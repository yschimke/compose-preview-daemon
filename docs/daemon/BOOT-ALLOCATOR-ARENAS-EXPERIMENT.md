# Limiting allocator arenas during actual reloads

`MALLOC_ARENA_MAX=2` reduced median end-of-workload PSS **774.88 → 647.18 MiB**
(**127.70 MiB / 16.5%**) in three alternating fresh-worker pairs. Mean process CPU
increased **1.1%**. This is a promising memory tradeoff, not an unconditional
speedup. No production allocator setting changes here.

This follows the [allocator trim diagnostic](BOOT-ALLOCATOR-TRIM-EXPERIMENT.md),
which established that a substantial part of the worker's anonymous residency
was reclaimable. Arena limits can reduce allocator retention but also change
contention and page-fault behavior. The [glibc manual](https://sourceware.org/glibc/manual/latest/html_node/Malloc-Tunable-Parameters.html)
documents the process-start environment control. This experiment uses glibc
**2.44**, OpenJDK **17.0.19+10**, and the same frozen final in-memory-settling jars
as [the memory checkpoints](BOOT-NATIVE-MEMORY-CHECKPOINTS.md).

## Measurements

Each fresh worker renders a red readiness fixture and 100 real child-loaded
`ReloadDashboardPreview` screens, replacing the application loader between
renders. All 100 application loader identities differ in every worker. Dimensions
are 480×1200 at density 2. Both variants use `-Xmx256m -Xms32m -XX:+UseSerialGC
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`. Only the allocator environment
variable differs. No NMT, heap histograms, diagnostic forced-GC checkpoints or
profiler; no concurrent local builds or other benchmark workers. Normal renderer
behavior is unchanged. The order is default/arena2, arena2/default, default/arena2.

| Trial | Default CPU s | Two arenas CPU s | Default PSS MiB | Two arenas PSS MiB | Default minor faults | Two arenas minor faults |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 92.01 | 91.11 | 797.49 | 647.18 | 224019 | 134094 |
| 1 | 90.79 | 93.21 | 774.88 | 666.10 | 213370 | 144982 |
| 2 | 91.73 | 93.25 | 771.51 | 642.53 | 215199 | 278590 |

Mean CPU is **91.510 → 92.523 s**. Median whole-workload wall time is
**60.012 → 60.328 s** (+0.5%); the third candidate takes **63.116 s**. Median
last-30 mean render CPU is **580.33 → 564.33 ms**, while median last-30 wall
latency is **519 → 520 ms**. A steady-render improvement does not erase the
whole-process CPU increase.

Mean minor faults fall 14.5%, but the third candidate exceeds all default runs;
there is no consistent per-trial fault reduction. All major fault counts are zero.
Faults are [process totals](https://www.man7.org/linux/man-pages/man5/proc_pid_stat.5.html)
from launch through workload end, excluding child-process counters and shutdown.
They are not bytes read or a direct allocation count.

All 101-frame PNG/UIA sequences match across the six workers (303 paired frames).
Raw rows, including the slower/high-fault candidate, are retained in
[the report](profiles/allocator-arenas-reload.json). This run does not measure
post-GC live heap, prove loader reclamation, or establish a lifetime memory bound.

## Reproduce

The harness records allocator overrides and faults in each worker summary. The
experiment runner rejects inherited allocator overrides so the baseline cannot
silently inherit the candidate setting. It scopes the setting to its child
processes and requires full per-frame PNG/UIA parity before accepting a row.

```sh
python3 scripts/experiments/benchmark-allocator-arenas.py \
  --classpath daemon/android/build/in-memory-settling-final-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --renders 100 \
  --output daemon/android/build/allocator-arenas-reproduction
```

Use a fresh directory. The frozen classpaths are local artifacts; rebuild and
freeze them following the linked experiment when unavailable, retaining input
hashes. Six sequential runs completed and both Python scripts passed compilation.
The archived trim-probe patch was refreshed and checked against the updated
harness, but is not applied.

## Decision

Keep the production launch settings unchanged while extending the promising
memory result to 300+ reloads with live-heap/allocator checkpoints, then concurrent
workers and more native-image-heavy content. Compare an intermediate arena limit
and periodic trimming before choosing a policy. The third trial's faults and wall
time make workload-specific validation necessary; do not infer that two arenas
is optimal for a CPU-loaded server from a single-worker matrix.

This is glibc/JVM launch tuning, not a demonstrated Robolectric bug. It may also
benefit long-lived unit/screenshot forks, but that applicability remains unmeasured.
R09 stays P3 as upstream feedback; local memory optimization remains worthwhile.
