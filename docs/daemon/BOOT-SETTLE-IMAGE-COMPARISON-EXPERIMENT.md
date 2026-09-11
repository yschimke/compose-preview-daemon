# Compare decoded settling images without full-frame snapshots

This experiment replaces each settling sample's full-frame `IntArray` snapshot
with the decoded `BufferedImage` itself. A capture-local comparator accepts only
standard, non-premultiplied sRGB BGR/ABGR byte storage with checked dimensions,
strides, channel order, translation, bank count, offset and exact array length for
direct byte equality. Everything else uses exact `getRGB` comparison with two
arrays of at most 4096 integers each. No frame sample or clock advance is removed;
dimensions, outcomes, decode failure handling and final-image callback stay intact.

The previous-image reference and fallback arrays belong to one capture. Nothing
is cached across previews or application loaders. This is our pipeline work, not
a Robolectric bug or an upstream API request.

## Validation and default-GC evidence

All 18 focused comparator, settling, final-image and PNG reader tests pass. Differential
tests compare against full `getRGB` snapshots for all standard BufferedImage types
and subimages. Dedicated cases cover palette remapping, alpha and hidden transparent
RGB channels, equal-area dimension changes, last pixels and 4096-pixel boundaries.

Frozen baseline and candidate classpaths contain 252 ordered entries; only entry
19, the renderer jar, differs. The baseline includes #69's decoded-image handoff.
The deferred two-array variant from #70 is not enabled in either input.

[Default JDK17/G1 results](profiles/settle-image-comparison.json) retain three
rotated fresh-worker pairs, each one red plus 60 dense captures at 480×1200,
density 2, 1 GiB maximum heap and equal GC logging. All 183 frame pairs and 2,013
exported artifacts match, with only documented diagnostic-order/Typeface-identity
normalizations. No concurrent build, test, profiler or second benchmark overlapped.

| Metric | Baseline | Image comparator |
| --- | --- | --- |
| Total CPU per trial (s) | 81.30 / 81.26 / 79.76 | 76.81 / 78.01 / 107.10 |
| Mean total CPU (s) | 80.77 | 87.31 |
| Median total CPU (s) | 81.26 | 78.01 |
| Median last-30 mean CPU (ms) | 827.7 | 797.0 |
| Median last-30 wall latency (ms) | 545.5 | 507.5 |
| Concurrent mark cycles | 6 / 4 / 5 | 6 / 6 / 179 |
| Median end PSS (MiB) | 997.4 | 1015.1 |

The first two pairs improve CPU but the third candidate triggers excessive
concurrent marking. Mean CPU rises 8.1%; do not advertise the median gain as an
unconditional win. A separate matrix bounds both variants to two parallel and one
concurrent GC thread to investigate comparison cost under equal GC resources.
No production launch policy changes. Allocation profiles run separately.

## Bounded-GC and allocation results

[Three additional rotated pairs](profiles/settle-image-comparison-bounded-gc.json)
with `ParallelGCThreads=2` and `ConcGCThreads=1` reduce mean total CPU from 75.71
to 72.47 seconds (4.3%). Individual totals are 75.28/75.63/76.23 seconds versus
72.34/72.51/72.55. Median steady CPU falls 738.0 → 698.3 ms; median steady wall
latency falls 576.0 → 536.5 ms. All 183 frame pairs and 2,013 artifacts match.
End PSS medians are 949.8 → 916.8 MiB, but this short workload does not establish
a general memory reduction or bounded long-term retention.

[Separate allocation sampling](profiles/settle-image-comparison-allocation.json)
uses one red plus 36 dense captures with equal bounded GC settings and 512 KiB
sampling. Total post-readiness sampled byte weight falls 5.3%, from 5,954,679,907
to 5,639,300,681 bytes. The baseline's 378,104,656-byte direct settling-array
weight disappears. No comparator allocations are sampled; this does not mean the
small comparator object/channel checks allocate nothing. All 37 frame pairs and
407 exported artifacts match. Instrumented timings are excluded; sampled weights
are not exact allocations or live heap measurements.

**Decision: preserve as a promising conditional candidate, not enabled runtime.**
The default-GC mean regression remains unresolved despite a consistent gain with
bounded GC resources. The [tested patch](../../scripts/experiments/settle-image-comparison.patch)
applies to `c75dbff7`. Runtime and test source changes are removed from this records
PR. No heap or collector defaults change, and no repeated small-heap reload soak
has qualified this candidate for adoption.

Next combine this direction with eliminating intermediate PNG encoding/decoding,
then remeasure the default launch profile and repeated application reloads before
recommending the combined path. Preserve these results instead of treating a more
favorable launch configuration as a replacement for the original comparison.

## Existing API lead for the next experiment

Roborazzi 1.74.0, commit `56f6987180b28a10c8fe79dfd524c3f0d9c633d0`, already exposes
[`AwtImageWriter` and `JvmImageIoFormat`](https://github.com/takahirom/roborazzi/blob/56f6987180b28a10c8fe79dfd524c3f0d9c633d0/include-build/roborazzi-core/src/commonJvmMain/kotlin/com/github/takahirom/roborazzi/ImageIoFormat.commonJvm.kt).
[`AwtRoboCanvas.save`](https://github.com/takahirom/roborazzi/blob/56f6987180b28a10c8fe79dfd524c3f0d9c633d0/roborazzi-painter/src/commonJvmMain/kotlin/com/github/takahirom/roborazzi/AwtRoboCanvas.kt)
passes the cropped/scaled image to that writer. This offers an existing experimental
API to investigate before requesting any new entrypoint or using reflection.

It is not yet a validated file-free capture path: canvas release flushes images,
record reporting follows the writer, UI-tree annotation may read the output file,
and final encoding/metadata/failure semantics need preservation. The same source
already has an internal packed-integer equality shortcut for screenshot comparison;
its tolerance-aware comparator is not a substitute for our exact ARGB settling
contract. Reproduce lifecycle ownership and final PNG parity before adopting a
writer-based capture handoff. Do not report these APIs as missing upstream.
