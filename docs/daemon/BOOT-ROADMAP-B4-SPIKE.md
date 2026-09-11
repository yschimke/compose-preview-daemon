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

## Broader catalog and window focus

A second experiment adds nine fixtures with
`COMPOSEAI_ACTIVITY_FREE_BROAD=true`: opaque images, linear/radial gradients,
emoji/annotated text, transformed/vector graphics, an icon-button row, a lazy list,
a self-focusing editable text field and custom clipping. The original four-fixture
mode remains the default opt-in workload.

The first broad run found a real host defect. The standalone window omitted the
editable field's 2×16-pixel cursor; the engine and matched Activity showed it.
Serialized hierarchies agreed, demonstrating why hierarchy parity alone is
insufficient. `WindowManager.addView` attached the view but did not deliver the
window-focus event supplied by ActivityScenario. Lifecycle RESUMED and the field's
own focus request did not substitute for window focus.

The experimental host now sends focus through `ShadowViewRootImpl.callWindowFocusChanged`
after attachment, drains the paused looper, and verifies `decorView.hasWindowFocus()`
before settling/capture. It does not mask the cursor or relax image comparison.
The SDK-specific root lookup remains confined to the test prototype.

Three fresh, rotated trials then passed: **351 byte-identical PNGs**, **351 matching
default hierarchy artifacts**, and **234 matching full hierarchy artifacts** across
13 fixtures and three cycles. The test ran through Gradle/AGP with no skipped tests.
No production capture behavior changed.

| Measurement | Engine reference | Matched Activity | Standalone window |
| --- | ---: | ---: | ---: |
| Median first RedSquare render | 1706 ms | 1486 ms | 1347 ms |
| Median first-frame JVM uptime | 3862 ms | 3651 ms | 3541 ms |
| Median CPU through first frame | 14990 ms | 14570 ms | 14130 ms |
| Median classes through first frame | 16040 | 15533 | 15142 |
| Median warm render, cycles 1–2 | 118.5 ms | 36 ms | 29 ms |

The matched host saves 139 ms (9.4%) on first rendering and 7 ms (19.4%) on warm
frames in this broader workload. First-frame uptime improves by 110 ms. These
measurements use the original test launch settings (JDK 17, 512 MiB heap, no C1/CDS
experiment flags) and must not be subtracted from separately measured worker
readiness to claim a combined result. The smaller first-frame gain than the earlier
215 ms is a reason to keep using matched repeats, not a reason to drop the focus fix.
[Raw per-render measurements](profiles/activity-free-broad-spike.json).

```sh
COMPOSEAI_ACTIVITY_FREE_SPIKE=true COMPOSEAI_ACTIVITY_FREE_BROAD=true \
  COMPOSEAI_ACTIVITY_FREE_TRIALS=3 \
  ./gradlew :daemon:android:testDebugUnitTest --rerun \
  --tests '*ActivityFreeCaptureSpikeTest' --max-workers=4
```

This expands static capture coverage and fixes one focus defect. It does not yet
establish input dispatch, IME behavior, focus transfer between multiple windows,
consumer Activity dependencies or general dialog/gutter geometry. Those remain
requirements for a production host abstraction and its compatibility fallback.

## Input probe

`COMPOSEAI_ACTIVITY_FREE_INPUT=true` selects a separate matched-Activity/window
workload: `ClickToggleSquare` (raw pointer handler), `ClickableToggleSquare`, and
`EditableTextFieldSquare`. Each fixture latches an input-driven state change to a
known green frame. Both hosts must independently produce at least 95% expected
green pixels (8/channel tolerance), then their PNG bytes, default hierarchy and
full hierarchy must agree. A pair of equally inert red frames cannot pass.

The probe mirrors the live lane's `LocalInspectionMode=false`. Touch input uses
Compose's root injector with the production down/move/up sequence and advances
both Compose's clock and Robolectric's looper clock in 16 ms steps. Earlier
simplifications failed even on the Activity control; changing inspection mode or
merely separating down/up was not sufficient. Reproducing the complete held-session
sequence and clock handling made both hosts pass. After input, 500 ms of simulated
time lets the test capture the final state; this is a correctness probe, not a
measurement of live response latency or ripple motion.

Three rotated fresh-process trials passed all **54 frames**, their default and full
hierarchies, and the independent green-state assertions.
[Recorded per-frame checks](profiles/activity-free-input-spike.json).

```sh
COMPOSEAI_ACTIVITY_FREE_SPIKE=true COMPOSEAI_ACTIVITY_FREE_INPUT=true \
  COMPOSEAI_ACTIVITY_FREE_TRIALS=3 \
  ./gradlew :daemon:android:testDebugUnitTest --rerun \
  --tests '*ActivityFreeCaptureSpikeTest' --max-workers=4
```

Artifacts are under `daemon/android/build/activity-free-input-spike/`, separate
from static measurements. Text uses `performTextInput`, which exercises Compose's
text action rather than a native key/IME route. This probe establishes these three
state changes and subsequent capture; it does not establish drag/selection,
native keyboard/IME, accessibility actions, multiple-window focus transfer or full
production held-session compatibility. Production hosting remains unchanged.

After adding input mode, one fresh broad static trial also passed all 117 frames,
default hierarchies and matched-host full hierarchies. The input-specific settings
do not change the static path's inspection mode or synchronization.

### Android key and editing-connection checks

The input mode now also includes `EditableTextFieldNativeKey` and
`EditableTextFieldImeCommit`, both backed by the same self-focusing text fixture.
The first injects an Android A key-down/key-up through Compose's root key injector.
The second obtains the Android view's `InputConnection` with `onCreateInputConnection`
and calls `commitText("x", 1)`, requiring a non-null connection and accepted commit.
Neither uses `performTextInput`. Both must reach the fixture's exactly-one-character
green state; a double insertion produces a different color and fails.

One fresh Activity JVM and one standalone-window JVM passed all five input cases
for three cycles: **30 frames**, with independent green-state checks and exact
PNG/default/full hierarchy parity. This verifies Android key delivery and the
editing-connection route. It does not simulate an entire IME service, visible
keyboard/insets, keyboard switching, or cross-window focus transfer.
[Recorded checks](profiles/activity-free-native-input-spike.json).

### Popup capture and window stacking

The broad static mode now includes `MultipleSemanticsRoots` and
`VisualOnlySurfaceWithPopup` (15 fixtures total). The latter draws a main surface
with no semantics children, so it also checks that the popup does not become the
hierarchy's subject just because it has more semantics.

The first run failed: both minimal hosts exported the main red surface but omitted
the blue popup from the image. Production's Roborazzi semantics capture composites
multiple windows; the spike's direct view bitmap fetch did not. Using screen capture
for multiple non-dialog Compose roots fixed the Activity control, but the standalone
window still hid the popup. Its `TYPE_APPLICATION_OVERLAY` put the main surface above
the popup in window stacking order. Changing it to `TYPE_APPLICATION` fixed that
second defect. No Activity or ActivityScenario is introduced in the window mode.

One fresh trial then matched **135 PNGs and default hierarchy artifacts**, plus
**90 full hierarchy artifacts**, across all 15 fixtures and three cycles.
[Recorded checks](profiles/activity-free-popup-spike.json). The earlier 13-fixture
performance table is historical; these correctness runs do not establish an updated
speed estimate. Current broad and input commands run 15 and 5 fixtures respectively.

These fixes remain in the opt-in test host. General popup positioning, dimming,
focus transfer, non-Compose windows and arbitrary dialog geometry still require
coverage before production adoption.

After the window-type correction, the five-case input workload was rerun in fresh
Activity/window JVMs. All 30 frames, both hierarchies and independent green-state
checks passed again, including native key delivery and the editing connection.

## Host cost with C1 tuning

`COMPOSEAI_ACTIVITY_FREE_C1=true` adds `-XX:TieredStopAtLevel=1` and
`-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30` to each child JVM. The
method-handle property is an internal JDK tuning knob, not a supported application
API. This mode uses the normal Robolectric dependency and no application CDS
archive; it isolates whether the host saving survives compiler tuning.

Three rotated trials on the current 15-fixture host passed **405 PNG/default
hierarchy artifacts** and **270 full hierarchy artifacts**. The matched Activity's
median first render was **1427 ms**, versus **1292 ms** for the standalone window
(a **135 ms / 9.5%** saving). First-frame JVM uptime was 3309 versus 3199 ms;
median warm render was 28.5 versus 24 ms. Warm medians are computed within each
trial over cycles 1–2, then across the three trials.
[Raw tuned measurements](profiles/activity-free-c1-spike.json).

```sh
COMPOSEAI_ACTIVITY_FREE_SPIKE=true COMPOSEAI_ACTIVITY_FREE_BROAD=true \
  COMPOSEAI_ACTIVITY_FREE_C1=true COMPOSEAI_ACTIVITY_FREE_TRIALS=3 \
  ./gradlew :daemon:android:testDebugUnitTest --rerun \
  --tests '*ActivityFreeCaptureSpikeTest' --max-workers=4
```

This remains a fixture-process host comparison, not worker readiness. In
particular, the 135 ms cannot be subtracted from the separately measured 2.480 s
JDK 25/CDS/constant-binding readiness to claim a combined result.

Phase medians explain the modest net saving: rule entry falls from 752 to 107 ms,
but attachment rises from 261 to 478 ms and draining attachment/composition from
32 to 345 ms. Work moves into the first real window and composition; removing
Activity launch does not eliminate those costs. Bitmap capture is effectively
unchanged at 226 versus 221 ms. Phase medians are independently aggregated and
need not sum to the median total.

A fresh untuned comparison on the same code also passed 405 PNG/default artifacts
and 270 full hierarchies. Its first-render medians were 1491 ms (Activity) and
1356 ms (window), again a 135 ms saving. Warm medians were 35 and 29 ms. Thus the
Activity-free benefit survives C1 tuning in this workload; it does not disappear
once compiler overhead falls. The configuration batches ran sequentially (tuned,
then untuned), with host order rotated within each batch, so small cross-configuration
differences can include host drift. [Current untuned measurements](profiles/activity-free-current-default-spike.json).
