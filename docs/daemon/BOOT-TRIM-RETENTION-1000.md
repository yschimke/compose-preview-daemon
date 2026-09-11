# One thousand reloads with sampled metrics and native trimming

A single-worker diagnostic extends the five-second trim policy to **1,000 actual
application reloads**. Every live-object checkpoint still contains **two application
loaders**. Used heap does not grow monotonically, and code-cache growth slows late
in the run. These observations do not establish an unbounded lifetime guarantee or
a fixed worker-recycling interval.

## Method

Use the same frozen sampled-metrics jars, JDK17.0.19+10, Serial256/Xms32m,
free ratios 10/30, two compiler threads, every-fifth metrics and warmed offline
fonts as the [trim study](BOOT-PERIODIC-NATIVE-TRIM.md). Render the complex reload
dashboard at 480×1200, density 2, swapping its application loader on every request.
Trim every 5000 ms. Add NMT detail and diagnostic checkpoints every 100 reloads,
including warm-up and the final reload. Each checkpoint forces GC, obtains a live
histogram and classloader statistics, then captures NMT and Linux smaps.

This is **not a timing benchmark**. Forced collection and diagnostic snapshots
change the workload; loader counts establish collectibility at those checkpoints,
not natural collection timing. Snapshots are sequential, and trimming remains
active during collection. Heap used is read before the histogram's additional GC;
NMT commitment and smaps residency are different measurements.

The [report](profiles/periodic-trim-retention-1000.json) retains checkpoint inputs,
NMT categories, address-correlated residency, heap readings, loader counts and
launch flags. Only mappings wholly contained in one NMT reservation receive that
category. Unmatched anonymous mappings can include JVM malloc, third-party
allocations and allocator free pages; they are not labelled a native leak.

## Observations

All memory values below are MiB. PSS is the sum of smaps entries and may differ
slightly from smaps_rollup because of rounding and sequential collection.

| Reload | Live app loaders | Used heap | Total PSS | Heap-region PSS | Code-region PSS | Metaspace-region PSS | Unmatched anonymous PSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 2 | 86.59 | 440.20 | 134.64 | 18.50 | 91.30 | 118.52 |
| 100 | 2 | 92.79 | 617.09 | 193.23 | 73.04 | 103.26 | 168.69 |
| 200 | 2 | 99.81 | 620.02 | 193.23 | 85.83 | 104.81 | 157.24 |
| 300 | 2 | 100.61 | 637.38 | 193.23 | 94.95 | 106.14 | 164.09 |
| 400 | 2 | 90.98 | 578.18 | 192.22 | 101.02 | 106.57 | 99.39 |
| 500 | 2 | 82.84 | 633.91 | 190.54 | 104.46 | 106.59 | 153.29 |
| 600 | 2 | 90.04 | 659.98 | 190.54 | 109.50 | 107.46 | 173.45 |
| 700 | 2 | 89.97 | 643.11 | 190.54 | 110.73 | 107.48 | 155.33 |
| 800 | 2 | 92.03 | 658.50 | 190.54 | 112.57 | 107.48 | 168.86 |
| 900 | 2 | 91.58 | 643.39 | 190.54 | 113.91 | 107.48 | 152.34 |
| 1000 | 2 | 93.15 | 590.64 | 190.54 | 114.32 | 107.48 | 99.16 |

Code-region residency grows 21.91 MiB between reloads 100 and 300, but only
4.82 MiB from 600 to 1000. This supports JIT warm-up as a contributor to the
earlier upward trend. Metaspace-region residency is nearly unchanged after 600;
heap-region residency is unchanged after 500 despite fluctuating live heap.

Total PSS fluctuates substantially late in the run. Its 659.98→590.64 MiB change
between reloads 600 and 1000 accompanies a 173.45→99.16 MiB change in unmatched
anonymous residency, while code residency rises. This locates most of that sampled
variation; it does not identify the allocation owner or prove that one particular
trim caused it. The low final endpoint must not be treated as a durable floor.

All **1,001 PNG/UIA outputs** match the corresponding warm-up or deterministic
application frame from the prior concurrent run, and final SVG bytes match. The
run has 1,000 distinct application-loader identities. Font-cache hashes are
unchanged. Reused paths do not retain historical other exported artifacts.

## Decision

No growing application-loader leak is demonstrated through 1,000 reloads under
these diagnostic conditions. The bounded initial loader remains a separate
AWT/TCCL ownership investigation. Do not choose a recycling age from these data,
claim ordinary Robolectric tests leak, or change the upstream R09 priority from P3.

There is still a meaningful gap between used heap and heap residency. A smaller
heap with this same sampled-metrics/trim profile is an experiment to compare for
CPU, allocation pressure and complex-screen headroom, not a default recommendation.
The subsequent [concurrent 192-versus-256 MiB comparison](BOOT-SAMPLED-METRICS-HEAP.md)
finds only a modest aggregate residency reduction, with one paired reversal and
essentially unchanged CPU; it does not extend this 1,000-reload lifetime result
to the smaller heap. The code cache is still growing slowly; it is neither proven minimal nor safe to
cap aggressively. No runtime defaults change.

## Reproduction

Use a fresh output directory and the previously prepared frozen jars/font cache:

```sh
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/periodic-trim-retention-1000-repeat \
  --renders 1000 --fixture ReloadDashboardPreview \
  --class-name benchmark.screens.ReloadDashboardPreviewsKt \
  --user-class-dir daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --swap-every 1 --reuse-output --width 480 --height 1200 --density 2 --memory \
  --gc-checkpoint-every 100 --heap-histograms --native-memory-checkpoints \
  --jvm-arg=-Xmx256m --jvm-arg=-Xms32m --jvm-arg=-XX:+UseSerialGC \
  --jvm-arg=-XX:MinHeapFreeRatio=10 --jvm-arg=-XX:MaxHeapFreeRatio=30 \
  --jvm-arg=-XX:CICompilerCount=2 --jvm-arg=-XX:NativeMemoryTracking=detail \
  --jvm-arg=-Dcomposeai.daemon.metrics.everyRenders=5 \
  --jvm-arg=-Dcomposeai.fonts.cacheDir=daemon/android/build/bulk-font-cache \
  --jvm-arg=-Dcomposeai.fonts.offline=true \
  --jvm-arg=-XX:TrimNativeHeapInterval=5000 \
  '--jvm-arg=-Xlog:gc*=info' --jvm-arg=-Xlog:trimnative=debug
```

`scripts/summarize-native-memory.py OUTPUT_DIRECTORY` correlates the recorded
NMT/smaps snapshots. Live application-loader counts come from the exact
`UserClassLoaderHolder$ChildFirstURLClassLoader` histogram entry at each checkpoint.
