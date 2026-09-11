# Explicit GC with the current bounded Serial heap

`SandboxMeasurement.collect` requests a full GC after every render so it can report
`heapAfterGcMb`. The current 300-reload diagnostic profile contains 303 distinct
`System.gc()` full collections and approximately 41.62 s of logged GC CPU, out of
46.33 s for all logged collections. These rounded GC fields identify work worth
investigating; they are not removable-time estimates or exclusive profile samples.

The [earlier experiment](BOOT-EXPLICIT-GC-EXPERIMENT.md) used a different renderer,
1 GiB G1 heap and default compilation. Its 0.9% CPU difference does not answer the
same question after the pipeline improvements, smaller Serial heap and compiler
limit. This study repeats the flag comparison with the current configuration.

## Configuration and results

Three alternating fresh-worker pairs use the frozen renderer from
[PR #80](https://github.com/yschimke/compose-preview-daemon/pull/80), JDK 17.0.19+10,
Serial GC, Xmx256m, Xms32m, free ratios 10/30 and two compiler threads. Both variants
use the same warmed font cache and offline mode, retaining embedded font output.
Only the candidate adds `-XX:+DisableExplicitGC`. Neither variant uses a different
classpath. Each worker renders red followed by 60 dense-dashboard frames at
480×1200 and density 2.

[The complete result](profiles/serial-explicit-gc.json) includes every trial,
GC summaries and artifact comparisons.

| Metric | Explicit GC | Automatic GC only |
| --- | ---: | ---: |
| Mean total CPU | 53.850 s | 49.197 s (−8.6%) |
| Median total wall | 32.229 s | 27.560 s (−14.5%) |
| Median steady-frame CPU | 570.7 ms | 498.0 ms (−12.7%) |
| Median steady-frame wall | 420.5 ms | 315.0 ms (−25.1%) |
| Median end PSS | 569.66 MiB | 630.41 MiB (+60.76 MiB / 10.7%) |

CPU falls in every pair (53.59→49.52, 54.06→48.85, 53.90→49.22 seconds).
Each explicit worker has 63 forced full collections, accounting for about 6.8–7.1 s
of logged GC CPU. Removing those requests reduces total pause time from roughly
7.8 s to 3.1–3.3 s; automatic collections still do substantial work. The net CPU
saving is smaller than the removed explicit-GC cost. Startup changes are small
and this does not establish a general startup optimization.

All **183 paired frames and 2,013 data artifacts match**, using only the established
diagnostic ordering and Typeface identity normalizations. `heapAfterGcMb` is
intentionally excluded from memory conclusions: with explicit GC disabled it is
an ordinary used-heap reading, not the promised post-GC measurement.

## Smaller-heap pilot

A subsequent single ordered pair compares explicit GC at 256 MiB with automatic
GC at 192 MiB, keeping all other settings unchanged. This is a coupled-policy
pilot, not an isolated measurement of heap size, and is retained separately.

| Metric | Explicit, 256 MiB | Automatic, 192 MiB |
| --- | ---: | ---: |
| Total CPU | 54.170 s | 49.290 s (−9.0%) |
| Total wall | 32.410 s | 27.937 s (−13.8%) |
| End PSS | 544.29 MiB | 628.45 MiB (+84.16 MiB) |

All **61 paired frames and 671 artifacts match** in the
[pilot report](profiles/serial-explicit-gc-192.json). The lower cap does not recover
the measured whole-process memory cost. Do not interpret the two automatic-GC
endpoints from separate studies as a controlled 256-vs-192 comparison: their
baselines also differ in observed residency. Heap capacity, committed heap,
allocator-retained pages, JIT code and total process residency are different things.

## Decision and next step

**No production collector, heap or telemetry default changes.** The CPU saving is
now consistent in this measured workload, but it comes with substantially higher
PSS. Moreover, silently disabling GC would invalidate the meaning of
`heapAfterGcMb`. These results apply to this renderer/JDK/configuration and static
dense captures; no application-reload retention soak was performed for the
no-explicit-GC variants, so they do not establish loader lifetime or a reaping policy.

A production proposal should first separate ordinary used-heap telemetry from an
explicit post-GC measurement, then compare a clearly defined measurement cadence
or idle-time collection policy. It must retain honest metric timestamps/semantics,
measure CPU and concurrent PSS, and validate repeated actual reloads before reducing
collection frequency. Do not call the current eager measurement redundant merely
because it is expensive. No new Robolectric bug is established: the explicit GC
request is in our daemon's measurement code.

## Reproduction

Use PR #80's benchmark harness and frozen classpath. The warmed font cache and
its preparation are documented in the
[font-cache correction](https://github.com/yschimke/compose-preview-daemon/blob/4ce220fdb5ecfafbd66f6b2560c059f8125b9584/docs/daemon/BOOT-FONT-CACHE-BENCHMARK-CORRECTION.md).
The explicit cache argument also works with the prior harness on main.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/serial-explicit-gc.json \
  --classpath daemon/android/build/bulk-argb-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/serial-explicit-gc-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --memory
```

For the pilot use `scripts/experiments/serial-explicit-gc-192.json`,
`--trials 1`, and a fresh output directory. No renderer code changes are needed;
all experimental flags are recorded in the reports and configurations.
