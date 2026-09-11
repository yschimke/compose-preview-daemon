# Allocator-retained memory after application reloads

A diagnostic `malloc_trim(0)` after 300 actual application reloads reclaimed
**182.90 MiB PSS** in **13.19 ms**. Fifty further reloads succeeded with matching
PNG/UIA output. This establishes reclaimable allocator pages in this worker; it
does not establish a leak, Robolectric ownership, an optimal trim interval, or a
production recycling policy. No production allocator/worker policy changes here.

## Evidence

Same frozen final in-memory-settling jars as
[the native-memory investigation](BOOT-NATIVE-MEMORY-CHECKPOINTS.md): JDK 17,
`-Xmx256m -Xms32m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10
-XX:MaxHeapFreeRatio=30 -XX:NativeMemoryTracking=detail`, 480×1200 density 2,
`ReloadDashboardPreview`, a new application loader per render. Forced GC,
histograms and NMT/smaps snapshots every 50 renders. This is an instrumented
single-run diagnostic, not a throughput benchmark. Background JVM work can
continue between sequential snapshots.

| Point | PSS MiB |
| --- | ---: |
| Initial red frame | 534.51 |
| 50 reloads | 764.89 |
| 150 reloads | 831.55 |
| 250 reloads | 829.17 |
| 300, immediately before trim | 842.60 |
| Immediately after trim | 659.70 |
| 350, after another 50 reloads | 704.79 |

Anonymous mappings outside NMT reservations fell **393.20 → 210.41 MiB**.
Java heap (**162.44 MiB**), code (**116.64 MiB**) and metaspace (**108.45 MiB**)
PSS were unchanged across trim. The allocator reported 24 arenas; its top-level
`rest` free-space statistic was about 300 MiB before and after. Allocator-managed
address space barely changed (~399 MiB), consistent with reclaiming resident
pages without releasing most address ranges. These statistics measure different
things: allocator free bytes are not resident bytes, and neither equals NMT
commitment. Do not subtract them to infer a precise native live set.

All first 301 PNG/UIA hashes match the earlier untrimmed small-initial-heap run;
the 50 post-trim dashboard frames match its final dashboard. The benchmark
verified each requested loader swap changed identity. Full data is in
[the derived report](profiles/allocator-trim-reload.json); raw local snapshots
are under `daemon/android/build/allocator-trim-reload`.

## Reproduction

The archived [probe](../../scripts/experiments/allocator-trim-probe.c) uses
[JDK 17's attach entrypoint](https://docs.oracle.com/en/java/javase/17/docs/specs/jvmti.html)
and the existing `jcmd JVMTI.agent_load` command, verified with the running VM's
help. It does not install JVMTI events or replace allocation functions. Attach
only to the disposable benchmark worker. The probe writes allocator XML before
and after trimming and a monotonic duration/result file; failed writes reject
attachment. An initial three-render smoke test completed first.

Compile on glibc Linux (adjust JDK include paths):

```sh
gcc -Wall -Wextra -Werror -O2 -fPIC -shared \
  -I/usr/lib/jvm/java-17-openjdk/include \
  -I/usr/lib/jvm/java-17-openjdk/include/linux \
  scripts/experiments/allocator-trim-probe.c -o /tmp/liballocator-probe.so
git apply scripts/experiments/allocator-trim-probe.patch
ALLOCATOR_PROBE_LIBRARY=/tmp/liballocator-probe.so TRIM_CHECKPOINT=300 \
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/in-memory-settling-final-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java --renders 350 \
  --fixture ReloadDashboardPreview \
  --class-name benchmark.screens.ReloadDashboardPreviewsKt \
  --user-class-dir daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --swap-every 1 --reuse-output --gc-checkpoint-every 50 --heap-histograms \
  --native-memory-checkpoints --width 480 --height 1200 --density 2 --memory \
  --jvm-arg=-Xmx256m --jvm-arg=-Xms32m --jvm-arg=-XX:+UseSerialGC \
  --jvm-arg=-XX:MinHeapFreeRatio=10 --jvm-arg=-XX:MaxHeapFreeRatio=30 \
  --jvm-arg=-XX:NativeMemoryTracking=detail \
  --output daemon/android/build/allocator-trim-reproduction
git apply -R scripts/experiments/allocator-trim-probe.patch
```

The patch is diagnostic-only and is not applied to the committed harness.
Use a fresh output directory. Rebuild/freeze the documented classpath if those
local jars are unavailable; retain hashes for any comparison.

According to the Linux man-pages, [malloc_trim](https://www.man7.org/linux/man-pages/man3/malloc_trim.3.html)
can release whole free pages across arenas, while
[malloc_info](https://www.man7.org/linux/man-pages/man3/malloc_info.3.html) reports
all arenas. Trimming cannot prove all remaining allocations are necessary and
can trade lower residency for subsequent page faults and CPU work.

## Decision and next experiments

Prefer investigating allocator retention before using worker age as a proxy for
leaks. Compare allocator arena limits and trim policy in matched repeated runs,
measure total CPU/page faults plus PSS, and include native-image-heavy screens
and concurrent workers. A one-time successful trim is insufficient for a
production policy. The observed partial rebound (45 MiB in 50 renders) is a
specific next measurement, not a lifetime bound.

Upstream feedback R09 stays **P3**: this is demonstrated glibc reclamation, not a
Robolectric defect. Long-lived unit/screenshot JVMs can encounter the same
allocator mechanism, but the size and tradeoffs have only been measured in our
application-reloading daemon.
