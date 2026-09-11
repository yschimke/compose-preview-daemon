# Bulk ARGB settling snapshots

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
  --width 480 --height 1200 --memory
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
