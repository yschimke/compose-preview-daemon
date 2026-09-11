# Bulk ARGB settling snapshots

**Benchmark correction:** the original runs below omitted the font cache supplied
by normal daemon launch plans, causing repeated network font downloads. See
[the wall-profile investigation and controlled follow-up](BOOT-FONT-CACHE-BENCHMARK-CORRECTION.md).
Use `--uncached-fonts` to reproduce these historical runs with the updated harness.

The post-snapshot CPU profile still attributes 4.1% of post-readiness samples to
pixel reads. Settling needs an independent ARGB array for each sampled frame,
but canonical non-premultiplied `TYPE_INT_ARGB` images already store these values.

`snapshotArgb` uses the rectangular raster `getDataElements` API only for an exact
`BufferedImage` instance with `TYPE_INT_ARGB`, the identity-equal default RGB color
model, and no alpha premultiplication. This preserves subimage offsets and row
stride without exposing the backing buffer. All other images retain `getRGB`,
including subclasses, converted formats, and premultiplied images. Allocation
count, settling sample budget, comparison semantics and final PNG validation are
unchanged. This is our image-processing optimization, not a Robolectric defect.

The [JDK BufferedImage contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/image/BufferedImage.html)
specifies default ARGB/sRGB conversion for `getRGB`. The fast path requires that
representation already; it must not copy arbitrary raster storage as ARGB.

## Validation

Five snapshot tests cover transparent hidden RGB, partial alpha, subimage offset
and stride, independent ownership, all thirteen standard image types,
premultiplication after construction, and overridden `getRGB`. Six existing
settling tests and six real-painter/in-memory-capture tests also pass.

## Benchmark protocol

Three alternating fresh-worker pairs render a red fixture followed by 60
`DenseDashboardPreview` frames at 480×1200, density 2, on JDK 17. Both variants use
Serial GC, Xmx256m, Xms32m, free ratios 10/30, equal GC logging, and
`CICompilerCount=2`. This controls the compiler-policy improvement separately.
Only the renderer jar differs between the frozen classpaths; the candidate's
rebuilt daemon version resource is replaced by the baseline entry.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/bulk-argb.json \
  --classpath daemon/android/build/reuse-final-snapshot-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/bulk-argb-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --memory --uncached-fonts
```

## Results

OpenJDK 17.0.19+10, Ryzen 3900X host. The [machine-readable report](profiles/bulk-argb.json)
retains every run and the artifact comparisons.

| Metric | Baseline | Bulk ARGB | Change |
| --- | ---: | ---: | ---: |
| Mean whole-process CPU | 56.690 s | 54.440 s | −4.0% |
| Median steady-frame CPU | 583.0 ms | 559.3 ms | −4.1% |
| Median steady-frame wall | 525.5 ms | 500.5 ms | −4.8% |
| Median total wall | 45.249 s | 45.144 s | −0.2% |
| Median readiness CPU | 9.500 s | 9.510 s | essentially unchanged |
| Median end PSS | 570.52 MiB | 588.09 MiB | +17.57 MiB |

Whole-process CPU is lower in all three pairs (56.64→54.16, 56.55→54.57,
56.88→54.59 seconds). Total wall has substantial run-to-run variation, so this is
not a demonstrated startup or consistent whole-session latency improvement.
Median end PSS is higher; no allocation-count, live-heap or memory reduction is
claimed. The local snapshot has no cache or additional cross-frame ownership.

All **183 paired frames and 2,013 data artifacts match**. Artifact comparisons
normalize the existing documented semantics debug ordering and Typeface identity
fields; PNG bytes and UIA hashes match. This matrix uses repeated dense frames,
not application-loader replacement, and does not establish another JDK/collector's
performance or a worker recycling policy.

The optimization removes conversion work in our settling loop. It preserves
image semantics and benefits this measured workload, with the measured PSS tradeoff
kept visible rather than treating a CPU reduction as a memory reduction.

Validation command: `./gradlew ktfmtFormatAll :renderer-android:testDebugUnitTest
--tests '*ArgbSnapshotTest' --tests '*InMemoryStillCaptureTest'
--tests '*CaptureVisuallySettledFrameTest' :daemon:android:writeDaemonClasspath`.
All 17 tests pass. This module does not declare `checkKotlinAbi`; attempting that
module task confirms it is unavailable. Existing public function declarations
are unchanged; the new helper is Kotlin-internal.

## 300 actual application reloads

A subsequent [baseline-then-bulk pair](profiles/bulk-argb-long-reloads.json) keeps
the same JDK, frozen jars and launch flags, replacing the static fixture with
`benchmark.screens.ReloadDashboardPreviewsKt.ReloadDashboardPreview` from
`daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar`. Each variant
uses `--renders 300 --swap-every 1 --reuse-output --memory`, with the same dimensions.
The saved summaries verify **300 distinct application loader identities per
variant**, rather than merely repeating one parent-loaded fixture.

| Metric | Baseline | Bulk ARGB |
| --- | ---: | ---: |
| Whole-process CPU | 182.820 s | 177.700 s (−2.8%) |
| Whole-session wall | 192.138 s | 225.973 s (+17.6%) |
| End PSS | 629.83 MiB | 641.14 MiB (+11.30 MiB) |
| Final worker-reported heap after GC | 81 MiB | 81 MiB |
| Frames taking over 2 seconds | 5 | 20 |

The CPU benefit persists in every 50-reload block:

| Reloads | Mean CPU baseline / bulk (ms) | Median wall baseline / bulk (ms) |
| --- | ---: | ---: |
| 1–50 | 846.6 / 827.8 | 564.5 / 546.5 |
| 51–100 | 565.8 / 551.4 | 534.0 / 505.0 |
| 101–150 | 517.4 / 505.0 | 525.5 / 503.0 |
| 151–200 | 506.6 / 483.2 | 527.0 / 504.0 |
| 201–250 | 520.8 / 499.2 | 529.0 / 494.5 |
| 251–300 | 492.0 / 471.4 | 529.0 / 490.5 |

**The uncached total-wall regression was material and initially unexplained.** The worst candidate
frame takes 5.987 s wall, 0.750 s process CPU, and reports 5.862 s within the worker.
The largest logged GC pause is 151 ms (baseline 158 ms). Render-time sums account
for almost all total wall after readiness, so the stalls are not simply gaps in
the Python driver. This is consistent with the previously observed long waits,
but does not establish their cause or rule out an optimization-induced regression.
Do not infer a tail-latency win from the lower frame medians. Next capture wall
stacks during a reproducible stall before attributing it to scheduling, rendering,
or the JVM.

Worker heap medians remain around 97–99 MiB before dropping into the high 80s;
both variants finish at 81 MiB. Sampled RSS still grows more slowly late in the
run: baseline 30-second medians rise from 654.6 MiB at 90–120 s to 669.1 MiB in
the final partial interval; candidate rises from 662.0 to 682.7 MiB. These are
elapsed-time intervals, not matched reload blocks, and RSS is not PSS. This is
not evidence of a complete memory plateau or a leak-free lifetime. No extra
histograms/root census or diagnostic GCs were added; ordinary worker GC remains.

All **301 PNG/UIA pairs match**. Because output names are reused, other artifacts
are not retained for every reload, so the full historical artifact-parity claim
from the short matrix does not apply here. One ordered pair is insufficient to
establish a universal CPU saving, native-memory ownership, or a reaping interval.
The uncached longer run supports the CPU saving and confirms that this is not a memory
improvement. The subsequent [font-cache investigation](BOOT-FONT-CACHE-BENCHMARK-CORRECTION.md)
identifies repeated network downloads in the benchmark: with the normal cache
behavior restored, 300 reloads have no multi-second stalls, 3.9% lower CPU and
4.8% lower total wall with bulk ARGB. End PSS is still 4.86 MiB higher.
