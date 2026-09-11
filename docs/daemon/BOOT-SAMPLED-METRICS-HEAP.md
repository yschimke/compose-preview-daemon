# Smaller heap with sampled metrics and native trimming

Reducing the maximum heap from 256 to 192 MiB gives a **modest, inconsistent
whole-process memory reduction** in this workload. Across three alternating
concurrent pairs, median observed peak combined PSS falls 34.95 MiB (2.6%), with
essentially unchanged CPU and elapsed time. One pair's memory increases. This
result does not justify reducing the production default or claiming 64 MiB of
resident savings per worker from a 64 MiB lower heap limit.

## Controlled comparison

Each group contains two workers sharing CPUs 0,1,2,3 (distinct physical cores),
each completing 300 actual application reloads of the complex dashboard at
480×1200, density 2. Both variants use the same frozen sampled-metrics jars,
JDK17.0.19+10, Serial GC, Xms32m, free ratios 10/30, two compiler threads,
every-fifth metrics, native trim every 15000 ms and warmed offline font cache.
Only Xmx changes. Affinity is verified; cores are not exclusive. No other local
benchmark or build overlaps the comparison.

There are no additional GC checkpoints, histograms, NMT or profilers. GC logging
is equal on both sides. The [report](profiles/heap192-trim-concurrent-300.json)
retains all groups, launch flags, per-worker GC summaries, heap readings, reload
windows, actual trim events and combined-PSS observations.

| Metric | Xmx256m | Xmx192m |
| --- | ---: | ---: |
| Mean combined CPU | 296.783 s | 295.823 s |
| Median group elapsed | 114.662 s | 114.635 s |
| Median observed peak combined PSS | 1329.79 MiB | 1294.84 MiB |
| Median of last-30-second median combined PSS | 1242.34 MiB | 1221.04 MiB |
| Mean minor page faults | 430,695 | 431,923 |
| Mean logged GC pauses per worker | 925.83 | 945.33 |
| Mean summed GC pause time per worker | 17.223 s | 17.334 s |

CPU changes **-0.32%**, elapsed **-0.02%** and minor faults **+0.29%**. Those small
differences do not establish a meaningful performance change. GC counts rise about
2.1%, while summed pauses rise about 0.65%; logs cover the whole worker lifetime,
including startup. They are not an exclusive CPU attribution.

Paired observed peak PSS is 1320.87→1340.66, 1329.79→1294.84 and
1345.23→1291.02 MiB. The reported 34.95 MiB saving is the difference of aggregate
medians, not a promise per pair or per worker. Combined PSS is sampled sequentially
from both live workers roughly once per second, so its maximum is not an exact
or atomic peak. Group elapsed includes startup, shutdown and polling; process CPU
ends at workload completion. Late-window PSS is a separate measurement.

All **1,806 paired PNG/UIA frames** and final SVG bytes match. Each worker uses
300 distinct application-loader identities. Metric cadence, expected heap and trim
flags, affinity and unchanged font-cache hashes pass. No frame exceeds two seconds.
The reused output paths do not retain historical other exported artifacts. This
is not a live-loader census or a test of unbounded lifetime.

## Decision

The 192 MiB limit handled this complex screen repeatedly, with little measured
CPU cost. It offers a small aggregate residency improvement here, not a general
headroom guarantee for larger screens, different applications or held sessions.
Keep defaults unchanged. The earlier [1,000-reload diagnostic](BOOT-TRIM-RETENTION-1000.md)
used 256 MiB; its lifetime evidence must not be relabelled as a 192 MiB soak.

The next code-level opportunity is to reduce allocation itself. Reassess the
archived modifier-field metadata cache under the current compiler/renderer/cache
settings, retaining output and loader-lifetime checks and the earlier G1 regression
as a required concern rather than treating allocation reduction as a CPU win.

## Reproduction

After preparing the frozen classpath and warmed font cache, use a fresh directory:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --output daemon/android/build/heap192-trim-concurrent-repeat \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --cpus 0,1,2,3 --renders 300 --policy heap \
  --font-cache daemon/android/build/bulk-font-cache
```

The heap policy fixes every-fifth measurement and fifteen-second trimming equally
on both sides. Other experiment policies and production settings are unchanged.
