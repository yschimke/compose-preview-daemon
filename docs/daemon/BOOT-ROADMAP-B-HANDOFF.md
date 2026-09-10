# Handoff: Tier B of the boot roadmap (a Robolectric fork)

The exploratory task that follows [BOOT-ROADMAP.md](BOOT-ROADMAP.md) Tier A.
Written for whoever picks it up cold: what is already done and measured, what
the cold boot still costs and where, what each Tier B item would buy, how to
measure any of it, and the order to try things in. One spike has run: B2's,
recorded in [BOOT-ROADMAP-B2-SPIKE.md](BOOT-ROADMAP-B2-SPIKE.md) and folded
in below. Later measurements supersede several original hypotheses:
[fixed bindings](BOOT-FROZEN-BINDINGS-EXPERIMENT.md),
[Activity-free capture](BOOT-ROADMAP-B4-SPIKE.md) and
[dynamic CDS](BOOT-CDS-EXPERIMENT.md). Use those reports for measured results;
the return estimates below are historical hypotheses, not established gains.

## Where Tier A left things

Tier A (adoptable spare workers, released in `compose-preview-daemon` 3.0.1,
wired into the serve box by compose-preview-server #677) took a catalog daemon's
time to first pixel on the served path from 9-17 s to 1.4-2.3 s, measured
through the production client on a loaded CI-shaped box (JDK 17):

| daemon | `initialize` | first render |
|---|---|---|
| cold, no pool | 3.7-8.8 s | 5.3-8.3 s |
| adopting a warm spare | 0.67-1.10 s | 0.70-1.17 s |
| the next daemon, on a worker the previous one handed back | 0.88 s | 0.67 s |

So on a box that keeps spares, a **resident** signature never pays a boot. What
still pays one, in full, is:

1. the **first** daemon of a signature after a deploy, or after the pool's
   budget evicted that signature — every spare it will later adopt is itself a
   cold boot (4-22 s under load) plus a warm render (4-10 s), done off the
   request path but on the box's CPU and ~500 MB of its memory each;
2. every **local** `serve` and every Gradle / VS Code daemon, where there is
   no pool;
3. a daemon's own **in-process sandbox** (slot 0) whenever a held interactive
   session or a `@PreviewParameter` enumeration asks for it (3.3-5.0 s, once
   per daemon, deferred by `composeai.daemon.lazyInProcessSandbox`).

Tier B is about making that cold boot itself cheap. Every item is a change to
Robolectric, which is why it is a fork with upstreamable opt-ins rather than
daemon code. The daemon pins `org.robolectric:robolectric` 4.17-beta-4
(`gradle/libs.versions.toml`).

## Where a cold boot's time goes

From [STARTUP.md](STARTUP.md) (idle box, JDK 17, one sandbox, `4.3 s` JVM start
to ready):

| window | what | cost |
|---|---|---|
| 0.0-0.75 s | JVM, daemon classes, kotlin stdlib, guava, JUnit, Robolectric's injector | ~0.5 s |
| 1.5-2.25 s | BouncyCastle: 709 classes from a signed jar, then JCE registration; installed because `conscryptMode=OFF` | ~0.75 s |
| 2.25-5.25 s | the sandbox: 2,154 classes `SandboxClassLoader` defines (android-all, shadows, resources, `ActivityThread`) plus **2,392 `java.lang.invoke` hidden classes**, one per distinct invokedynamic call-site shape the instrumented framework links | ~3 s |

Then the warm render (4-10 s on a loaded box, ~2 s idle), of which roughly half
is the same `java.lang.invoke` linking for the paths a render touches, and the
rest is Compose, the font stack, Roborazzi's `ActivityScenario`/Espresso stack
and the PNG encoder loading and JIT-ing.

The JVM-level levers are already spent: AppCDS of the daemon classpath, no
remote bytecode verification, an unsigned BouncyCastle repack and C1-only
compilation together take 4.3 s to 3.0 s (STARTUP.md § "What each JVM-level
lever is worth"), and the serve daemons already get the CDS archive and the
verification flag. Everything below that line is inside Robolectric.

## The items, what each buys, and how to know

Ordered by expected return per unit of risk. Each has the measurement that
decides it *before* building the whole thing.

### B2. Boot on the sandbox API instead of JUnit — spiked, deprioritised

**What.** The daemon boots through `JUnitCore.runClasses(SandboxRunner)` with a
dummy `@Test` that holds the sandbox open (`SandboxHoldingRunner`, DESIGN.md
§ 9). The idea was to boot on Robolectric's simulator / sandbox API instead,
own the lifecycle, and skip the test-only application setup.

**Measured** ([BOOT-ROADMAP-B2-SPIKE.md](BOOT-ROADMAP-B2-SPIKE.md),
`SandboxBootstrapSpikeTest`, run with `COMPOSEAI_BOOT_SPIKE=true`). Bypassing
JUnit *execution* while keeping the runner's configuration and application
lifecycle saves 48 ms to sandbox-ready and 88 ms to first PNG (medians of three
trials, ~2 %), with byte-identical PNGs and hierarchy exports. The earlier
estimate of a few hundred ms and ~700 classes was wrong: the JUnit layer is
not where the time is. Two upstream facts limit what more B2 can do on
4.17-beta-4: the simulator's `AppLoader` still runs
`setUpApplicationState`, and its `SandboxBuilder` has no hooks for the
daemon's acquisition rules or extra shadows, so it is not a drop-in entry
point. Skipping application setup (framework initializers, BouncyCastle) is a
separate experiment that also needs a replacement for the state
`ActivityScenario` and the data extractors read, which is B4's territory.

**Decision.** Keep the production runner. Revisit only as part of B4 or if
B1 needs the daemon to own the boot.

### B1. Closed-world shadow binding (the big one)

**What.** Every method call in instrumented code is an `invokedynamic` whose
bootstrap asks `ShadowWrangler` whether a shadow applies, then links a
`MethodHandle` chain. That indirection exists so a later *test* can install
different shadows. The daemon's shadow set is fixed at boot, so the 2,392
hidden classes per JVM, and their linking on every first call, pay for a
flexibility never used. A fork adds an instrumentation mode that, given the
shadow map at instrumentation time, emits direct calls: `invokevirtual` to the
real method where no shadow applies, `invokestatic` to the shadow where one
does. `android-all-instrumented` is re-instrumented once per (Robolectric
version, shadow set) as a build-time job and published under our own
coordinate (B3).

**Return.** The `java.lang.invoke` cost leaves both the boot (~3 s of 4.3) and
the warm render (about half). Because nothing is defined at runtime any more,
every sandbox class becomes archivable (B5), which is what makes a sub-second
cold boot reachable.

**Spike (two to three days).** Do not start with the instrumenter, and do
not take a class-load count as the ceiling: the B2 spike counted ~2,900
`LambdaForm`/`Species` load events at sandbox-ready and ~5,200 at first PNG,
but that span includes unrelated work and the prefixes include JVM machinery
Robolectric does not own. The ceiling needs CPU attribution: profile a spare's
boot and warm render (async-profiler or JFR on the child JVM through
`COMPOSEAI_TEST_SPARE_JVM_ARGS`) and sum the time under `ShadowWrangler`,
`MethodHandle` linking and `LambdaForm` compilation. Then prototype on one
class: hand-rewrite a single hot instrumented framework class (a `View`
method with a known shadow) to direct calls, load it ahead of android-all on
the sandbox classpath, and confirm `ShadowWrangler` tolerates a class it did
not instrument. The prototype must preserve instance-shadow state,
constructors, `@RealObject`, SDK-dependent shadow selection and fallback
dispatch; replacing every shadow call with `invokestatic` is not the general
implementation. If it holds, the fork work is in
`org.robolectric.internal.bytecode.ClassInstrumentor`, behind a
`-Drobolectric.instrumentation=static` switch, with the shadow set frozen
after the first sandbox.

**Risk.** Shadows registered at runtime (`@Config(shadows=…)`, the daemon's
own additions) must be in the frozen set, or their targets silently call the
real method. Enumerate the daemon's shadow set first; it is small.

### B5. Make the sandbox archivable

**Measured correction.** The existing Robolectric 4.17-beta-4 loader already
archives sandbox classes with JDK 17 dynamic CDS. `Composer` appears under an
unregistered loader in the archive and loads from shared objects on restart.
The [controlled comparison](BOOT-CDS-EXPERIMENT.md) rejects the proposed jar-backed
loader rewrite: it is slower and restores the same measured AndroidX/Kotlin classes.
CDS is independent of B1. It still requires training on the matching JVM/classpath;
sub-second cold startup and Leyden gains remain unproven.

### B4. A capture path without an Activity

**What.** The warm render and every catalog's cold first render load
`ActivityScenario`, Espresso and the `Activity`/`Window`/`ActionBar` stack
because Roborazzi's `captureRoboImage` goes through them. Compose needs a
`View` tree with lifecycle and saved-state owners and a `HardwareRenderer` to
draw a `RenderNode`; none of that needs an Activity. A daemon-owned capture —
`ComposeView` under a `FrameLayout` attached through `WindowManager`, tree
owners set by hand, drawn with `HardwareRenderer` — drops ~700 classes and
their call-site linking from the first render, and removes the ActionBar
workaround Roborazzi logs five times per render.

**Return.** On an adopted worker the catalog's first render is 0.34-0.65 s
(foundation vs Material 3, `RobolectricHostSpareAdoptionTest`); a second
Material render is 0.27-0.35 s. B4 attacks the difference plus whatever the
Activity stack costs in the warm render. Not a boot item, but it is the one
Tier B change that also speeds the *served* path, and it is daemon code, not
fork code.

**Spike (two days).** Render the fixture catalog's `RedSquare` and
`MaterialButtonInteractionState` through a hand-built `ComposeView` capture on
an existing sandbox, byte-compare the PNGs against `captureRoboImage`'s, and
time both. Data products that read the Activity (the layout inspector, the
semantics tree) must still resolve; check `AndroidCaptureGutterLaneTest` and
the semantics extractor on the new path before going further.

### B3. Our own SDK artifact

Publishing `compose-preview-android-all`: statically instrumented (B1),
trimmed of framework services a preview never reaches, a lazy
`SystemServiceRegistry`, shipped beside a pre-built class archive (B5) and the
pre-extracted native runtime. SDK management stays Robolectric's, one
coordinate per SDK level, pointed at our artifact. This is the delivery
vehicle for B1/B5, not a separate win; do it when B1 works.

### B6, B7. Smaller, and mostly moot

B6 (parallel class definition) is useful only until B5 removes the definition
cost. Of B7, the font-dir override was tried and dropped (Robolectric 4.17
copies fonts unconditionally), BouncyCastle laziness needs the fork, and the
`doNotAcquirePackage` move of pure-JVM libraries is a daemon change with the
coroutines trap noted in the roadmap.

## How to measure any of it

- **The adoption tests are the harness.** `RobolectricHostSpareAdoptionTest`
  (`:daemon:android`) launches a spare the way the pool does and prints
  `[measure]` lines: spare RSS, adoption time, the catalog's first renders.
  `COMPOSEAI_TEST_SPARE_JAVA` runs the spare on another JDK,
  `COMPOSEAI_TEST_SPARE_JVM_ARGS` adds flags; with
  `-XX:NativeMemoryTracking=summary` in the latter it also prints an NMT
  breakdown. A forked Robolectric goes on the spare's classpath through the
  same seams.
- **End to end**, `SpareAdoptionAndroidRealModeTest` (`:daemon:harness`,
  `-Pharness.host=real -Pharness.target=android`) times cold vs adopted vs
  returned daemons through the production client.
- **Class-level attribution**: `-Xlog:class+load:file=…` on a spare, then
  group by loader and by whether the name matches `LambdaForm`/`Species`;
  STARTUP.md § "Where the time goes" was produced that way.
- **The numbers to beat** are in [SANDBOX-POOL.md](SANDBOX-POOL.md) § "Spare
  workers", "Measured", and in STARTUP.md's lever table. Report every result
  against those, on the same box, with the JDK named.

## Suggested order

1. B1 ceiling by profiling (not by class counts), then the one-class
   prototype. This is the go/no-go for the fork.
2. B4 spike in parallel; it is independent, daemon-only, and the only place
   where skipping application setup can be evaluated safely.
3. Only if B1's prototype holds: the instrumenter change, B3 publishing, B5
   archive, in that order.
4. B2 stays parked (spiked at ~2 %); it comes back only if B1 or B4 needs the
   daemon to own the boot lifecycle.

## Out of scope for this task

CRaC (A2) needs a CRaC JDK and CRIU privileges in the container; GraalVM
native-image and Layoutlib are rewrites. All three are listed in the roadmap
with their reasons and stay there.
