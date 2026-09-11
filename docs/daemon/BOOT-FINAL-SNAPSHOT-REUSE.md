# Reuse the final settling snapshot for PNG validation

Reuse the ARGB array already computed by the settling loop instead of reading
the accepted image into another full-frame array during final PNG validation.
The decoded PNG still gets its own ARGB array and must exactly match the snapshot.
Settling samples, frame advances, dimensions, outcomes, default encoder/reporting,
and public callback signatures are unchanged. The internal callback carries the
capture-local snapshot; nothing is cached across requests.

The baseline includes main's host ActionBar fix (#74) and in-memory settling
(#72), commit `271a8e7256159f0973a5e00d03377fbbc878f5e5`. Nine host-theme tests
and a four-frame dashboard smoke run passed before freezing it. Late ActionBar
warnings disappeared. The older frozen renderer differs only in the host-theme
class and version metadata, so older benchmark numbers are not treated as this
candidate's control. The candidate/control classpaths have 252 entries and differ
only in the renderer jar (entry 19).

## Results and limits

Three alternating fresh-worker pairs, red plus 60 dense dashboard renders at
480×1200 density 2, JDK 17, 256 MiB Serial heap with 32 MiB initial heap and free
ratios 10/30. Equal GC logging, no profilers or overlapping builds/benchmarks.

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Mean process CPU | 64.620 s | 64.567 s |
| Median whole-workload wall time | 38.569 s | 39.226 s |
| Median last-30 mean render CPU | 586.33 ms | 584.67 ms |
| Median last-30 wall time | 519.5 ms | 520.5 ms |
| Median end PSS | 712.41 MiB | 719.51 MiB |

CPU is effectively unchanged (-0.08%). Whole-workload median wall time is 1.7%
higher, and residency varies substantially. No demonstrated latency or live-memory
saving is claimed. All **183 frame pairs and 2,013 exported artifacts match**;
[all rows and parity checks](profiles/reuse-final-snapshot.json) are retained.

A separate allocation pair (red plus 36 dense renders, same Serial heap, async-
profiler 4.5 allocation sampling at 512 KiB, post-readiness filtering) samples
**5,505,452,862 → 5,330,184,960 bytes** overall (-3.2%). More directly, final
validation's `BufferedImage.getRGB` array samples halve from **165,889,152 →
80,640,560 bytes**, consistent with removing one of its two full-frame array
reads. All **37 frame pairs and 407 artifacts match**. These are sampled weights,
not exact allocation totals or live heap; inclusive stacks overlap and must not
be summed. [Allocation report](profiles/final-snapshot-allocation.json).

Keep this small removal of redundant work for its verified allocation reduction,
not as a speedup claim. It does not justify heap, collector, allocator or worker
recycling changes. This is our own capture pipeline, not missing Robolectric API.

## Validation and reproduction

Twenty tests pass: `InMemoryStillCaptureTest` (6),
`CaptureVisuallySettledFrameTest` (6), `FixedAxesDecodedImageTest` (4),
`FramePngReaderTest` (4). They cover all settle outcomes, capture counts, image
handoff, real painter release/metadata, reporting and failure/fallback behavior.
Formatter and full artifact comparisons pass. No public Kotlin API changes.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/reuse-final-snapshot.json \
  --classpath daemon/android/build/host-fixed-baseline-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/reuse-final-snapshot-reproduction \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Use a fresh directory. Rebuild/freeze baseline and candidate when the local frozen
jars are unavailable, preserving ordered SHA-256 manifests. Allocation runs use
the same flags without GC logging, plus `-agentpath:<libasyncProfiler.so>=start,
event=alloc,interval=524288,cstack=no,file=<output>/allocations.jfr`; convert using
`jfrconv --alloc --total --from <ready-unix-ms> -o collapsed`. Do not use their
timings for performance claims.
