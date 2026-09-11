# Rechecking settling snapshot buffer reuse

This change reuses two owned pixel arrays within one visual settling operation.
It follows the in-memory capture and bulk ARGB snapshot changes, so the older
pixel-buffer experiment is not a measurement of this implementation. Neither
experiment should be removed from the record.

## Ownership and correctness

The immediately preceding snapshot remains unchanged until comparison. The next
capture may overwrite only the array from two samples ago. Buffers do not survive
the settling call, and the final callback receives an independent pixel snapshot.
Dimensions are compared separately, including changes with the same pixel count.
The five-sample budget, frame advances, settling outcomes and final PNG validation
are unchanged. Arbitrary BufferedImage subclasses still receive a fresh getRGB
output array; only exact standard BufferedImage instances may reuse arrays.

Twenty focused tests pass across ARGB conversion, image subclass behavior,
changing dimensions, mutable capture images, final snapshot ownership, settling
and in-memory capture. This is a non-visual optimization; full worker artifacts
are compared below.

## G1 with the default compiler count

[Raw report](profiles/settle-buffer-reuse-g1.json). Three alternating pairs,
JDK 17, G1/Xmx1g, no compiler-count override, metrics every render, native
trimming disabled, warmed offline font cache. Each worker renders a red warmup
and 60 DenseDashboardPreview frames at 480 × 1200. No allocation profiler or
extra diagnostic collection runs alongside timings.

| Measure | Baseline | Two buffers |
| --- | ---: | ---: |
| Mean process CPU | 72.877 s | 71.657 s |
| Median workload wall time | 28.662 s | 28.543 s |
| Median last-30 mean CPU per frame | 708.0 ms | 682.7 ms |
| Median end-of-workload PSS | 960.65 MiB | 1005.90 MiB |

Mean CPU improves 1.7%, wall time 0.4%, but median final PSS increases
45.25 MiB (4.7%). Paired CPU changes are −1.9%, −3.6%, and +0.5%; this is
mixed evidence, not a demonstrated memory improvement. Final PSS is one sample,
not the process peak or retained live heap. The font cache is unchanged.

All 183 paired frames match across 11 artifacts (2,013 comparisons), normalizing
only semantics debug ordering and Typeface identity. Every-render heap metrics
and G1/default-compiler flags are verified in worker summaries.

The [GC log summary](profiles/settle-buffer-reuse-g1-gc.json) shows fewer
humongous-allocation concurrent-start pauses in every candidate pair:
53 → 12, 60 → 7 and 48 → 16. Total logged pause time barely changes
(4.031 → 3.997 s, 4.076 → 3.977 s, 4.022 → 4.021 s), while each worker
still performs 63 explicit full collections. This supports changed allocation/GC
behavior but does not explain the PSS increase or establish an overall memory
saving. Logs include startup and do not measure all concurrent GC CPU.

## Concurrent workers, 60 reloads each

[Raw report](profiles/settle-buffer-reuse-concurrent.json). The concurrent test uses two
workers sharing four physical cores, actual complex-screen application reloads,
Serial/Xmx256m, two compiler threads, metrics every five renders, and native heap
trimming every 15 seconds. Three alternating pairs give mean combined CPU 105.973 → 105.077 s (−0.85%)
and median peak observed combined PSS 1253.15 → 1235.17 MiB (−17.98 MiB).
The third pair reverses both CPU (104.32 → 104.98 s) and PSS
(1253.15 → 1269.12 MiB). Median group elapsed is 33.429 → 32.419 s, but this
includes up to one second of polling delay and shutdown, so it does not establish
a 3% rendering speed improvement. Host load is uncontrolled.

All 366 paired PNG/accessibility frames and both final SVG products match across
the 12 workers. Each worker produces 60 distinct application loader identities;
this is not a live-loader census or proof of retained-memory stability. Metrics
cadence, native trimming and unchanged frozen classpath entries are verified.

## Concurrent workers, 300 reloads each

[Raw report](profiles/settle-buffer-reuse-concurrent-300.json). The same protocol
with 300 actual reloads per worker produces:

| Measure | Baseline | Two buffers |
| --- | ---: | ---: |
| Mean combined CPU | 295.140 s | 292.393 s |
| Median group elapsed | 113.651 s | 113.603 s |
| Median peak observed combined PSS | 1295.75 MiB | 1302.87 MiB |
| Median final-30-second combined PSS | 1251.46 MiB | 1235.58 MiB |
| Mean minor page faults | 451,507 | 451,837 |

CPU improves 0.93%, with all three pairs lower: 296.71 → 292.67 s,
294.13 → 292.05 s, and 294.58 → 292.46 s. Wall time is effectively unchanged.
Peak combined PSS changes by +59.80, −68.14 and +7.11 MiB, respectively;
there is no consistent memory improvement. The median peak increases 7.11 MiB
while the median late-window value declines 15.88 MiB. Page faults are flat.
These remain profile-specific observations on a nonexclusive host.

All 1,806 paired PNG/accessibility frames and both final SVG products match.
Every worker verifies 300 distinct application loader identities. The reporter
checks every frame against its metrics cadence, confirms heap/collector/compiler
flags, and preserves CPU and wall results in 50-reload windows. This timing run
has no live-loader census or forced diagnostic GC checkpoints.

## G1 with two compiler threads

[Raw report](profiles/settle-buffer-reuse-g1-compiler2.json). The same three-pair
G1 protocol with `-XX:CICompilerCount=2`, as requested by generated Android
launch descriptors, gives mean CPU 62.750 → 60.693 s (−3.28%), median workload
wall time 28.615 → 28.212 s (−1.41%), and median final PSS 756.65 → 749.23 MiB.
All three CPU pairs improve: 62.43 → 60.30 s, 62.04 → 60.69 s and
63.78 → 61.09 s. The third PSS pair rises slightly, 756.54 → 756.92 MiB.
All 183 frame pairs and 2,013 artifact comparisons pass with the same narrow
normalizations as the default-compiler test. This checks the compiler setting,
not every option or workload supported by generated launches.

## Decision

Keep the two-buffer change as a small CPU optimization. It preserves snapshot
ownership and settling behavior, passes focused correctness tests and artifact
parity, and lowers CPU in all three 300-reload concurrent pairs and all three
G1/two-compiler-thread pairs. The default-compiler G1 mean also improves, although
one CPU pair reverses. These results supersede neither the historical pixel-buffer
experiment nor its different implementation and capture pipeline.

Do not advertise a memory reduction: PSS changes are mixed across configurations
and trials. No heap, collector, metrics-cadence or recycling defaults change.
No allocation profiler was run for this revision; fewer allocated snapshot arrays
follow from the implementation, but a sampled total-allocation percentage is not
claimed. A current CPU profile is the next step for choosing further work rather
than assuming remaining final-PNG validation dominates.

## Reproduction

Freeze the baseline before building the candidate, then freeze the rebuilt
renderer with only classpath entry 19 replaced. The reports record ordered
classpath hashes; do not compare against a mutable build output. The G1 matrix
is `scripts/experiments/settle-buffer-reuse-g1.json` and is run with
`scripts/benchmark-worker-matrix.py`, three trials, 60 DenseDashboardPreview
renders, width 480, height 1200, and `--memory`.

The concurrent runner's `classpath` policy is available in main commit
`081eac9946162bf05fda4c7b6d07dc1097edec47`:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --policy classpath \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --candidate-classpath daemon/android/build/settle-buffer-reuse-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --cpus 0,1,2,3 --renders 300 \
  --font-cache daemon/android/build/bulk-font-cache \
  --output daemon/android/build/settle-buffer-reuse-concurrent-300
```

The test fixture jar contains `benchmark.screens.ReloadDashboardPreviewsKt`
and `ReloadDashboardPreview`. Frozen jars and warmed fonts are local benchmark
inputs, not downloaded by this command. Use a fresh output directory. The actual
runs used a temporary copy of the runner from commit `6fe4ee9a` (merged by #87),
with its harness path made absolute; runtime arguments and measurement logic are
unchanged. Do not overlap builds, profilers or other benchmarks with this run.
