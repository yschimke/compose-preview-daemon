# Arena limits with concurrent CPU-constrained workers

Two concurrent workers sharing two CPUs show **no consistent substantial memory
or CPU benefit** from `MALLOC_ARENA_MAX=2`. Across three alternating group pairs,
median observed combined peak PSS changes **1233.82 → 1223.67 MiB** (0.8% lower),
and mean aggregate process CPU changes **123.577 → 123.387 s** (0.15% lower).
One pair reverses the memory result. Keep production allocator settings unchanged.

This qualifies the [single-worker result](BOOT-ALLOCATOR-ARENAS-EXPERIMENT.md),
where median end PSS fell 16.5%, and the
[350-reload diagnostics](BOOT-ALLOCATOR-REAL-RELOADS.md). Those measurements remain
valid for their respective profiles; their savings do not transfer automatically
to a CPU-constrained concurrent configuration.

## Workload and evidence

Three default/arena2 group pairs, alternating order. Each group launches two
fresh workers, both restricted with `taskset -c 0,1`; these logical CPUs are on
different physical cores on this host. Actual live-worker affinity was also
checked through `sched_getaffinity`. CPUs are not exclusively reserved and
background host load is uncontrolled. No other local benchmarks/builds overlap.

Each worker renders a red fixture plus 60 `ReloadDashboardPreview` screens with
loader replacement after every application render. Each saved summary is checked
for `swapEvery=1`, 61 output frames and 60 distinct application loader identities.
All 366 paired PNG/UIA frames match across 12 workers. No diagnostic GC checkpoints,
NMT or profiler are enabled. Heap and classpaths match the preceding experiment:
JDK 17.0.19, glibc 2.44, 256 MiB maximum / 32 MiB initial heap, Serial GC with
free ratios 10/30, frozen final in-memory-settling jars, 480×1200 density 2.

| Trial | Variant | Aggregate CPU s | Group elapsed s | Observed concurrent peak PSS MiB | Minor faults | Major faults |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 0 | default | 123.22 | 76.813 | 1233.82 | 596895 | 2 |
| 0 | arena2 | 122.40 | 76.926 | 1224.81 | 326417 | 3 |
| 1 | arena2 | 124.59 | 66.834 | 1223.67 | 296506 | 3 |
| 1 | default | 125.98 | 67.856 | 1194.03 | 289782 | 2 |
| 2 | default | 121.53 | 66.787 | 1243.35 | 298755 | 0 |
| 2 | arena2 | 123.17 | 66.773 | 1174.98 | 269189 | 4 |

PSS is read sequentially for both live workers about once per second, then summed;
this avoids adding independent end snapshots but is neither atomic nor an exact
peak. The report retains every sample. CPU is the sum of each worker's process
CPU through workload end, excluding shutdown. Group elapsed includes startup,
shutdown and up to one polling interval. Median group elapsed improves 1.5%, but
the first pair is around 10 s slower for both variants; do not call this a stable
throughput win. Fault counts also vary substantially, particularly the first
default group. All trials are retained in
[the report](profiles/allocator-concurrent.json).

Affinity changes the JVM's available execution capacity and may influence thread
and arena creation. This experiment does not isolate which mechanism explains the
smaller memory difference, nor measure actual allocator arena counts. It also
uses 60 reloads per worker, not the prior diagnostic's 350. Do not attribute the
difference solely to concurrency or conclude arena limits never help loaded servers.

## Reproduction

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --classpath daemon/android/build/in-memory-settling-final-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java --cpus 0,1 --renders 60 \
  --output daemon/android/build/allocator-concurrent-reproduction
```

Select two allowed CPUs and record topology. Use a fresh output directory; rebuild
and freeze the documented jars if the local frozen artifacts are unavailable.
The runner rejects inherited allocator overrides, scopes candidate environment
to child processes, waits for each harness to clean up its worker, validates
loader identity and output parity, and persists each completed group.

## Decision and remaining work

Do not enable a global arena limit based on the earlier single-worker saving.
Before choosing a production profile, measure longer concurrent reloads and
native-image-heavy content, then separate CPU affinity/available-processor effects
from concurrency. Periodic trimming still needs sustained CPU, fault and memory
measurements. The existing experiments establish neither a reaping threshold nor
an indefinite resource bound.

This remains local glibc/JVM tuning, not evidence of a Robolectric defect. Upstream
R09 stays P3; ordinary unit/screenshot workloads have not been benchmarked.
