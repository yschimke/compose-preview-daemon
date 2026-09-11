# Capture-local settling pixel buffers

The settling loop decodes each captured frame and creates an `IntArray` for exact
ARGB comparison with the previous sample. This experiment keeps two arrays local
to one invocation, swaps their roles after comparison, and allocates a replacement
only when the spare array has a different pixel count. Width and height remain
separate equality conditions, including when differently shaped images contain
the same number of pixels. No sample, frame advance, comparison, final-image
callback, or outcome is removed. No array escapes the invocation.

This is our renderer pipeline optimization, not a Robolectric defect. Allocation
reduction is a hypothesis to measure, not evidence of lower CPU or live memory.

## Validation

All 15 focused tests pass: six settling-contract tests, five final-image tests,
and four decode tests. New regressions exercise equal-area dimension changes and
an alternating last pixel after reuse starts. The existing tests cover all three
outcomes, dimension growth, exact sample/advance counts and failed decoding.

The controlled timing matrix uses three rotated pairs of fresh JDK17/G1 workers,
1 GiB heap, default compilation and GC thread counts, equal GC logging, one red
frame plus 60 dense-dashboard captures at 480×1200 and density 2. Frozen input
manifests confirm only classpath entry 19 (the renderer jar) differs.

The first attempted matrix (`settle-pixel-buffers-matrix`) was stopped because its
baseline also differed in the core dependency containing the merged CI race fix.
Do not use it as performance evidence. The corrected baseline takes every current
candidate dependency except the prior renderer jar. Completed controlled results are in `daemon/android/build/settle-pixel-buffers-controlled-matrix`.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/settle-pixel-buffers-default.json \
  --classpath daemon/android/build/settle-pixel-buffers-baseline-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/settle-pixel-buffers-controlled-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

## Results and decision

[All three timing pairs](profiles/settle-pixel-buffers.json) match 183 frame pairs
and 2,013 exported artifacts. Only documented diagnostic-order and Typeface-identity
normalizations are used; pixels and meaningful node data are unchanged.

| Metric | Baseline | Two reusable arrays |
| --- | --- | --- |
| Total CPU per trial (s) | 79.31 / 81.94 / 82.21 | 80.62 / 81.07 / 134.89 |
| Mean total CPU (s) | 81.15 | 98.86 |
| Median total CPU (s) | 81.94 | 81.07 |
| Median last-30 mean CPU (ms) | 824.7 | 838.3 |
| Median last-30 wall latency (ms) | 546.5 | 565.0 |
| Concurrent mark cycles per trial | 6 / 18 / 19 | 6 / 4 / 365 |
| Median end PSS (MiB) | 1028.2 | 991.1 |

All trials are retained. The candidate's third run has excessive concurrent
marking and raises mean CPU by 21.8%; the small median CPU improvement does not
justify adoption. This demonstrates an unfavorable observation, not proof that
array reuse alone causes the GC pattern. Similar sensitivity has appeared in
other allocation experiments. The end-PSS difference is not a memory guarantee.

[Separate allocation profiles](profiles/settle-pixel-buffers-allocation.json)
compare one red plus 36 dense captures with equal G1 thread bounds (2 parallel,
1 concurrent), async-profiler allocation sampling at 512 KiB and post-readiness
filtering. Direct settling-array sampled weight falls from 360.6 to 147.4 MiB;
total sampled weight falls from 5678.8 to 5459.9 MiB (3.9%). These are allocation
sample weights, not exact counts or live heap. All 37 frame pairs and 407 exported
artifacts match. Instrumented timings are excluded from performance claims.

**Deferred:** runtime and test changes are removed; the exact tested variant is
archived in [settle-pixel-buffers.patch](../../scripts/experiments/settle-pixel-buffers.patch),
applicable to commit `a8bfdb43`. No heap, collector or recycling default changed.
There was no small-heap reload soak for this deferred variant, so it is not
qualified for adoption on the basis of capture-local ownership alone.

Next investigate bounded row/strip comparisons or a supported in-memory capture
path, preserving exact colors, changing dimensions, every sample and final PNG
semantics. Reducing allocation without a reproducible CPU or memory benefit is
insufficient on a loaded server.
