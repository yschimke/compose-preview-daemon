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

Daemon tracking issue: [#63](https://github.com/yschimke/compose-preview-daemon/issues/63).
No issues or PRs have been filed in Robolectric itself as part of this document yet.

## Priorities

**Start with R01, then validate R02 in a conventional test suite.** These offer the
best combination of practical daemon impact and potential benefit beyond our use
case. Priority is the order in which we should spend upstream preparation effort,
not a claim of bug severity or that a prototype is ready to merge.

- **P1 — pursue now:** substantial measured/local impact and plausible broad benefit.
- **P2 — next:** useful cross-cutting tooling or meaningful embedding needs, but less
  proven benefit, narrower applicability, or unresolved tradeoffs.
- **P3 — exploratory:** limited demonstrated impact or unclear upstream ownership.

Within each tier, the table is in suggested working order. General applicability
is an assessment from the relevant code paths; ordinary test-suite gains remain
unmeasured. Evidence confidence is separate from priority.

| Order | Priority / ID | Impact on our daemon | Applicability to existing users | Evidence / readiness | Next action |
| --- | --- | --- | --- | --- | --- |
| 1 | **P1 · R01 — native data cache SPI** | High: avoids repeated extraction of roughly 185 MB immutable data and removes private-reflection maintenance. Local workaround already delivers the saving; upstream value is supported reuse and robustness. | Broad within native-graphics unit/screenshot workloads, especially fresh CI forks; no effect on tests that never load native data. | High confidence in local need; production workaround and loader tests. | Propose a supported shared-data cache/extraction SPI and reproduce with concurrent screenshot-test forks. |
| 2 | **P1 · R02 — immutable shadow bindings** | High potential: measured 22.7% total CPU reduction under the explicitly tuned prototype profile. | Potentially broad for stable-map test/screenshot sandboxes; incompatible with unhandled shadow-map changes. | Medium: guarded prototype and parity evidence; no ordinary-suite benchmark. | Establish test-runner freeze boundaries and benchmark a conventional suite before proposing opt-in support. |
| 3 | **P2 · R10 — lifecycle/CPU/memory telemetry** | Medium: makes further optimization and regressions attributable, with no direct speedup claimed. | Broad diagnostic value for unit tests, screenshot CI and simulator users. | High confidence in diagnostic need; existing PerfStats facilities must be reused. | Identify missing phase coverage and propose minimal optional lifecycle events. |
| 4 | **P2 · R08 — supported CDS recipe** | Medium: measured startup benefit; existing loader already works, so no runtime rewrite needed. | Potentially broad for repeated JVM forks with stable inputs; training and invalidation may limit practical benefit. | High confidence in capability; real Gradle-workflow amortization unmeasured. | Publish a validated recipe with fingerprints, rejection diagnostics and total training/reuse cost. |
| 5 | **P2 · R06 — crypto initialization policy/overlap** | Medium: 210 ms / 5.4% readiness gain, but 1.4% more total CPU in the probe. Loaded-server tradeoff matters. | Broad setup path for Android tests; benefit amortizes with environment reuse and may matter most for short forks. | Medium: measured prototype with unresolved join/provider/failure cases. | Resolve lifecycle correctness and compare CPU plus cold/warm test-suite latency before advocating overlap. |
| 6 | **P2 · R04 — simulator sandbox configuration** | High for adopting simulator: customization is a migration prerequisite; no measured speedup. | Narrow today: simulator embedders; conventional test runners already expose relevant configuration. | High confidence in pinned-source gap; complete simulator integration not tested. | Build one custom-shadow/instrumentation reproducer and request shared configuration hooks. |
| 7 | **P2 · R05 — simulator step/close lifecycle** | High for adopting simulator: deterministic capture and bounded session ownership are prerequisites. | Narrow today, with potential for simulator-backed screenshot/automation tools; existing test loops are unaffected. | High confidence in source observation; no integration benchmark. | Demonstrate deterministic capture and repeated sessions, then propose setup/step/close separation alongside R04. |
| 8 | **P2 · R18 — constructor instrumentation writes duplicate local-variable rows** | Blocking for any preview reaching such a class (Wear Compose Material's `MaterialTheme` → `Shapes`); fixed locally by cleaning the instrumented bytes. | Any Robolectric test that instruments a class whose constructor runs code with debug locals before `this(...)` / `super(...)` — routine in Kotlin default-arguments constructors with inline getters. | Confirmed with a minimal reproducer (stock `ClassInstrumentor` output fails `defineClass`); owner is Robolectric. | File upstream with the reproducer and the proposed fix below. |
| 9 | **P3 · R03 — runner-neutral embedding API** | Medium maintenance/dependency friction; measured bypass saving is only 48 ms ready. | Mainly standalone tools and non-JUnit integrations; ordinary JUnit users already benefit from the runner. | High confidence in coupling; dependency removal not demonstrated. | Coordinate with R04/R05 session design and audit dependencies before requesting API extraction. |
| 10 | **P3 · R07 — Activity-free window host** | Potential setup saving and simpler offscreen hosting; broad compatibility remains unproven. | Conditional benefit for isolated View/composable screenshot tools; Activity-dependent cases still need a fallback. | Medium/low readiness: experimental host, integration bugs found, shared ecosystem ownership. | Validate focus/IME/popups/themes and establish Robolectric vs Compose/Roborazzi ownership. |
| 11 | **P3 · R09 — reload lifetime investigation** | Our growing leak is fixed. Remaining initial AWT loader root is bounded, so no established ongoing growth benefit. | Unknown for normal unit/screenshot tests; custom application reloads distinguish our case. | No confirmed Robolectric bug; ownership unresolved. | Minimize AWT/TCCL retention first; escalate priority only if growth or substantial retained memory is established. |
| 12 | **P3 · R11 — two input clocks in a held Compose session** | Fixed locally (input dropped by a hosted `View` player); upstream value is documentation, not a speedup. | Any Robolectric Compose test that injects input into an `AndroidView` whose `View` reads `SystemClock`, once the looper clock and `mainClock` have drifted. | High confidence in mechanism (measured, before/after test); ownership split across Compose ui-test, Robolectric and Roborazzi, not established. | Reduce to a one-file Robolectric + Compose test before deciding where, if anywhere, to report it. |
| 13 | **P3 · R13 — API 37 needs espresso-core 3.7.0+** | Blocking at API 37 until the floor is on the classpath; fixed by compose-ai-tools injecting the floor. | Every Robolectric API 37 test that injects input through Espresso before 3.7. | Confirmed end to end; the owner is AndroidX Test, which already fixed it in 3.7.0. | None beyond suggesting a note in Robolectric's API 37 release notes. |
| 14 | **P3 · R14 — caller-scoped wall clock for instrumented code** | Fixed locally with a shadow of `ShadowSystem` plus a stack-walk caller check; upstream value is a supported way to give instrumented app code a fixed wall clock. | Any Robolectric screenshot test of a component that formats `System.currentTimeMillis()`; ordinary app code is uninstrumented, so it reads the host clock today. | High confidence in mechanism (source + end-to-end tests); no upstream discussion yet. | Ask whether a configurable wall-clock offset, or a caller-aware `Interceptor` hook, would be accepted. |
| 15 | **P3 · R15 — no hook to choose the sandbox classpath** | Medium: the Bazel/bundle lanes rendered on the daemon's Compose instead of the app's; worked around via the context loader. | Narrow: embedders whose sandbox classpath differs from the JVM's. | High confidence in source observation; workaround tested in-process. | Propose an extra-classpath option on `InstrumentationConfiguration`. |
| 16 | **P3 · R17 — shadowing a composable function** | Fixed locally: a custom `@PreviewWrapper` that ends in `RemoteContentPreview` now captures through the connector. Upstream value is documentation, not a speedup. | Any Robolectric user shadowing a `@Composable` top-level function in a non-default package. | High confidence (end-to-end test with a negative control); no upstream discussion. | None until a second case needs it; then ask whether shadows of composable functions are a supported pattern. |
| 17 | **P3 · R19 — a started system intent resolves to nothing** | Fixed locally: an app tour passes over a system screen it can't open instead of failing the launcher activity's hero. Upstream value is documentation. | Any Robolectric user that follows `getNextStartedActivity()` from an app which asks for a runtime permission (or opens another system screen) on first composition. | High confidence in mechanism (a predicate unit test with a real `requestPermissions` call; the tour flow is not exercised); expected simulator behaviour, no upstream bug established. | None; revisit only if Robolectric ships a resolvable permission-controller activity. |

## Applicability beyond the daemon

These are source-based applicability assessments, **not measurements from ordinary
unit or screenshot test suites**. All performance numbers below come from our
render workloads. Reproduce in a representative test suite before claiming the
same benefit there. “Conditional” means the test must exercise the named path.

| ID | Existing unit tests | Existing screenshot tests | Daemon / simulator distinction and validation |
| --- | --- | --- | --- |
| R01 | Conditional: tests using native graphics/data loading, especially fresh JVM forks; tests that never initialize the native runtime do not pay this extraction cost. | Likely relevant with native graphics and forked CI workers; actual extraction reuse depends on sandbox/loader reuse. | Shared persistent caches matter more across many worker processes. Measure cold and warm extraction, disk usage and parallel forks in a normal screenshot suite. |
| R02 | Conditional: instrumented calls use this machinery, but per-test shadows/configuration and sandbox reuse may require map changes. | Potentially relevant to render-heavy tests with stable shadow bindings; no screenshot-suite speedup established. | Our fixed-map worker makes an explicit freeze feasible. Check real test-runner invalidation boundaries before enabling it for tests; never freeze a mutable shared sandbox. |
| R03 | Little direct friction for conventional JUnit users: runner integration is useful there. Custom runners or other test frameworks may benefit. | Runner-based screenshot tests already have lifecycle setup; standalone screenshot tools may hit the same coupling. | Primarily embedding/API design. Validate a non-JUnit consumer and dependency graph; do not sell the small measured bypass win as a suite-wide optimization. |
| R04 | Existing runner configuration already provides relevant customization; this is not a missing API in conventional tests. | Existing runner-based capture is unaffected; relevant if capture tools adopt the simulator. | Simulator-specific builder gap. Compare equivalent test and simulator configurations and demonstrate custom shadows/instrumentation before reporting migration friction. |
| R05 | Normal tests already control execution through test/looper APIs; the reported private loop is the simulator path. | Deterministic frame/clock control matters, but existing test-based capture does not use this simulator loop. | Primarily embedding the simulator for deterministic automation and repeated sessions. Validate simulator-backed screenshot capture; do not claim ordinary screenshots inherit this limitation. |
| R06 | Applicable when AndroidTestEnvironment/provider initialization is reached; short, freshly forked runs may expose startup cost. Static initialization is amortized according to defining-loader reuse, not necessarily paid per test. | Same setup path can matter, especially short suites/forks; rendering may dominate longer runs. | Not daemon-only, but our overlap result increases total CPU slightly. Measure cold forks and warm suites, with crypto-using tests and provider-order assertions. |
| R07 | Conditional: view/window tests that do not require an Activity could use a supported host; Activity lifecycle tests still need an Activity. | Potentially useful for isolated composable/View capture. Activity-dependent content, themes, popups and IME require broader validation. | Shared tooling opportunity across projects, not a confirmed Robolectric bug. Our missing focus and attachment-order problems were integration mistakes. |
| R08 | Potential benefit for repeated test JVM launches with a sufficiently stable JVM/classpath; build changes can invalidate the archive and training has a cost. | Same opportunity for repeated screenshot forks, subject to archive compatibility and amortization. | Not daemon-only. Daemon distributions may have a more stable framework classpath. Benchmark full training/reuse cost in a real Gradle test workflow; the current loader already supports CDS. |
| R09 | Our application-child-loader cache leak is not established in ordinary tests. Runner-owned sandboxes have different lifetimes. AWT/TCCL retention needs a separate reproducer. | AWT/image initialization could encounter related lifetime issues, but no conventional screenshot-test leak has been demonstrated. | Replacing application loaders within one long-lived sandbox is our distinguishing workload. Our growing leak is fixed; the remaining initial AWT root is bounded. Do not file an upstream leak without ownership evidence. |
| R10 | Useful for diagnosing suite/fork setup, instrumentation and teardown; existing PerfStats facilities should be reused. | Useful to separate render cost from setup, JIT and GC, especially parallel CI. | Embedders need stable lifecycle access outside the runner. Demonstrate missing phase coverage in both a standard test report and simulator session before proposing new events. |

Follow the ranked table above: **R01 → R02** first; then **R10 → R08 → R06 →
R04 → R05**; finally **R03 → R07 → R09**. Broad applicability raises priority,
but does not substitute for demonstrated impact. Frame **R03/R04/R05** explicitly
as embedding/simulator API feedback. **R07** needs cross-project API design; **R09**
remains an ownership investigation. None of these assessments establishes a new
correctness bug in ordinary Robolectric tests.

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

**Additional hosting evidence (Roborazzi 1.74.0 / Robolectric 4.17):** the
[PNG decoding investigation](BOOT-PNG-RESIZE-EXPERIMENT.md) observes 480×1088
captures being extended to 480×1200 at density 2. The same worker logs Roborazzi's
SDK-35+ ActionBar-overlap workaround and its warning about layout invalidation.
This is a theme/hosting lead, not proof that the workaround causes the 56 dp
shortfall or a Robolectric bug. Reproduce with and without the intended test
manifest and no-action-bar theme before assigning ownership.

**Applicability / priority:** potentially observable in ordinary unit or screenshot
tests capturing ActionBar-hosted content through Roborazzi on the affected SDKs;
it is not inherently daemon-only. Tests with a no-action-bar host may avoid this
path. No ordinary-suite impact measurement exists, so R07 remains **P3**. Our
repeated PNG decoding was our own pipeline overhead, and is addressed locally.

**Existing capture API to try first:** Roborazzi 1.74.0 already exposes an
experimental `AwtImageWriter`/`JvmImageIoFormat` that receives the cropped/scaled
image before encoding. The [image-comparison investigation](BOOT-SETTLE-IMAGE-COMPARISON-EXPERIMENT.md)
records the pinned source and remaining ownership/reporting/failure questions.
The [in-memory settling implementation](BOOT-IN-MEMORY-SETTLING-EXPERIMENT.md) now
validates cropped-image lifetime after canvas release and PNG metadata with the
real painter, plus daemon dialog/override paths. Plain-JVM tests must set
an explicit screenshot capture type because the options default reads
Robolectric's `ConfigurationRegistry`; this is embedding/test-setup friction,
not a demonstrated bug in ordinary runner-based tests. Its performance opportunity
is avoiding intermediate encodes in multi-sample capture, not speeding up a normal
one-shot screenshot. Do not request an image-writer hook as missing or attribute
our intermediate PNG round trips to Robolectric. This remains **P3** hosting/API investigation;
broader ordinary screenshot-suite benefit remains unproven.

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

[Contemporaneous memory checkpoints](BOOT-NATIVE-MEMORY-CHECKPOINTS.md) now
separate heap, JIT-code and anonymous-mapping residency during 300 real application
reloads. Most PSS growth is outside the Java heap. Anonymous mappings outside NMT
reservations include JVM malloc arenas, allocator-retained pages and JDK/third-party
native allocations; they do not establish Robolectric ownership or a native leak.
These JVM costs may also occur in long-lived unit/screenshot-test forks, but the
measured workload is our application-reloading daemon. **Priority remains P3** until
ownership and consequential retained growth are demonstrated.

A [disposable-worker allocator probe](BOOT-ALLOCATOR-TRIM-EXPERIMENT.md) reclaimed
182.90 MiB PSS after 300 renders (loader swaps every 50 renders) with `malloc_trim(0)`; 50 further renders matched
PNG/UIA output and partially refilled residency. The original description
incorrectly claimed a reload per render; the saved run used seven loaders total.
Heap/code residency was unchanged.
This establishes reclaimable allocator pages, not a Robolectric native leak or a
production trim/recycling policy. The mechanism also applies to long-lived test
JVMs, but its magnitude is unmeasured in ordinary unit/screenshot suites.

A [three-pair allocator arena experiment](BOOT-ALLOCATOR-ARENAS-EXPERIMENT.md)
reduced median end PSS by 127.70 MiB (16.5%) with `MALLOC_ARENA_MAX=2`, at a
1.1% mean process-CPU increase. One candidate had more page faults and longer wall
time. This is a local launch-policy tradeoff; no production default changes or
ordinary test-suite benefit claims follow from these single-worker measurements.

The [corrected per-render-reload pair](BOOT-ALLOCATOR-REAL-RELOADS.md) verifies
350 distinct loaders per variant, two live loaders at every checkpoint, and
351 matching PNG/UIA pairs. Default versus two arenas reaches 964.04 versus
702.85 MiB PSS before trim at reload 300. Trimming reclaims 283.81 versus
115.92 MiB, with partial rebound after 50 further reloads. This strengthens the
local allocator-retention evidence; it does not establish a recycling interval,
Robolectric ownership, or ordinary-test benefit. Priority remains P3 upstream.

A [two-worker/two-CPU comparison](BOOT-ALLOCATOR-CONCURRENT-EXPERIMENT.md) finds
no consistent substantial arena-limit benefit: median observed concurrent peak
PSS changes by 0.8% and mean CPU by 0.15%, with one memory reversal. All 366
paired PNG/UIA frames match. This limits any generalization of the single-worker
saving; allocator tuning remains workload-specific and R09 remains P3 upstream.

A [periodic native-trimming study](BOOT-PERIODIC-NATIVE-TRIM.md) uses an existing
HotSpot/glibc option to recover most of the single-worker memory cost of sampled
metrics. This is a local JVM-policy opportunity, not a missing Robolectric API or
a confirmed leak. A 300-reload trio and three two-worker pairs now support the
local opportunity: the longer 300-reload concurrent comparison lowers observed
peak PSS 9.8% with essentially unchanged CPU, but 145.6% more minor page faults.
A direct 5 s versus 15 s comparison reduces minor faults 34.2% at 15 s, with
2.1% higher aggregate peak PSS and essentially unchanged CPU. Some late RSS
windows still rise. Lifetime stability and ordinary-test benefit remain unproven;
R09 stays P3.

A [1,000-reload diagnostic](BOOT-TRIM-RETENTION-1000.md) retains two live application
loaders at all eleven forced-GC checkpoints. Used heap stays between 82.84 and
100.61 MiB after reload 100, code-cache growth slows, and later PSS fluctuations
are mainly in unmatched anonymous mappings. This strengthens local collectibility
evidence, not a universal lifetime bound, recycling policy or ordinary-test leak.
R09 remains P3.

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
A [fresh dense-screen profile](BOOT-DENSE-COMPILER-PROFILE.md) attributes 39.1%
of post-readiness CPU samples to compiler threads. A two-thread compiler limit
reduces process CPU 13.2% and median PSS 154 MiB in three short-session pairs,
while steady-frame cost is essentially unchanged. VM Thread native stacks remain
unresolved and are not labelled entirely GC. A subsequent 300-reload pair
retains 5.4% lower total CPU and 208 MiB lower end PSS, with no slower 50-render
window; all 301 PNG/UIA pairs and 300 loader identities per worker are verified.
A three-pair concurrent test with two workers sharing four CPUs subsequently
reduces CPU 6.9% and observed peak combined PSS 77 MiB, with 366 matching
PNG/UIA pairs. A two-CPU JVM already chooses two compiler threads in preflight.
These results remain scoped to the measured JDK-17 worker configuration.
This supports separating JVM and
application costs; it does not establish a Robolectric defect or ordinary-suite
benefit, and R10 remains P2.

A [wall-profile investigation](BOOT-FONT-CACHE-BENCHMARK-CORRECTION.md) attributes
many long waits to repeated font-download socket reads in our low-level benchmark,
which omitted the font cache already supplied by normal daemon launch plans.
With a warmed cache and offline mode, both 300-reload variants have no multi-second
frames and preserve embedded-font SVG output. This is benchmark setup, not a
Robolectric wait bug; it reinforces R10's ownership/phase-attribution requirement
without raising its P2 priority or claiming ordinary-test impact.

The daemon's own forced per-render GC and its `nativeHeapMb` approximation are our
telemetry decisions, not automatically Robolectric defects.

## R11 — Two input clocks in a held Compose session

**Owner:** unestablished — Compose ui-test (event stamping), Robolectric (looper clock),
Roborazzi (capture). **Status:** fixed in the daemon (2026-10-12); integration friction,
not a confirmed bug in any of the three. **Evidence/version:** Robolectric 4.17, Compose
`ui-test-android` 1.12.1, Roborazzi 1.76.0, `remote-player-view` 1.0.0-alpha19;
`AndroidInteractiveCarriedRealPlayerTest.everyAndroidPlayerTogglesTheCarriedToggleButton`.

Compose's test input (`performTouchInput` / `performMouseInput`) stamps each
`MotionEvent`'s `downTime`/`eventTime` from the rule's `mainClock`. A platform `View`
hosted in the composition reads those stamps against `SystemClock.uptimeMillis()`, which
under Robolectric's paused looper is the looper clock. In a held session the two do not
agree: `mainClock` starts at zero with each session's compose rule, while the looper clock
belongs to the sandbox and keeps the time of every earlier render and session (17 s apart
by the third session of one test; hours on a production daemon). Within a session the
looper clock also gains on its own: about 30 ms per `captureRoboImage`, and about 450 ms
over an injected tap plus its settle, although the daemon advances both clocks together.

The casualty was the `androidx-view` Remote Compose player. `RemoteComposeView.onDraw`
flags a long press when `SystemClock.uptimeMillis() - downTime >= getLongPressTimeout()`,
so the first frame drawn with the pointer down already read as a 400 ms hold and the
release clicked nothing. Compose players compare only the events' own stamps and were
unaffected; a `click` (press and release with no capture between) also escaped, which is
why the bug showed only for a `pointerDown` … frame … `pointerUp` tap.

**Workaround (ours):** `RobolectricHost.alignHeldInputClock` moves `mainClock` forward to
the looper clock before `setContent` and before every pointer/key dispatch. Residual: lag
still accrues across frames rendered while a pointer is held with no further input
(~30 ms per frame). **Possible upstream asks**, none validated: stamp injected events from
`SystemClock.uptimeMillis()` under Robolectric, or document that `mainClock` and the
looper clock are independent; establish which layer advances the looper clock during a
capture. **Validation needed:** a single-test reproducer with an `AndroidView` whose
`onDraw` reads `SystemClock`, outside the daemon.

Carried-document replay had a separate clock mismatch in our connector:
`RemoteComposeIrReplay` used the document source's default `SYSTEM` clock while the
live capture path selected `ROBOLECTRIC_UPTIME`. Both now select the same host clock;
production hosts retain `SYSTEM`. The carried-player regression renders with a fixed
32 ms held-clock advance and preserves the toggle's persistent-state checks for all
three players. This is our integration fix, not evidence of an upstream clock defect.

## R12 — Resolved fonts are not proof of emitted glyphs

**Owner:** our Figma SVG exporter. **Status:** regression fix prepared (2026-10-13),
not an upstream Robolectric defect. Font resolution records faces from whitespace and
eagerly composed offscreen content as well as visible text. Comparing that global set
with exported text families incorrectly reports an unused face as lost.

The exporter now checks whether every emitted nonblank text run has an identified
family before applying the global ambiguity guard. Family-less gaps, unresolved spans
and unknown font handles retain the guard. A regression using embedded Roboto Flex
and an unused resolved Space Grotesk reproduces the warning; before/after PNGs are
identical because the visible text was already correctly named. All 266 connector tests
and the module ABI check pass. MeshCore's five remaining publication warnings motivated
this investigation; their full application export still needs validation with the fix.

## R13 — API 37 sandboxes need espresso-core 3.7.0+

- **Category:** ecosystem compatibility at a new SDK level. **Owner:** AndroidX Test (Espresso),
  not Robolectric. Robolectric 4.17 boots API 37 correctly; the failure is in a library on the
  test classpath.
- **Evidence/version:** Robolectric 4.17, `android-all-instrumented` `17-robolectric-15733970-i7`,
  JDK 21, 2026-10-15. Rendering compose-ai-tools' `samples/android` at `sdk=37` with
  `ui-test-junit4` 1.9.x, which pulls in espresso-core 3.5.x, failed every preview with
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance []`. Espresso 3.5's
  `InputManagerEventInjectionStrategy` reflects that hidden static, which API 37 removed. Espresso
  3.7.0 calls `Context.getSystemService(InputManager)` on SDK 23+ instead. With 3.7.0 forced, 169
  of 174 previews rendered; the other 5 hit a sandbox-local `libfontmanager` GLIBC error unrelated
  to the SDK level.
- **Impact:** without the floor, every render at API 37 fails, so `AndroidSdk.MAX_SDK = 37` only
  works when the consumer resolves espresso-core 3.7.0 or newer.
- **Current workaround:** compose-ai-tools' Gradle plugin injects `espresso-core:3.7.0` into the
  unit-test bucket as a floor (yschimke/compose-ai-tools#5989). A consumer's newer pin still wins.
  The `compose-preview-android-daemon` sidecar, which `bundle render` runs (Bazel included), takes
  the same floor from `:daemon:android`'s test runtime classpath; before that it shipped 3.5.1, and
  compose-preview-bazel's SDK 37 job failed every render with this same stack trace.
- **Proposed upstream change:** none needed; Espresso already fixed it in 3.7.0. Worth a note in
  Robolectric's API 37 release notes, because the stack trace points at the sandbox rather than at
  Espresso.
- **Validation status:** confirmed by the end-to-end render above. No upstream issue filed.
- **Follow-up, 2026-10-15:** the daemon sandbox still booted API 35 for an API 37 consumer.
  `SandboxRunner` carried `@Config(sdk = [35])`, so jetchat's semantics pass hit
  `NoSuchFieldError: SDK_INT_FULL` while its PNGs rendered on 37. Removing the annotation's `sdk`
  and setting one from `SandboxHoldingRunner.buildGlobalConfig` does **not** work in 4.17:
  `ConfigConfigurer` takes its defaults from a service-loaded `GlobalConfigProvider`, and
  `DefaultSdkPicker` falls back to the manifest's target SDK. The daemon therefore pins one sandbox
  class per level (`SandboxRunnerSdk36`, `SandboxRunnerSdk37`) and picks one from
  `composeai.daemon.sandboxSdk`. A supported runtime hook for the SDK would remove the subclasses.

## R14 — A fixed wall clock for the app's own code needs a shadow of a shadow

- **Category:** missing API / embedding friction. **Owner:** Robolectric (interceptor design);
  our workaround is local.
- **Evidence/version:** Robolectric 4.17 sources, 2026-10-15 (compose-ai-tools#5945: two renders of
  one commit differed only in a consumer time label, `8:26` vs `2:18`).
  `AndroidInterceptors.SystemTimeInterceptor` intercepts `System.currentTimeMillis()` /
  `System.nanoTime()`, but only in *instrumented* classes, and binds them through
  `InvokeDynamicSupport.bootstrapIntrinsic` to `ShadowSystem.currentTimeMillis()`, which in
  `PAUSED` mode returns `SystemClock.uptimeMillis()` — 100 ms past the epoch. So instrumenting an
  app's packages makes its clock deterministic but reads 1 January 1970.
  `bootstrapIntrinsic` receives the caller's `Lookup` and drops it, and
  `Interceptor.getMethodHandle(methodName, type)` has no caller parameter, so an interceptor cannot
  be scoped to the app's classes. `RobolectricTestRunner.findInterceptors()` can be overridden, but
  only by a runner subclass — `ParameterizedRobolectricTestRunner` is `final` with a private inner
  runner, so a `robolectric.properties`-configured lane cannot reach it.
- **Also observed:** `AndroidConfigurer.withConfig` calls `addInstrumentedClass` for the
  `@Implements` target of every shadow in `Config.shadows` (annotation or `robolectric.properties`),
  so a properties-registered shadow instruments its exact target with no `instrumentedPackages`
  entry. `RobolectricTestRunner.getExtraShadows` does **not** go through `withConfig`, so a runner
  that adds shadows there must instrument the target itself. By this reading the
  `instrumentedPackages=androidx.wear.compose.materialcore.ResourcesKt` line that accompanies
  `ShadowWearTimeSource` is redundant on the properties path (still needed in the daemon); not
  verified by removing it.
- **Impact:** a screenshot of any component that formats the current time is non-reproducible
  unless the app injects a clock. Moving `SystemClock` instead is rejected for the reasons in
  `PreviewClock`'s KDoc (forward-only, overdue delayed messages, 54 years of uptime, ordering).
- **Current workaround:** `ShadowConsumerClock` shadows `org.robolectric.shadows.ShadowSystem`
  (instrumented as an exact class), walks a few stack frames to the class that made the original
  `System.*` call, and returns `PreviewClock`'s pinned instant (and the host `nanoTime`) only when
  that class matches `composeai.render.clockPackages`; every other caller gets `ShadowSystem`'s own
  value through a `@Direct` reflector. The consumer packages are added to `instrumentedPackages`.
  The daemon's user-class child loader defines the module's own classes outside the sandbox
  loader, so there an ASM rewrite (`ConsumerClockRewriter`) retargets the call instead.
- **Proposed upstream change:** either a configurable wall-clock base for `ShadowSystem`
  (`currentTimeMillis = base + uptime`, set per sandbox, without moving `SystemClock`), or pass the
  caller class to `Interceptor.getMethodHandle` so an interceptor can bind per call site at
  bootstrap rather than walking the stack per call.
- **Validation status:** `ConsumerClockRenderTest` (properties-shaped `@Config`, Wear M2/M3
  `TimeText` plus a consumer label, byte-identical PNGs a second apart, a `fixedTime=off` control
  that differs) and `ConsumerClockDaemonRenderTest` (real `RobolectricHost` render). No upstream
  issue filed.

## R15 — No supported way to choose the sandbox's classpath

- **Category:** embedding API gap. **Owner:** our daemon for the workaround; the missing hook is
  Robolectric's. Not a bug.
- **Evidence/version:** Robolectric 4.17 bytecode, 2026-10-15. `AndroidSandbox$SdkSandboxClassLoader`
  calls `SandboxClassLoader(InstrumentationConfiguration, ResourceProvider, ClassInstrumentor)`,
  which takes the **thread context loader** as parent; the sandbox's URLs are that parent's
  `getURLs()` when it is a `URLClassLoader`, else `java.class.path`. An acquired class's bytes come
  from the SDK jar's `UrlResourceProvider`, then `URLClassLoader.getResourceAsStream` — the
  parent's `getResource` first. There is no configuration for "these jars before the JVM classpath".
- **Impact:** the daemon's Bazel/bundle lanes put an app's own (possibly newer) Compose on a child
  loader, and the sandbox kept defining the daemon's bundled Compose
  (compose-ai-tools#6016; docs/daemon/CLASSLOADER.md § "Whose Compose runs").
- **Current workaround:** `RobolectricHost.runJUnit` sets the context loader to an
  `AppComposeFirstLoader` (a plain `ClassLoader`, so Robolectric still parses `java.class.path`)
  whose `getResource` answers `androidx/` from the app's jars first. Correct only because the
  sandbox is built on that thread and reads bytes through the parent; both are implementation
  details. `AppComposeSandboxTest` / `AppComposeEscapeHatchTest` pin them.
- **Proposed upstream change:** an `InstrumentationConfiguration` (or `SandboxBuilder`) option
  naming extra classpath entries searched before the parent for acquired classes.
- **Validation status:** workaround tested in-process; no upstream issue filed.

## R16 — Instrumented static preview containers acquire a constructor

- **Category/owner:** our renderer invocation bug, not an upstream Robolectric defect.
- **Evidence/version:** Robolectric 4.17 `ClassInstrumentor.addNoArgsConstructor` adds a constructor
  to Kotlin file facades when consumer clock packages are instrumented. The tools 2.66.0 default
  enables that instrumentation; wear-m3-catalog#850 then failed 806 parameterized variant renders
  with primitive null-unboxing or argument-type errors at `PreviewRenderStrategy.kt:180`.
- **Cause/fix:** receiver resolution can now instantiate a static method's container. The
  defaults-mask helper rejected any non-null receiver and fell through to Compose reflection,
  which passes unseeded primitive nulls verbatim. Test the method's static modifier instead; Java
  reflection ignores its receiver argument for a static invocation.
- **Validation status:** added an instrumented consumer partial-seed regression in
  `ConsumerClockRenderTest`; formatter checked locally, sandbox execution pending CI. No upstream
  issue needed and clock pinning remains enabled.

## R17 — Shadowing a composable function needs its package instrumented and its lowered signature

- **Category/owner:** embedding pattern, not a bug. Our daemon owns the workaround; whether
  shadowing a `@Composable` function is a supported pattern is Robolectric's call, and the
  signature contract is the Compose compiler's.
- **Evidence/version:** Robolectric 4.17, Remote Compose `1.0.0-alpha18` (`remote-tooling-preview`),
  2026-10-19 (compose-ai-tools#6116, compose-preview-daemon#327). `RemoteContentPreviewKt` lives
  under `androidx.`, which Robolectric does not instrument by default, so a `@Implements` shadow
  of it is inert until `InstrumentationConfiguration.Builder.addInstrumentedClass` names the class.
  The shadow's `@Implementation` must match the compiler-lowered JVM descriptor —
  `(Modifier, Profile, Function2, Composer, int, int)V` — including the `$changed`/`$default`
  ints; Compose emits no separate `$default` method, so default arguments arrive as mask bits the
  shadow must read with the same meaning.
- **Impact:** without it, a project's own `@PreviewWrapper` that calls upstream
  `RemoteContentPreview(profile = …)` rendered through the stock function: no `.rc` bytes reached
  the IR sidecar, and the daemon's player selection, knobs and clock were skipped.
- **Current workaround:** `ShadowRemoteContentPreview` in `:data-remotecompose-connector`
  redeclares the composable with upstream's defaults so the compiler lowers it to the same
  descriptor, and forwards to `RemoteOverridablePreview`. `SandboxHoldingRunner` instruments
  exactly that class and registers the shadow only when `remote-tooling-preview` and
  `remote-creation` are on the classpath (`isRemoteContentPreviewAvailable`).
- **Upgrade risk:** a Remote Compose release that changes `RemoteContentPreview`'s parameters, or a
  Compose compiler that changes the lowering, silently unbinds the shadow.
  `RemoteContentPreviewShadowTest` compares the shadow's descriptor with upstream's, so the bump
  fails a test instead.
- **Validation status:** `RemoteContentPreviewShadowTest` (descriptor match, end-to-end capture,
  and a negative control that fails without the shadow) and
  `SandboxHoldingRunnerRemoteContentPreviewShadowTest` (registration gated on the classpath
  probe). No upstream issue filed.

## R18 — Constructor instrumentation writes duplicate local-variable rows

- **Category/owner:** Robolectric bug, confirmed with a minimal reproducer. Our daemon owns the
  workaround.
- **Evidence/version:** Robolectric 4.17, JDK 17 and JDK 26, 2026-10-21 (compose-ai-tools#6161,
  compose-preview-daemon#350). `ClassInstrumentor.instrumentConstructor` →
  `extractCallToSuperConstructor` moves every instruction up to the `this(...)` / `super(...)` call
  into the new `<init>`, but leaves the original `MethodNode.localVariables` on the renamed
  `__constructor__`. Their labels are no longer in that method, so ASM's `ClassWriter` writes each
  row as `start_pc = 0, length = 0`. A Kotlin default-arguments constructor that inlines the same
  getter twice before delegating — Wear Compose Material's `Shapes(…)` inlines `.dp` for `4.dp`
  and `26.dp`, each with a `$i$f$getDp` row in slot 7 — then carries two identical rows, and
  `SandboxClassLoader.maybeInstrumentClass` hands them to `defineClass`:
  `ClassFormatError: Duplicated LocalVariableTable attribute entry for '$i$f$getDp'`. The jar's own
  bytes are valid (the rows start at 32 and 59), so no pre-instrumentation clean-up sees them.
- **Impact:** the class cannot be defined while verification is on; `-Xverify:none` is the only
  stock escape and disables verification for everything.
- **Current workaround:** `LocalVariableDedupingInstrumentor` in `:renderer-android`, bound in
  place of `ClassInstrumentor` through one process-wide injector (`LocalVariableDedupingInjector`),
  runs `LocalVariableTables.dedupe` (`:daemon-connector-api`) on the instrumented bytes. One
  injector, because it owns the `SandboxManager` whose cache lets every runner share a sandbox. The
  daemon's `SandboxHoldingRunner` uses it, and since compose-ai-tools#6230 so does the Gradle batch
  render: `RobolectricRenderTest` and `AppTourRobolectricRenderTest` run on
  `ee.schimke.composeai.renderer.DedupingParameterizedRobolectricTestRunner`, a copy of the `final`
  `ParameterizedRobolectricTestRunner`'s parameter handling whose per-parameter runners take that
  injector. The stock parameterized runner offers no injector seam, which is why the batch path
  kept failing after the daemon was fixed. Generated shard classes must name the new runner too.
- **Proposed upstream change:** drop (or re-target to the new `<init>`) the local-variable rows
  whose labels `extractCallToSuperConstructor` removes from `__constructor__`, so instrumentation
  never writes rows for code the method no longer contains.
- **Upgrade risk:** a Robolectric release that fixes this makes the workaround a no-op; one that
  renames or stops injecting `ClassInstrumentor` unbinds it, and the regression test below fails.
- **Validation status:** `LocalVariableDedupingInstrumentorTest` instruments a `Shapes`-shaped ASM
  fixture: valid as written, rejected by `defineClass` after the stock instrumentor, defined after
  ours. `DedupingParameterizedRobolectricTestRunnerTest` runs the #6230 reproducer (an
  enum whose default-arguments constructor inlines `.dp` twice) instrumented under the batch
  runner. No upstream issue filed yet.

## R19 — A started system intent resolves to nothing in the sandbox

- **Category/owner:** expected simulator behaviour, not a confirmed Robolectric bug. Our renderer
  owns the workaround.
- **Evidence/version:** Robolectric 4.17, SDK 35, 2026-10-23 (homeassistant-remotecompose#635,
  compose-preview-daemon#370). `Activity.requestPermissions` records a started
  `android.content.pm.action.REQUEST_PERMISSIONS` intent aimed at
  `org.robolectric.permissioncontroller`, which `ShadowInstrumentation.getNextStartedActivity()`
  returns. No activity in the sandbox's package manager resolves it, so `resolveActivity` returns
  null.
- **Impact:** `AppTourRenderer.followStartedActivities` followed every started intent and threw
  `No activity resolves Intent { act=android.content.pm.action.REQUEST_PERMISSIONS … }`, so a
  launcher activity that asks for a permission on first composition never rendered its hero.
- **Current workaround:** `isUnresolvableSystemIntent` passes over an intent that resolves to
  nothing and is not the app's own (neither an explicit component in the app's package nor one
  scoped to it with `setPackage`). The tour stays on the app's screen, as it would for a user who
  dismisses the dialog; an unresolvable intent of the app's own still fails the tour.
- **Upgrade risk:** a Robolectric release that registers a permission-controller activity would
  make the intent resolve, so the predicate stops passing over it and the tour tries to follow it.
  The test below would then fail its first assertion; it does not show whether the tour still
  renders, since nothing exercises `followStartedActivities` end to end.
- **Validation status:** `AppTourSystemIntentTest` (SDK 35) covers the predicate only: it records a
  real `requestPermissions` intent and checks that `isUnresolvableSystemIntent` classifies it as a
  system screen to pass over, and that it does not for an explicit component of the app's own or a
  package-scoped intent. The tour flow itself is untested here. No upstream issue filed; none
  planned.

## How to turn an item into upstream feedback

Attach a minimal project or a source-level patch, exact released/source versions,
reproduction command, before/after output checks, and a benchmark with cold/warm
conditions and CPU/memory scope stated. Link the issue/PR beside its ID and record
maintainer feedback, revisions and any rejected approach here. Recheck the current
upstream API before filing; this document intentionally pins observations rather
than assuming the next release still has the same limitations.
