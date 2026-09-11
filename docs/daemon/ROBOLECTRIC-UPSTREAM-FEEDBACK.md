# Robolectric upstream feedback

Living backlog from embedding Robolectric in a long-lived Compose render daemon.
Last reviewed: **2026-09-11**. Source/API observations below were rechecked against
**Robolectric 4.17**, commit [`0706415`](https://github.com/robolectric/robolectric/tree/0706415db828e1bdcf4f0c820784fa760d0185a2).
Measurements retain their original version and launch profile; they are not all
measurements of stock 4.17 or of the simulator.

Update an existing item when new evidence changes it. Keep: category, owner,
evidence/version, impact, current workaround, proposed upstream change, validation
needed, and upstream issue/PR status. A hypothesis becomes a bug report only after
a minimal reproducer establishes ownership. Record rejected ideas too, so they do
not get rediscovered as apparent wins.

No upstream issues or PRs have been filed as part of this document yet.

## Priorities

| ID | Topic | Category | Evidence/status | Next useful upstream step |
| --- | --- | --- | --- | --- |
| R01 | Shared native data extraction | Friction, performance, missing API | Local production workaround | Design a supported cache/extraction SPI |
| R02 | Immutable shadow-map mode | Performance | Guarded prototype with parity and CPU evidence | Discuss opt-in semantics, then source-level prototype |
| R03 | Runner-neutral configuration/lifecycle | Test-library coupling, missing entrypoint | Source review + direct-bootstrap spike | Define a supported embedding session API |
| R04 | Configurable simulator sandbox | Simulator, missing API | Released-source review | Builder hooks for instrumentation, shadows and modes |
| R05 | Caller-controlled simulator loop | Simulator, missing entrypoint | Released-source review; no full simulator integration benchmark | Separate setup, stepping, presentation and close |
| R06 | Crypto-provider initialization | Performance | Overlap prototype; unresolved lifecycle cases | Define provider policy/completion before optimization |
| R07 | Activity-free window hosting | Friction, missing API | Local experimental host; shared ecosystem ownership | Discuss a supported window/capture host seam |
| R08 | CDS and embedding diagnostics | Documentation, performance | CDS works with existing loader | Publish a validated embedding/warm-up recipe |
| R09 | Application reload and classloader lifetime | Bug investigation, diagnostics | Our leak fixed; bounded AWT root remains | Minimal reproduction before assigning upstream ownership |
| R10 | Layered performance telemetry | Friction, simulator feedback | Local profiles; simulator already has reporters | Expose comparable embedding phase/lifecycle measurements |

## R01 — Share immutable native data without private reflection

**Owner:** Robolectric native runtime. **Status:** API request backed by a production
workaround; not a claim that exit hooks can handle forced termination.

The [default loader](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/nativeruntime/src/main/java/org/robolectric/nativeruntime/DefaultNativeRuntimeLoader.java)
uses per-loader temporary extraction and keeps font/ICU/hyphenation copy helpers
private. Our [`SharedNativeRuntimeLoader`](../../renderers/android/src/main/java/ee/schimke/composeai/renderer/SharedNativeRuntimeLoader.java)
shares roughly 185 MB of immutable data, while keeping a separate native-library
image per sandbox as required by JVM/platform loading. Fork-heavy workers otherwise
repeat substantial extraction and can leave temporary data when killed.

**Workaround:** service-selected subclass, reflective extraction calls, versioned
cache keys, file locks, completion markers and owned temporary library copies.
**Request:** a supported immutable-data directory/cache policy or extraction SPI,
separate from native-library loading and sandbox lifetime. Preserve current defaults.
**Acceptance:** concurrent JVMs, interrupted extraction, SDK/platform/version changes,
read-only completed caches, corrupt-cache recovery, and no worker deleting another
worker's files. Existing [loader tests](../../renderers/android/src/test/kotlin/ee/schimke/composeai/renderer/SharedNativeRuntimeLoaderTest.kt)
are useful reproduction material, not an upstream compatibility guarantee.

## R02 — Opt in to shadow bindings that cannot change

**Owner:** Robolectric instrumentation/dispatch. **Status:** measured prototype,
not a production recommendation or permission to disable normal invalidation.

A worker with a fixed shadow map still pays for fallback/rebinding and switch-point
machinery. The [guarded binding experiment](BOOT-FROZEN-BINDINGS-EXPERIMENT.md)
retains shadow semantics and stack cleanup, rejects nonempty invalidation after
binding, and compares frozen mutable and constant call sites. With JDK17, C1 and
method-handle threshold30, the constant variant reduced total CPU by **22.7%** in
three rotated 73-frame trials; all 657 frame comparisons passed. These settings
are part of the result. The measured beta sandbox bytes were later verified
identical in released 4.17.

**Workaround:** SHA-pinned copied dependency jars; no Gradle-cache mutation.
**Request:** an explicit fixed-map sandbox mode with an enforceable freeze boundary.
**Acceptance:** constructors, instance shadows, `@RealObject`, SDK shadow selection,
exceptions, independent sandboxes, held input sessions, worker reuse, and rejection
of unsupported map changes. The experiment does not justify a global static-dispatch
rewrite or summing all `java.lang.invoke` samples as removable work.

## R03 — Supported embedding lifecycle without runner-shaped configuration

**Owner:** Robolectric runner/configuration API. **Status:** coupling/API friction;
small measured execution saving, not a large startup claim.

Our [`SandboxHoldingRunner`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/SandboxHoldingRunner.kt)
overrides configuration and extra-shadow hooks taking JUnit `FrameworkMethod`.
The daemon currently enters through a dummy test to obtain the normal lifecycle.
A [direct-bootstrap spike](BOOT-ROADMAP-B2-SPIKE.md) bypassed JUnit execution but
still used its runner as a configuration adapter: median ready improved only
**48 ms**, first PNG uptime **88 ms**. It did not remove JUnit from the dependency graph.

There is already an internal
[`TestEnvironment`](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/robolectric/src/main/java/org/robolectric/internal/TestEnvironment.java)
with configuration/manifest setup and teardown/reset methods; its signatures do
not themselves require JUnit types. The gap is a supported complete embedding path.

**Request:** an explicit configuration/session object and lifecycle entrypoint with
JUnit adapters layered over it, rather than requiring embedders to subclass runner
hooks. **Acceptance:** manifest/application selection, conditional shadows,
interceptors, main-thread/classloader entry, setup failure cleanup, normal close,
repeat sessions, and documented dependency ownership. Audit the resolved graph
before claiming a particular test dependency can actually be removed.

## R04 — Simulator sandbox customization

**Owner:** Robolectric simulator. **Status:** verified 4.17 API gap.

[`SandboxBuilder`](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/simulator/src/main/java/org/robolectric/simulator/SandboxBuilder.java)
exposes SDK and extra classpath entries, but constructs instrumentation privately,
installs the base shadow map, and chooses resource/SQLite modes internally. Our
bridge classes must cross the sandbox boundary unchanged; application packages,
interceptors and conditional shadows also need deliberate configuration.

**Workaround:** retain the existing runner. **Request:** supported hooks for
acquisition/instrumentation exclusions, extra shadows, interceptors and modes,
with validation before the first binding. Prefer reusing the same configuration
model as tests over maintaining a second diverging defaults list.
**Acceptance:** a daemon fixture that renders, swaps application classes, uses a
custom shadow and closes/reopens through the simulator builder without private
reflection. We have not yet run that complete integration.

## R05 — Separate simulator setup from clock/loop ownership

**Owner:** Robolectric simulator. **Status:** source-backed embedding feedback,
not a reported simulator correctness bug.

[`Simulator.start`](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/simulator/src/main/java/org/robolectric/simulator/Simulator.java)
configures graphics, starts presentation/control plumbing, optionally launches an
Activity, and enters its private loop. That loop advances Android time from elapsed
host time; its stop flag is local and wired to JVM shutdown. Headless mode, remote
control hooks and performance reporters already exist—do not request them as absent.

**Request:** a session API separating initialization, explicit `step`/`advanceUntilIdle`,
frame delivery, optional UI/real-time pumping, and bounded `close`/cancellation. A
preview daemon must own deterministic time and capture individual frames, then
serve another request without exiting the JVM.

[`AppLoader`](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/simulator/src/main/java/org/robolectric/simulator/AppLoader.java)
also sets fixed mode choices, initializes application state and selects a launcher
Activity. Ask for explicit application/entrypoint configuration; do not assume
using simulator skips Android initialization. **Acceptance:** deterministic clock,
animations and input, headless capture, cancellation during startup/frame work,
and repeated sessions with no global-loop ownership surprises.

## R06 — Avoid serial eager crypto-provider work where policy allows

**Owner:** Robolectric application environment. **Status:** promising startup
prototype with unresolved completion/failure cases.

[`AndroidTestEnvironment`](https://github.com/robolectric/robolectric/blob/0706415db828e1bdcf4f0c820784fa760d0185a2/robolectric/src/main/java/org/robolectric/android/internal/AndroidTestEnvironment.java)
constructs a static BouncyCastle provider eagerly. The
[concurrent construction probe](BOOT-BC-EXPERIMENT.md) preserves registration and
provider behavior: released-4.17 median readiness improved **210 ms / 5.4%**, but
whole-workload CPU rose **1.4%**. The crypto fixture and negative control test actual
provider use; arbitrary cryptographic behavior is not covered.

**Request:** discuss a provider-policy/lifecycle seam or safe overlap of construction
with independent setup. Do not simply omit the provider. **Acceptance:** provider
ordering, application-onCreate crypto, existing provider instances, Conscrypt on/off,
concurrent sandboxes, construction failure and guaranteed join/cleanup. In particular,
Conscrypt-off plus an existing BC provider can skip the registration/join branch in
the prototype. This must be resolved before proposing a production patch.

## R07 — Activity-free render/capture seam, with clear ecosystem ownership

**Owners:** Robolectric window hosting; Compose test APIs and Roborazzi capture APIs
for their own coupling. **Status:** prototype/API discussion, not one upstream bug.

The [standalone-window spike](BOOT-ROADMAP-B4-SPIKE.md) avoids Activity creation but
must install tree owners, attach a real window, drain attachment and deliver window
focus. Broader testing caught a missing cursor caused by our omitted focus event;
that was a prototype bug. A two-second wait came from our attachment ordering with
Compose's root registry, not proof that Robolectric has a needless fixed timeout.

**Request:** a documented supported offscreen window host with attach/focus/teardown
semantics, usable with deterministic frame stepping. Roborazzi's Activity/Espresso
capture assumptions belong in a separate conversation with that project. **Acceptance:**
IME/focus, dialogs/popups, AndroidView, Activity-dependent composables, themes,
wrap-content and interaction sessions. Keep the Activity fallback until those work;
four static fixtures are not sufficient evidence for switching defaults.

## R08 — Document the CDS path that already works

**Owner:** Robolectric documentation/embedding examples, with JVM constraints.
**Status:** confirmed capability and rejected rewrite.

[Dynamic CDS works with the existing sandbox loader](BOOT-CDS-EXPERIMENT.md).
A proposed jar-backed loader rewrite was slower and unnecessary. Under the stated
C1/threshold/constant-binding profile, trained CDS reduced ready time by about
1.06 s; this excludes training and requires matching JVM/classpath inputs. An
unmodified Robolectric jar also worked with its own archive.

**Request:** a reproducible embedding/warm-up example with input fingerprints,
archive rejection diagnostics and safe fallback. Do not advertise first-ever launch
as trained-archive speed or claim byte-array-defined sandbox classes cannot use CDS.
**Acceptance:** mismatch detection and actual shared-class source diagnostics,
including user classpath/SDK changes and stale archives.

## R09 — Track reload retention without misattributing it

**Owners:** initially our renderer; remaining AWT integration ownership unresolved.
**Status:** our growing leak fixed; no confirmed upstream leak report ready.

[Heap-root analysis](BOOT-LOCALE-CACHE-RETENTION.md) identified our
`LocaleCompositionLocals.resolutions` map as the owner retaining every application
loader. Weak keys and values fixed it. The subsequent 60-reload run retained only
two loaders: the current child and the initial child held by AWT `AppContext`.
The latter is a bounded observed root, not evidence of growth per reload.

**Next:** minimize the AWT initialization/TCCL case and determine whether our embedder
should initialize it under a stable loader or Robolectric should provide a lifecycle
hook. Ask for a supported application-loader replacement/unloading recipe and
clear framework/application ownership. Do not file our cache bug against Robolectric.
**Acceptance:** forced-GC root paths plus repeated reloads of actual child-loaded
classes; class counts, RSS high-water marks or parent-loaded fixtures alone are
insufficient to establish a leak.

## R10 — Make embedding performance costs observable

**Owner:** Robolectric profiling/embedding APIs and simulator integration.
**Status:** instrumentation/documentation request.

The simulator already integrates `PerfStatsCollector` and reporters. Our daemon still
needs explicit boundaries for sandbox construction, class loading/instrumentation,
native-data extraction/loading, provider setup, application setup, first frame and
teardown. [CPU profiles](BOOT-CPU-PROFILE.md) show why class counts or wall spans
alone are misleading: application, JIT and GC costs overlap.

**Request:** stable optional phase/lifecycle events usable outside a test runner,
with low-overhead examples reporting process CPU and memory alongside latency.
Distinguish heap, RSS/PSS, code cache, metaspace and virtual address space. Validate
under repeated renders, multiple workers and CPU-constrained environments rather
than recommending one GC/compiler policy from an isolated startup benchmark.
The daemon's own forced per-render GC and its `nativeHeapMb` approximation are our
telemetry decisions, not automatically Robolectric defects.

## How to turn an item into upstream feedback

Attach a minimal project or a source-level patch, exact released/source versions,
reproduction command, before/after output checks, and a benchmark with cold/warm
conditions and CPU/memory scope stated. Link the issue/PR beside its ID and record
maintainer feedback, revisions and any rejected approach here. Recheck the current
upstream API before filing; this document intentionally pins observations rather
than assuming the next release still has the same limitations.
