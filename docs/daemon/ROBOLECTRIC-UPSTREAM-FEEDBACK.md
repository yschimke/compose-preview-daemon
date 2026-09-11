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
| 8 | **P3 · R03 — runner-neutral embedding API** | Medium maintenance/dependency friction; measured bypass saving is only 48 ms ready. | Mainly standalone tools and non-JUnit integrations; ordinary JUnit users already benefit from the runner. | High confidence in coupling; dependency removal not demonstrated. | Coordinate with R04/R05 session design and audit dependencies before requesting API extraction. |
| 9 | **P3 · R07 — Activity-free window host** | Potential setup saving and simpler offscreen hosting; broad compatibility remains unproven. | Conditional benefit for isolated View/composable screenshot tools; Activity-dependent cases still need a fallback. | Medium/low readiness: experimental host, integration bugs found, shared ecosystem ownership. | Validate focus/IME/popups/themes and establish Robolectric vs Compose/Roborazzi ownership. |
| 10 | **P3 · R09 — reload lifetime investigation** | Our growing leak is fixed. Remaining initial AWT loader root is bounded, so no established ongoing growth benefit. | Unknown for normal unit/screenshot tests; custom application reloads distinguish our case. | No confirmed Robolectric bug; ownership unresolved. | Minimize AWT/TCCL retention first; escalate priority only if growth or substantial retained memory is established. |

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

## How to turn an item into upstream feedback

Attach a minimal project or a source-level patch, exact released/source versions,
reproduction command, before/after output checks, and a benchmark with cold/warm
conditions and CPU/memory scope stated. Link the issue/PR beside its ID and record
maintainer feedback, revisions and any rejected approach here. Recheck the current
upstream API before filing; this document intentionally pins observations rather
than assuming the next release still has the same limitations.
