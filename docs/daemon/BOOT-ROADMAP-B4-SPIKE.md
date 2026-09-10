# B4: capture without an Activity

The test-only [`ActivityFreeCaptureSpikeTest`](../../daemon/android/src/test/kotlin/ee/schimke/composeai/daemon/ActivityFreeCaptureSpikeTest.kt)
implements a standalone `PhoneWindow` host. It keeps Robolectric's Android application and native
renderer, Compose's test clock, and Roborazzi's PixelCopy/bitmap writer, but creates no Activity.
The published render engine is unchanged.

## What had to change

A `ComposeView` with lifecycle, saved-state and view-model-store owners composes and exposes
semantics without an Activity. Capture has two additional constraints in Roborazzi 1.74.0:

- `SemanticsNodeInteraction.captureRoboImage` resolves an Activity window for ordinary roots and
  throws when its context is an application context.
- `View.captureRoboImage` goes through Espresso and fails without a resumed Activity. Direct
  `View.fetchImage` works, but a bare `FrameLayout` has no window and falls back to `View.draw`.

The spike constructs an SDK-35 `PhoneWindow`, attaches its decor through `WindowManager`, and
uses `View.fetchImage` followed by the bitmap overload of `captureRoboImage`. The decor lets the
fetcher find the window and retain PixelCopy. A dialog root supplies its own window; the spike
resizes that root's bitmap to the fixture's declared 320×320 frame. That small fixture-specific
operation does **not** implement the engine's general dialog, gutter or wrap-content rules.

The first working version had a two-second delay on **every** capture. Phase timing placed it in
settling (2074 ms for the warm Material fixture), not bitmap capture (11 ms). Inspection of Compose's
`ComposeRootRegistry_androidKt.waitForComposeRoots` showed a two-second latch wait when there are
no registered roots. The window attachment was still queued on Robolectric's paused main looper.
Draining that queue before advancing the test clock and waiting for idle removes the wait, while
retaining pixel and hierarchy parity. This is an ordering fix in the experimental host, not a
shorter timeout or removal of synchronization.

## Comparison method

Three modes run in separate fresh JVMs using `SandboxProcessPool.spareWorkerCommandForTest()`:

1. **engine:** the existing `RenderEngine.render` path, as an artifact reference.
2. **activity:** `createAndroidComposeRule<ComponentActivity>` with a directly installed ComposeView.
3. **window:** `createEmptyComposeRule`, a standalone window, and explicit tree owners.

The two minimal hosts share fixture invocation, background, clock advancement, attachment drain,
bitmap capture and hierarchy extraction. Compare **activity versus window** to assess host cost.
The full engine also performs reflection, extension/data-product work and adaptive settling that
both minimal hosts omit; its timing difference is not an estimate of the Activity saving.

Each process renders RedSquare, MaterialButtonInteractionState, SerifTextPreview and
DialogWindowSurface in that order, then repeats the sequence twice. Each render creates and tears
down its host. Three trials rotate process order (engine/activity/window, activity/window/engine,
window/engine/activity). This yields 108 PNGs, 108 default hierarchy exports and 72 full hierarchy
exports. The test compares PNG bytes and default hierarchies against the engine and compares
full hierarchies, including non-actionable text, between the two minimal hosts. These are the
extractor's serialized fields, not every Compose semantics property or every daemon data product.

Wall phases use `System.nanoTime`; JVM uptime marks first-frame completion. Process CPU comes from
`OperatingSystemMXBean.processCpuTime`, including compiler/GC threads. Loaded-class totals come from
`ClassLoadingMXBean` and cover the whole JVM, not just the sandbox. CPU totals are not wall-time
breakdowns. This is a test-fixture process, not a production worker readiness benchmark.

## Measured result

Linux Ryzen 9 3900X, OpenJDK 17.0.19+10, SDK 35, Robolectric 4.17-beta-4, 2026-09-10.
Dependencies and OS file caches were warm. The spare-test launch configuration uses `-Xmx512m`;
no compiler flags were changed. [Raw timing, CPU and class-count records](profiles/activity-free-spike.json)
include all three trials, including the slower Activity trial. Three trials are directional evidence,
not a confidence interval or a production latency guarantee.

| Measurement | Full engine reference | Matched Activity | Standalone window |
| --- | ---: | ---: | ---: |
| Median first red-square render | 1756 ms | 1551 ms | 1336 ms |
| Median JVM uptime at first-frame completion | 3942 ms | 3705 ms | 3518 ms |
| Median cumulative CPU at first-frame completion | 15230 ms | 16540 ms | 14290 ms |
| Median total classes loaded at first-frame completion | 16068 | 15560 | 15149 |
| Median warm render, cycles 1–2 (24 frames/mode) | 132.5 ms | 43.5 ms | 34.5 ms |

The matched host comparison saves **215 ms (14%)** in median first-render time and **9 ms (21%)**
on these warm frames. First-frame JVM uptime improves by 187 ms (5%); initial sandbox setup still
costs about 2.2 seconds. The first-render ranges are 1494–1798 ms for Activity and 1326–1459 ms for
window. Cumulative process CPU through the first frame is about 14% lower, with 411 fewer classes
loaded. None of these is the old roadmap's implied removal of the entire Activity-launch cost.

First-render phase medians explain why (phase medians need not sum to the median total):

| Phase | Matched Activity | Standalone window |
| --- | ---: | ---: |
| Qualifiers/registration and rule creation | 83 ms | 73 ms |
| Rule entry, including Activity launch where applicable | 852 ms | 115 ms |
| Host/view attachment | 265 ms | 527 ms |
| Drain pending attachment and composition | 27 ms | 310 ms |
| Clock advance and idle | 3 ms | 2 ms |
| Root selection | 6 ms | 5 ms |
| Bitmap capture and encoding | 249 ms | 249 ms |
| Hierarchy extraction and writing | 24 ms | 22 ms |
| Explicit host cleanup | 3 ms | 22 ms |
| Rule teardown | 42 ms | 9 ms |

Much of the apparent rule-entry saving moves into window attachment and first composition.
Capture/encoding is unchanged. The minimal hosts' gap from the full engine also includes omitted
extension work and settling; it must not be advertised as a production speedup from B4 alone.
All **108 PNGs** matched by bytes, all **108 default hierarchy artifacts** agreed across modes, and
all **72 full hierarchy artifacts** agreed between the matched hosts.

## Reproduce

```sh
COMPOSEAI_ACTIVITY_FREE_SPIKE=true COMPOSEAI_ACTIVITY_FREE_TRIALS=3 \
  ./gradlew :daemon:android:testDebugUnitTest --rerun \
  --tests '*ActivityFreeCaptureSpikeTest'
```

The default is one trial when opted in; otherwise the test is skipped. Logs and PNG/JSON artifacts
are under `daemon/android/build/activity-free-spike/trial-{0,1,2}/`. `[phase]` lines cover rule
creation/entry, host attachment, pending attachment/composition drain, clock settling, root
selection, capture/encoding, extraction and cleanup. `[measure]` lines include total render time,
uptime, cumulative CPU and class count. Gradle's `--rerun` matters when changing only the trial
count because it is an environment variable, not a declared task input.

## Remaining production work

Keep this experimental. A production host abstraction must preserve consumer themes, Activity-
dependent composables and extensions, interaction sessions, IME/focus behavior, AndroidView,
multiple popups/windows, dialog placement, round devices, density, gutters and wrap-content.
Pixel parity for four static fixtures is evidence that the basic path works, not a compatibility
claim for those cases. Test a broader catalog and then integrate an opt-in host with an Activity
fallback before considering a default switch. Do not combine this experiment with compiler tuning;
that would obscure the host comparison.
