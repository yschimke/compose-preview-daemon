# Investigate repeated PNG decoding

**Metadata fast path rejected for this workload.** The tested prototype is
archived in [a patch](../../scripts/experiments/png-resize-metadata.patch);
the replacement decoded-image handoff candidate is described below.

The [allocation profile](profiles/inspector-allocation-profile.json) attributes
about 302 MiB of sampled post-readiness allocation to `resizeFixedAxesPng` over
37 frames. Both Android render paths decode a PNG before checking whether its
size already matches the requested fixed axes. The final dense benchmark PNGs are 480×1200. That alone does not prove the
pre-resize captures already match; the allocation profile must establish whether
the fast path is actually exercised.

The candidate records when visual settling has successfully decoded the output.
Only that unchanged output, with no intervening dialog or wrap crop, can take a
metadata-only dimension check before the existing decode. Matching dimensions
return without another pixel buffer; a mismatch or failed metadata probe falls
through to the original decoder and resize logic. The ImageIO reader and input
stream are closed for every probe.

Single-shot/exact-phase captures do not assert prior decoding and retain their
original path. No capture, frame advance, comparison, settling threshold, crop,
edge extension, or corrupt-frame validation is removed. Daemon decode failures
remain caught; standalone decode failures still propagate when decoding is
required. The internal resize helper's new flag defaults to false.

Four new resize tests cover byte preservation, edge extension, one fixed axis,
and metadata-failure fallback. Together with the existing frame-decoder and
settling tests, all 14 focused tests pass. Frozen classpaths have 252 entries;
only the daemon runtime/full jars and renderer jar differ from the method-cache
baseline. The deferred field cache is not included.

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/png-resize-default.json \
  --classpath daemon/android/build/layout-method-candidate-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/png-resize-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Use default JDK17 compilation, G1/1 GiB and equal GC logging. Retain all trials,
including any concurrent-mark outliers, and check every exported artifact.
The metadata prototype results below show no established performance benefit.


## First timing comparison

[Three rotated pairs](profiles/png-resize.json) match all 183 frame pairs and
2,013 data artifacts. Median total CPU is 81.56 → 82.83 s, steady CPU
834.7 → 837.3 ms, and steady latency 556.5 → 555 ms. End PSS is
1022.9 → 1008.9 MiB. These results do not establish a CPU or physical-memory
benefit. A separate allocation profile is checking whether the no-op branch
actually applies to the workload; final PNG dimensions alone were insufficient
evidence of the pre-resize dimensions.


## Diagnostic correction and next approach

[Allocation evidence](profiles/png-resize-allocation.json) shows resize decoding
still present: baseline sampled allocation is 150.2 MiB under `ImageIO.read`
and 151.8 MiB creating the corrected image; the metadata candidate is 152.2 and
156.0 MiB respectively. The no-op assumption was wrong.

A temporary dimension diagnostic, excluded from timing and then removed, reports
source **480×1088** and target **480×1200** for both red and dense captures at
density 2. Warm-up is 64×8 → 64×64. Thus the helper genuinely extends the frame;
the 56 dp height difference needs separate ownership analysis, not an assumed
Robolectric bug. Final output dimensions alone had hidden this correction.

The metadata prototype and tests are archived and removed from production;
reverse and reapplication checks pass. The next approach should pass the final
already-decoded settling image to size correction when no intervening crop has
changed the file. Preserve the existing public settling entrypoint, every sample,
clock advance, exact pixel comparison and correction pixel. Single-shot captures
and cropped frames must keep their normal decode. This requires an explicit
per-capture handoff, not a global file/image cache or a changed viewport.

## Final decoded-image handoff candidate

The revised implementation adds an overload of `captureVisuallySettledFrame`
that delivers the final decoded `BufferedImage` once, after all comparisons and
before returning. The original four-argument JVM method remains present and
forwards with an empty callback. Neither entrypoint changes captures, advances,
outcomes, or failure handling; the callback is not invoked on failed decoding.

Daemon and standalone stills retain that single image only when neither axis is
wrapped, release it before a dialog crop, and supply it to size correction only
when the file has not been cropped. The reference is cleared after correction.
Other paths retain the existing decoder. Size correction still performs the same
crop or edge extension, including 480×1088 → 480×1200 in the diagnostic fixture.
There is no metadata probe, changed viewport, or cross-capture image cache.

The initial validation passed 28 renderer tests, including final-image delivery
for all outcomes, dimension changes, decode failure, and byte-identical resize
results with supplied versus file-decoded input. The 18 selected daemon tests
also passed (gutter, override, and permissions-override paths). Final validation of the tightened release guards also passes: 28 renderer tests
and 18 daemon tests. The frozen candidate differs from the method-cache baseline
only in the daemon runtime/full jars and renderer jar.
Allocation and timing results for this revision are recorded below; the earlier
metadata prototype's measurements do not describe this implementation.


The handoff allocation profile now shows no `ImageIO.read` samples beneath size
correction. Inclusive correction allocation weight drops from 302.5 to 156.0 MiB;
creating the corrected image remains necessary. Total sampled allocation is
5,717.9 versus 5,848.3 MiB (about 2.2% lower), and all 37 PNG/UIA pairs match the
baseline. These sampled weights do not establish retained-memory or CPU savings.
Uninstrumented timing trials follow with the new frozen candidate.

## Decoded-image handoff timing results

[Three fresh rotated pairs](profiles/png-decoded-frame.json) compare the handoff
against the method-cache baseline under default JDK17/G1 settings. All 183 frame
pairs and 2,013 artifacts across eleven products match. No trial is excluded.

| Median measure | Method-cache baseline | Decoded-image reuse |
| --- | --- | --- |
| Whole-worker CPU | 81.62 s | 80.39 s |
| Steady mean CPU/request | 831.0 ms | 822.3 ms |
| Steady median wall/request | 579 ms | 562.5 ms |
| Whole-worker wall | 51.371 s | 43.326 s |
| Ready wall | 4353 ms | 4356 ms |
| End PSS | 996.3 MiB | 980.6 MiB |

Mean total CPU is 81.60 → 80.277 s (1.6% lower). Baseline totals are
81.62/82.66/80.52 s and candidate totals 79.63/80.39/80.81 s: the third pair is a
small regression, not an omitted result. Steady median latency improves 2.8%.
These are modest improvements; whole-worker wall and PSS remain variable and
must not be presented as general guarantees. The completed 300-reload, 256 MiB Serial/NMT soak below checks the new
image handoff's lifetime separately from timing.

The dimension diagnostic also emits Roborazzi's ActionBar-overlap warning for
SDK 35+, including its statement that hiding the ActionBar may invalidate layout
and its recommendation to use a no-action-bar test theme. This is an observed
hosting/configuration lead, not proof that it causes the 56 dp difference or a
new Robolectric defect. Earlier lifecycle experiments already required AGP's
manifest/resources and module working directory for the no-action-bar test theme
(see [the lifecycle checks](BOOT-FROZEN-BINDINGS-EXPERIMENT.md)). Compare correctly
configured host themes before generalizing these dimensions to ordinary unit or
screenshot testing. The current optimization preserves the existing correction.

## Completed 256 MiB reload soak

[The final handoff soak](profiles/png-decoded-frame-reload-256.json) uses the same
Serial-GC/free-ratio/NMT profile as the method-cache baseline, with forced-GC and
live-histogram checkpoints every 50 renders. All 301 PNG/UIA pairs match. Each
run observes 300 distinct application loader IDs, with exactly two live child
loaders at every checkpoint.

Candidate live heap at 0/50/100/150/200/250/300 reloads is
87.82/90.75/91.12/91.50/80.60/79.49/79.79 MiB; baseline ends at 79.76 MiB.
Candidate PSS is 561.62/752.96/796.52/812.08/818.29/821.06/820.35 MiB, versus
914.55 MiB at the baseline's end. This one pair supports no added loader or
live-heap growth in the tested workload; it does not prove a general PSS saving
or an indefinite bound. Diagnostic timings are excluded from performance claims.
Heap, collector and recycling defaults remain unchanged.
