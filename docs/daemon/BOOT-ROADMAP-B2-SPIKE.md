# B2 bootstrap spike: bypassing JUnit execution

Follow-up to the [Tier B handoff in PR #27](https://github.com/yschimke/compose-preview-daemon/pull/27).
The first experiment is implemented in
[`SandboxBootstrapSpikeTest`](../../daemon/android/src/test/kotlin/ee/schimke/composeai/daemon/SandboxBootstrapSpikeTest.kt).
It is test-only; the published daemon still uses its existing runner.

## What the upstream code actually provides

Checked against Robolectric 4.17-beta-4, commit
`f3c17bd2aeb66035ec3ece695f94ebc81588b79c`:

- [`SandboxBuilder`](https://github.com/robolectric/robolectric/blob/f3c17bd2aeb66035ec3ece695f94ebc81588b79c/simulator/src/main/java/org/robolectric/simulator/SandboxBuilder.java)
  exposes SDK selection and extra classpath entries. Its instrumentation configuration is private
  and it installs the base shadow map. It has no builder hooks for the daemon's bridge exclusions,
  user-package exclusions, or extra shadows. Adding its artifact is not a drop-in replacement.
- [`AppLoader.run`](https://github.com/robolectric/robolectric/blob/f3c17bd2aeb66035ec3ece695f94ebc81588b79c/simulator/src/main/java/org/robolectric/simulator/AppLoader.java)
  still calls `sandbox.getTestEnvironment().setUpApplicationState(...)`. The simulator does not
  demonstrate that application initialization, framework services or BouncyCastle can be skipped.
- [`Simulator`](https://github.com/robolectric/robolectric/blob/f3c17bd2aeb66035ec3ece695f94ebc81588b79c/simulator/src/main/java/org/robolectric/simulator/Simulator.java)
  is marked `@Beta` and owns a real-time event loop. The daemon needs its existing controlled-clock
  render loop instead.

Consequently, removing JUnit execution and reducing Android application setup are separate
experiments. The original handoff conflated their expected savings.

## Implemented experiment

Each trial launches two fresh JVMs with the existing spare-test JVM/classpath configuration:

- **junit:** `JUnitCore` runs a fixture under `SandboxHoldingRunner`.
- **direct:** a test-only subclass uses that same runner as a configuration adapter, obtains and
  configures its sandbox, enters `runOnMainThreadWithClassLoader`, calls `beforeTest`, invokes the
  fixture directly, and attempts both cleanup phases even on failure. It bypasses JUnit execution
  and the helper runner, but deliberately retains configuration resolution, application setup,
  and lifecycle callbacks. It is **not** a JUnit-free dependency graph or a simulator integration.

Both paths render `RedSquare` and then `MaterialButtonInteractionState` through `RenderEngine`.
The test compares PNG bytes and exported UI Automator hierarchy bytes, verifies dimensions and
an actual red pixel, and requires the Material fixture to expose button semantics. Three trials
alternate route order to reduce ordering bias. Each process has a bounded wait and a separate log.
No timing threshold is asserted in CI, and the experiment is skipped unless explicitly enabled.

## Reproduce

```bash
COMPOSEAI_BOOT_SPIKE=true ./gradlew :daemon:android:testDebugUnitTest --rerun \
  --tests '*SandboxBootstrapSpikeTest'
```

Outputs live under `daemon/android/build/boot-spike/trial-{0,1,2}/`: route logs, PNGs and hierarchy
JSON. `[measure]` records the actual child JDK version, JVM uptime at sandbox readiness, render
elapsed time, and JVM uptime when each PNG is complete. Uptime excludes the parent's process-launch
work, so it is not production-client latency. The Material timing follows a foundation render and
must not be presented as a cold Material render. This harness also omits the production host queue,
spare adoption, warm-up and optional extension registration.

For a separate class-loading diagnostic run, add `COMPOSEAI_BOOT_SPIKE_CLASS_LOG=true` to write
`classes.log` inside each route directory. Do not mix logged and unlogged timing samples. The
`LambdaForm`/`Species` load-event count is useful input to B1, but the span between first and last
load includes unrelated work: it is **not** an upper bound on the time static binding could save.
CPU attribution needs a profiler and a controlled implementation comparison.

## Measurements

Local Linux host, OpenJDK 17.0.19 child JVM, SDK 35, Robolectric 4.17-beta-4,
2026-09-10. Dependencies and filesystem cache were warm; each sandbox/JVM was fresh.
No class-load logging in these timing runs. Times are milliseconds:

| Route | Trial | Sandbox ready | First PNG complete (JVM uptime) | Red render | Material render after red |
|---|---|---:|---:|---:|---:|
| JUnit | 0 | 2225 | 3978 | 1748 | 213 |
| Direct | 0 | 2195 | 3915 | 1716 | 200 |
| Direct | 1 | 2212 | 3963 | 1746 | 205 |
| JUnit | 1 | 2249 | 4011 | 1757 | 204 |
| JUnit | 2 | 2243 | 4003 | 1755 | 207 |
| Direct | 2 | 2173 | 3911 | 1733 | 207 |

Median readiness: **2243 → 2195 ms** (48 ms). Median first PNG uptime:
**4003 → 3915 ms** (88 ms, about 2.2%). Both PNGs and both hierarchy exports
matched byte-for-byte in every trial. These are small local samples, not a
statistical performance guarantee. They do not support the handoff's expectation
of hundreds of milliseconds or ~700 classes removed just by bypassing execution.

A separate logged trial counted class-load events up to the recorded readiness/first-PNG
uptimes (prefixes `java.lang.invoke.LambdaForm$` and
`java.lang.invoke.BoundMethodHandle$Species_`, across all loaders):

| Route | All loads at ready | LambdaForm/Species at ready | All loads at first PNG | LambdaForm/Species at first PNG |
|---|---:|---:|---:|---:|
| JUnit | 8923 | 2900 | 16033 | 5224 |
| Direct | 8809 | 2882 | 15939 | 5211 |

The invocation machinery is still present on both routes. These prefix counts include
JVM machinery unrelated to Robolectric and are not a measurement of removable dispatch cost.
They provide a baseline for profiling B1, not a go-ahead for static instrumentation.

## Decision and remaining work

Keep the production runner until an experiment demonstrates enough benefit to justify maintaining
its lifecycle ourselves. This spike only tests bypassing execution; it does not establish the
benefit of eliminating all runner configuration machinery.

A complete B2 implementation needs shared configuration extraction (manifest/application selection,
SDK and modes, package acquisition, interceptors, and conditional shadows), equivalent teardown and
boot-error propagation, then coverage for consumer applications, resources, permissions, fonts,
held sessions, and sequential host reuse. Skipping `setUpApplicationState` additionally needs a
replacement for the state used by `ActivityScenario` and the data extractors. B4 can investigate
that capture prerequisite independently.

B1 remains fork work. A one-class prototype must preserve instance-shadow state, constructors,
`@RealObject`, SDK-dependent shadow selection and fallback dispatch; replacing all shadow calls
with `invokestatic` is not a general implementation. B3/B5 should remain conditional on a correct,
measured prototype rather than assuming sub-second boot from class counts.
