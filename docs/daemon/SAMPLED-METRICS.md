# Opt-in sampling of post-GC render metrics

`composeai.daemon.metrics.everyRenders` controls how often the daemon deliberately
requests GC to collect the structured render metrics block. The default is **1**,
which retains measurement on every render. An integer N greater than 1 measures
the first render in a sandbox and then every N renders thereafter. Counts include
warm-up renders. Invalid input uses the default; values below 1 clamp to 1.

On an unmeasured render, the raw result retains `tookMs` but does not contain any
of the four structured metric fields. The JSON-RPC adapter emits no populated
`metrics` block. It does not publish a stale heap value, zero, or an ordinary
used-heap reading under `heapAfterGcMb`. A genuinely partial metrics map still
produces the existing warning. Lifecycle counters advance on every render, so
measured blocks retain the actual render count; a sandbox-stat reset restarts
the cadence. The count and measurement belong to each sandbox, not to a preview.

For example, a host starting from zero with N=3 measures render counts 1, 4 and 7.
The intervening `renderFinished` notifications keep their normal timing and image
result, with `metrics` null/absent. History records for those renders likewise have
no structured measurement. Consumers must tolerate missing samples before an
operator opts in. The published wire shape already permits absent metrics;
there is no new wire field and no need for a contracts release.

Pass `-Dcomposeai.daemon.metrics.everyRenders=5` to the daemon JVM, or add that
system property to its launch descriptor. Existing property forwarding carries
it to Android pooled workers and adopted spares. The value is read when collecting
metrics, so adopted workers use their configured policy. A warm-up measurement
may occur before a spare is configured. Desktop uses the same measurement helper.

Do not combine this setting with `-XX:+DisableExplicitGC` when relying on post-GC
metrics: the JVM flag can ignore the requested GC even on a measured render.
The existing JVM-hint qualification still applies. `nativeHeapMb` retains its
existing approximate virtual-memory semantics; use process RSS/PSS for residency.

Increasing the interval may reduce CPU and latency but increase resident memory.
It does not change heap limits, automatic GC, render scheduling or worker recycling.
The default is not reduced merely because explicit collection is expensive.

## Validation

Lifecycle tests verify that only measured renders request GC, measurements are
complete, unmeasured results contain timing only, every render advances the count,
and reset restarts the schedule. A JSON-RPC round-trip verifies that timing-only
results preserve duration and omit the structured block. Existing complete,
missing and partial-map cases remain covered. Property registry, round-trip and
generated-document checks are included. The public API dump adds only the new
property and its name constant; the measurement method's public signature is unchanged.

## Dense-screen measurement

Three alternating fresh-worker pairs use JDK17, Serial GC, Xmx256m/Xms32m,
free ratios 10/30, two compiler threads and the same warmed offline font cache.
Both variants use identical frozen jars; only the measurement cadence changes.
Each renders red plus 60 dense-dashboard frames at 480×1200, density 2.

| Metric | Every render | Every fifth render |
| --- | ---: | ---: |
| Mean total CPU | 53.910 s | 49.647 s (−7.9%) |
| Median total wall | 32.764 s | 28.716 s (−12.4%) |
| Median steady-frame CPU | 571.7 ms | 505.0 ms (−11.7%) |
| Median steady-frame wall | 429.0 ms | 325.5 ms (−24.1%) |
| Median end PSS | 559.54 MiB | 618.48 MiB (+58.94 MiB / 10.5%) |

[The full report](profiles/sampled-metrics.json) retains all runs, GC summaries,
measured render counts and artifact comparisons. All **183 frames and 2,013
artifacts match**, with the existing documented debug-order/Typeface-identity
normalizations. Every-fifth workers emit 12 complete metric blocks and 49
timing-only results across the 61 measured requests; prior warm-up explains the
phase. No partial or stale metrics are returned. The additional residency is a
real tradeoff, so the default remains 1. These numbers do not establish a universal
saving or a memory bound.

Reproduce with `scripts/experiments/sampled-metrics.json` using
`benchmark-worker-matrix.py --trials 3 --renders 60 --fixture DenseDashboardPreview
--width 480 --height 1200 --memory`, the frozen classpath
`daemon/android/build/sampled-metrics-frozen/classpath.txt`, JDK17 and a fresh output
folder. Prepare the warmed font cache using the font-cache correction guide.

## Application-reload retention diagnostic

A separate run renders the dense child-loaded `ReloadDashboardPreview` 300 times
with `--swap-every 1`, the same launch settings and cadence 5. The summary verifies
300 distinct application loader identities. All **301 PNG/UIA pairs match** the
cached baseline; output reuse means other historical artifacts are not retained
and no full-history SVG/data comparison is claimed here.

Live histograms after diagnostic GC checkpoints at render indices 0, 50, 100,
150, 200, 250 and 300 each contain **two application child loaders**. Heap used
reported by `GC.heap_info` is 86.6 MiB at index 0, then 98.9–101.0 MiB at the later
checkpoints. PSS rises from 641.4 MiB at 50 to 716.8 MiB at 300, so this does not
establish a whole-process residency plateau. See the
[checkpoint report](profiles/sampled-metrics-reloads.json).

The added `GC.run` and live-histogram checkpoints force collection and can alter
residency. They demonstrate loader collectibility at those checkpoints, not an
unforced memory bound or a safe reaping interval. Diagnostic timings are excluded
from the performance comparison. The cadence's metric blocks remain complete
and occur only at the expected render counts, independently of diagnostic GCs.

To reproduce, use the cadence-5 variant's JVM flags and frozen classpath with
`benchmark-worker-startup.py --renders 300 --fixture ReloadDashboardPreview
--class-name benchmark.screens.ReloadDashboardPreviewsKt
--user-class-dir daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar
--swap-every 1 --reuse-output --width 480 --height 1200 --memory
--gc-checkpoint-every 50 --heap-histograms`, JDK17 and a fresh output directory.

This option trades measurement frequency and potentially higher resident memory
for CPU/latency. It has not been qualified for a new default or a general server capacity
recommendation. The bounded concurrent experiment below provides narrower evidence.
The existing eager default and worker lifecycle policy remain intact.


## Two workers sharing four CPUs

A subsequent three-pair experiment runs two workers concurrently on the same four
logical CPUs (0, 1, 2, 3), verified to map to four distinct physical cores. Each
worker performs 60 actual application reloads. JVM settings remain JDK17, Serial
256 MiB/Xms32 MiB, free ratios 10/30 and two compiler threads; both variants use
the same warmed font cache in offline mode. Only the metrics cadence changes.
The [complete report](profiles/sampled-metrics-concurrent.json) retains live
worker-affinity observations, all PSS samples, flags, topology, font hashes and
actual measured/skipped metric counts.

| Metric across worker pairs | Every render | Every fifth render |
| --- | ---: | ---: |
| Mean combined process CPU | 117.187 s | 105.897 s (−9.6%) |
| Median group elapsed | 38.460 s | 32.411 s (−15.7%) |
| Median observed peak combined PSS | 1165.86 MiB | 1215.52 MiB (+49.66 MiB / 4.3%) |

Every paired trial uses less CPU and finishes sooner at cadence 5. Memory is higher
in all three pairs: 1165.86→1260.20, 1189.28→1215.52 and 1124.30→1215.38 MiB.
The difference of aggregate medians above is not the median of paired differences.
All **366 paired PNG/UIA frames match** and each worker reports 60 distinct
application loader identities. Across all 12 workers, measured blocks are complete
and on schedule; skipped measurements contain timing only. The shared font-cache
hashes are unchanged.

These are renderer-worker costs, not a complete server benchmark. The four CPUs
are shared by the workers but not reserved exclusively from other host tasks.
Process CPU ends at workload completion and excludes shutdown; group elapsed
includes startup, shutdown and up to one polling interval. PSS is read sequentially
from both live workers once per second, so the maximum observed sum is not an
atomic measurement or the exact peak. No profiler, NMT or extra diagnostic GC is
used in this comparison. It supplies no new live-loader census or lifetime bound.

This strengthens the evidence for a CPU/latency tradeoff under contention. It
continues to cost resident memory, so it does not justify a universal default
change or a fixed workers-per-server recommendation.

Reproduce after preparing the warmed cache:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --policy metrics --cpus 0,1,2,3 --renders 60 \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --font-cache daemon/android/build/bulk-font-cache \
  --output daemon/android/build/sampled-metrics-concurrent
```

The runner's historical allocator and compiler modes remain available. Metrics mode
requires a warmed cache and checks the fixture's Roboto WOFF2 signatures before
launch, preventing silent offline fallback from being treated as the same workload.
