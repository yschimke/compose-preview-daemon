# Periodic native trimming with sampled render metrics

Sampling post-GC metrics reduces CPU and latency but increases resident memory.
This experiment tests whether HotSpot's existing periodic native-heap trimmer
recovers some of that cost while keeping the CPU gain. No runtime defaults change.

## Runtime support and scope

The installed OpenJDK **17.0.19+10** exposes `TrimNativeHeapInterval` as a product
flag, defaulting to 0. Launching with `-XX:TrimNativeHeapInterval=5000` and
`-Xlog:trimnative=debug` confirms that the native heap trimmer starts. The installed
21.0.12+8 also exposes the flag. Support must be verified on the actual vendor/update
build, not inferred merely from the major version.

The [OpenJDK option documentation](https://github.com/openjdk/jdk/blob/master/src/java.base/share/man/java.md)
describes periodic native trimming on Linux/glibc, with shorter intervals trading
more eager reclamation for overhead. This returns eligible allocator pages; it
does not free live objects or establish that retained native memory is a leak.
`SandboxSparePool.Config.trimNativeHeapMs` already exposes an optional launch path
with a conservative JDK-21-or-newer/current-launcher guard. This experiment passes
the verified JVM flag directly and does not relax that guard.

## Controlled comparison

Three rotated trios use identical frozen jars from the sampled-metrics implementation,
JDK17, Serial GC, Xmx256m/Xms32m, free ratios 10/30 and two compiler threads. All
variants use the same warmed font cache in offline mode and equal trim logging.
Each worker renders red followed by 60 dense-dashboard frames at 480×1200, density 2.

1. Measure every render; trimming disabled.
2. Measure every fifth render; trimming disabled.
3. Measure every fifth render; trim every 5000 ms.

The [full report](profiles/sampled-metrics-trim.json) retains every run, artifact
comparison, trim event, font-cache hash and late-RSS summary.

| Metric | Every render | Every fifth | Every fifth + trim |
| --- | ---: | ---: | ---: |
| Mean total CPU | 54.167 s | 49.537 s | 49.607 s |
| Median total wall | 32.314 s | 28.093 s | 28.137 s |
| Median steady-frame CPU | 576.3 ms | 504.0 ms | 507.7 ms |
| Median steady-frame wall | 421.5 ms | 315.0 ms | 313.5 ms |
| Median end PSS | 572.00 MiB | 636.77 MiB | 582.74 MiB |
| Median of final-10-second median RSS | 603.61 MiB | 649.27 MiB | 591.22 MiB |

Relative to sampling alone, trimming reduces median end PSS **54.03 MiB (8.5%)**
with **0.14% higher mean CPU** and almost unchanged total wall. That CPU difference
is too small to establish a precise overhead. End PSS falls in all three paired
comparisons: 643.1→582.7, 636.8→586.7 and 607.6→579.2 MiB. Native-trim memory can
refill between events, so end PSS is not treated as a durable floor. The separate
late-RSS measurement supports a sustained difference during this short workload;
RSS and PSS are different measurements and should not be compared directly.

Relative to every-render measurement, the combined profile uses **8.4% less CPU**
and **12.9% less total wall**, with **10.74 MiB (1.9%) higher median end PSS**.
This recovers most of the sampled-metrics memory penalty in this single-worker
workload. It does not establish a universal memory reduction.

All **366 paired frames and 4,026 data artifacts match**: each of the two candidate
variants is compared against every-render measurement in all three trials. Only
the existing documented diagnostic-order and Typeface-identity fields are normalized.
The sampled variants continue emitting complete metrics only on measured frames;
trimming does not change that schedule or turn skipped frames into heap measurements.

Each trimmed run logs five actual periodic trims. Maximum logged trim duration per
run is 7.280, 4.053 and 4.385 ms. These are operation durations, not exclusive CPU
cost or an end-to-end stall bound. Do not sum reclaimed bytes across trims as if
they were distinct permanent savings: freed pages can be allocated and trimmed again.

## 300 actual application reloads

One ordered trio uses the same policies and frozen classpath, with
`ReloadDashboardPreview` loaded through a fresh application classloader for every
render. Each worker completes 300 reloads plus warm-up. No diagnostic GC,
histograms or profiler were added. The [reload report](profiles/periodic-trim-reloads.json)
records request-aligned RSS windows, sampled heap readings and actual trim events.

| Metric | Every render | Every fifth | Every fifth + trim |
| --- | ---: | ---: | ---: |
| Total CPU | 173.510 s | 143.560 s | 142.710 s |
| Total wall | 135.436 s | 107.465 s | 107.485 s |
| End PSS | 633.00 MiB | 723.74 MiB | 621.17 MiB |
| Reloads 251–300 median RSS | 667.76 MiB | 738.54 MiB | 631.98 MiB |
| Reloads 251–300 median wall | 427 ms | 283 ms | 282 ms |

Against sampling alone, trimming ends **102.58 MiB lower in PSS**, with nearly
identical wall time and 0.6% lower CPU. Against every-render measurement, the
combined profile uses **17.8% less CPU**, **20.6% less wall time**, and ends
**11.84 MiB lower in PSS**. This is one ordered trio, not a repeated estimate of
small CPU or memory differences.

The trimmed worker's median RSS across successive 50-reload windows is 595.0,
611.9, 619.9, 632.0, 636.6 and 632.0 MiB. The final two windows are encouraging,
but do not establish a permanent plateau or a safe recycling interval. Observed
RSS can spike between trims (maximum 682.84 MiB in the final window). RSS windows
and endpoint PSS must not be treated as the same metric.

All **602 paired PNG/UIA frames** match across the two candidate comparisons.
All three workers use 300 distinct application-loader identities; this is not a
live-loader census. Final SVG bytes also match. Reused output paths retain only
the final other artifacts, so historical SVG/data parity is not claimed. No frame
exceeds two seconds. The trimmed run logs 21 actual trims, with maximum logged
operation duration 7.208 ms. Metric cadence and unchanged font-cache hashes are
verified.

## Two concurrent workers sharing four CPUs

Three alternating pairs compare every-fifth metrics with trimming off versus
5000 ms. Each pair uses two concurrent workers, each doing 60 actual application
reloads, with the same frozen jars, warmed offline fonts, Serial heap settings
and two compiler threads. Affinity is verified as CPUs 0,1,2,3 (distinct physical
cores). Other local builds/benchmarks are stopped; these cores are not exclusive
and host load remains uncontrolled. See the [concurrent report](profiles/periodic-trim-concurrent.json).

| Metric | Every fifth | Every fifth + trim |
| --- | ---: | ---: |
| Mean combined CPU | 106.460 s | 105.380 s |
| Median group elapsed | 33.373 s | 32.372 s |
| Median observed peak combined PSS | 1246.94 MiB | 1157.86 MiB |
| Mean minor page faults | 274,180 | 342,702 |

Observed combined peak PSS is lower in all three pairs: 1228.45→1157.86,
1288.06→1178.57 and 1246.94→1146.16 MiB. The difference of aggregate medians is
**89.09 MiB (7.1%)**. Mean CPU is 1.0% lower and median group elapsed 3.0% lower;
these small timing differences do not establish a precise speedup from trimming.
Minor faults increase **25.0%**, consistent with returned pages needing to be
faulted back in; this is a cost to monitor on busier workloads, not free memory.

PSS is read sequentially from both live workers roughly once per second, so the
maximum observed sum is not an exact or atomic peak. Group elapsed includes
startup, shutdown and up to one polling interval; process CPU ends at workload
completion. Do not compare this combined peak directly with single-worker endpoint
PSS or RSS windows.

All **366 paired PNG/UIA frames** match, with 60 distinct application-loader
identities per worker. All 12 workers' sampled-metric cadence and unchanged font
cache hashes are verified. Each trimmed worker logs six trims; maximum logged
operation duration is 6.198 ms. No checkpoint GC, histograms or profiler were added.

## Longer concurrent reload comparison

The same three alternating pairs were repeated with **300 actual application
reloads per worker**, retaining the four-CPU shared affinity and every-fifth
measurement on both variants. No extra diagnostic GC, census or profiler was
added. The [longer concurrent report](profiles/periodic-trim-concurrent-300.json)
retains all six groups, per-worker 50-reload RSS/CPU windows, reported heap samples,
20-second combined-PSS windows, faults and trim durations.

| Metric | Every fifth | Every fifth + trim |
| --- | ---: | ---: |
| Mean combined CPU | 295.700 s | 296.760 s |
| Median group elapsed | 114.565 s | 114.605 s |
| Median observed peak combined PSS | 1408.05 MiB | 1270.23 MiB |
| Median of last-30-second median combined PSS | 1387.65 MiB | 1210.12 MiB |
| Mean minor page faults | 331,511 | 814,103 |

The difference of aggregate peak medians is **137.82 MiB (9.8%)**. All three
paired peaks fall: 1408.05→1270.23, 1420.53→1247.62 and 1394.31→1281.31 MiB.
The late-window comparison also favors trimming in every pair. CPU rises **0.36%**
and elapsed changes **0.03%**; neither establishes a meaningful timing difference.
Minor faults rise **145.6%**, substantially more than the shorter-run increase.
Returning and later reusing allocator pages has a measurable fault cost even
when this workload's CPU and wall totals barely change.

This is a reduction in residency, **not evidence of a universal plateau**. Across
successive 50-reload windows, one trimmed worker's median RSS rises from 570.5 to
599.3, 619.3, 623.4, 634.5 and 643.3 MiB. Other trimmed workers level off or fall
in their final windows. Baselines rise more consistently, ending with median RSS
between 716.6 and 734.0 MiB. Sampled heap readings alone cannot assign the remaining
RSS to a particular native allocator, classloader, JIT or library. No live-loader
census was performed in this timing experiment, and no recycling interval follows.

All **1,806 paired PNG/UIA frames** match, with 300 distinct application-loader
identities per worker. Final SVG bytes match across all 12 workers; historical
other artifacts were overwritten and are not claimed. No frame exceeds two
seconds. Metric cadence, CPU affinity and unchanged font-cache hashes pass.
Every trimmed worker logs 22 actual trims; maximum logged operation duration is
7.553 ms. The same sampling and endpoint limitations as the short comparison apply.

## Five versus fifteen seconds

A further three alternating pairs compare **5000 ms versus 15000 ms**, with both
variants measuring every fifth render and doing 300 actual reloads per worker.
The same frozen jars, shared four-CPU affinity, heap, compiler settings and offline
fonts apply. This directly compares intervals rather than relying on historical
runs. See the [interval report](profiles/periodic-trim-intervals-300.json).

| Metric | Trim every 5 s | Trim every 15 s |
| --- | ---: | ---: |
| Mean combined CPU | 296.680 s | 295.910 s |
| Median group elapsed | 114.635 s | 114.673 s |
| Median observed peak combined PSS | 1264.38 MiB | 1291.00 MiB |
| Median of last-30-second median combined PSS | 1209.96 MiB | 1234.48 MiB |
| Mean minor page faults | 660,271 | 434,470 |

Fifteen seconds reduces mean minor faults **34.2%**, with **0.26% lower CPU** and
**0.03% higher elapsed time**. The timing differences are too small to establish
a speedup. The difference of aggregate peak medians is **26.62 MiB higher (2.1%)**;
late-window median PSS is 24.52 MiB higher. Memory is not consistently worse in
every pair: peaks are 1264.38→1330.48, 1255.37→1291.00 and 1294.29→1285.34 MiB.
All three pairs show fewer minor faults at fifteen seconds.

Every five-second worker logs 22 trims, versus seven for every fifteen-second
worker. Maximum logged trim operation duration is 10.906 versus 7.655 ms, which
is not an end-to-end stall bound or a repeatable latency claim. All **1,806 paired
PNG/UIA frames** and final SVG bytes match. Each worker uses 300 distinct loader
identities, and metric cadence, affinity and unchanged font-cache hashes pass.

This exposes a useful workload-specific choice: less frequent trimming reduces
page churn at a modest aggregate residency cost in this experiment. It does not
establish fifteen seconds as optimal, nor remove the need to investigate late
memory growth. Neither interval becomes a production default.

## Decision and next validation

Keep this as a measured opt-in launch-policy candidate. Five- and fifteen-second intervals were tested,
neither established as optimal. Another collector, another OS/libc, older JDK updates and lifetimes beyond
300 concurrent reloads remain untested. The 300-reload comparison above extends
single-worker evidence without establishing unbounded lifetime stability. Do not infer the same saving on a server from a single-worker PSS endpoint.
The longer concurrent run confirms a memory reduction but leaves lifetime growth
unresolved. The interval comparison quantifies one memory-versus-fault tradeoff.
The separate [1,000-reload retention diagnostic](BOOT-TRIM-RETENTION-1000.md)
finds two live application loaders at every checkpoint, slowing code-cache growth
and substantial anonymous-residency fluctuations. It does not establish unbounded
stability or a recycling age. Retain whole-process CPU, page faults, combined PSS
and output checks in further policy comparisons.

This is a HotSpot/glibc policy opportunity, not a Robolectric defect or a reason
to add another Robolectric native-memory API. The existing metrics default, heap
limits, worker recycling behavior and spare-pool trim default remain unchanged.

## Reproduction

Prepare the warmed font cache described in the font-cache benchmark correction,
then use a fresh output directory:

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/sampled-metrics-trim.json \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/sampled-metrics-trim-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --memory
```

For the 300-reload trio (the matrix supplies frozen classpath and warmed-cache paths):

```sh
python3 scripts/experiments/periodic-trim-reloads.py \
  --output daemon/android/build/periodic-trim-reloads-repeat \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar
```

For three alternating concurrent pairs, both sampling every fifth render, with
trimming as the only policy difference:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --output daemon/android/build/periodic-trim-concurrent-repeat \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --cpus 0,1,2,3 --renders 60 --policy trim \
  --font-cache daemon/android/build/bulk-font-cache
```

Repeat the concurrent command with `--renders 300` and a fresh output directory
for the longer comparison.

For the direct interval comparison, use the concurrent command with `--renders 300`,
`--baseline-trim-ms 5000 --candidate-trim-ms 15000`, and a fresh output directory.
The script defaults remain baseline 0 and candidate 5000 for reproducing the
original disabled-versus-five-second study.
