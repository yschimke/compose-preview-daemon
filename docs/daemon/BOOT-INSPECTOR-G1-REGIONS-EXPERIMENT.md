# G1 region size and inspector allocation costs

The method-cache experiment exposed intermittent concurrent-mark activity: one
baseline worker with two parallel GC threads and one concurrent GC thread logged
170 concurrent starts attributed to `G1 Humongous Allocation`, compared with six
in a quiet baseline worker. Both used 1 MiB regions. This identifies a GC trigger,
not the allocating code or the cause of run-to-run variation.

The [JDK17 G1 guide](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html)
describes occupancy checks at humongous allocation; its
[tuning guide](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-garbage-collector-tuning.html)
suggests larger regions as one option to reduce humongous objects.

## Matched trials

Run the method-cache candidate with default JDK17 compilation, G1/1 GiB,
`ParallelGCThreads=2`, `ConcGCThreads=1`, equal GC logging, and only region size
varying. Three rotated trials each render red plus 60 DenseDashboardPreview
frames at 480×1200, density 2. No local builds overlap the measurements.

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/inspector-g1-regions.json \
  --classpath daemon/android/build/layout-method-candidate-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/inspector-g1-regions \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

[Every trial and artifact comparison](profiles/inspector-g1-regions.json) is
retained. The six comparisons against 1 MiB check 366 frame pairs and 4,026 data
artifacts across eleven products; all match, allowing only the existing narrow
semantics diagnostic ordering and Typeface identity normalization.

| Median measure | 1 MiB | 4 MiB | 8 MiB |
| --- | --- | --- | --- |
| Total CPU | 75.53 s | 74.96 s | 75.14 s |
| Steady mean CPU/request | 746.0 ms | 732.3 ms | 723.0 ms |
| Steady median wall/request | 595 ms | 586 ms | 601.5 ms |
| Whole-worker wall | 45.907 s | 43.081 s | 51.596 s |
| Concurrent mark cycles | 6 | 7 | 7 |
| Humongous-allocation concurrent starts | 11 | 1 | 0 |
| End PSS | 975.4 MiB | 973.0 MiB | 979.4 MiB |

Larger regions reduce the named large-allocation trigger but do not eliminate
mark cycles. Whole-worker CPU changes by less than 1%, and physical memory stays
similar. No run in this matrix reproduces the earlier roughly 200-cycle case,
so it does not prove larger regions prevent that outlier. The 8 MiB whole-worker
wall result also argues against choosing a setting from steady CPU alone.
**Keep the existing region-size default.** The next useful experiment is an
allocation profile identifying call sites and object sizes; reducing avoidable
allocation may improve CPU across collector configurations.


## Allocation profile and next implementation targets

[An allocation-only profile](profiles/inspector-allocation-profile.json) of one
red plus 36 dense renders uses async-profiler 4.5 at a 512 KiB sampling interval.
The report filters events after worker readiness using the absolute recorded
start time plus readiness duration. Sample weights estimate allocation traffic;
they are not live heap, exact allocation totals, or a CPU attribution. Inclusive
categories overlap and must not be added. Instrumented timings are excluded.

The sampled workload accounts for about 5,848 MiB of allocation weight. Inclusive
modifier inspection accounts for 2,308 MiB; frame settling for 1,972 MiB, including
801 MiB under PNG decoding. The fixed-axis resize helper accounts for another
302 MiB. These results shift the next experiments toward removing allocation:

1. Cache declared-field metadata with class lifetime ownership, retaining dynamic
   field values and access checks. Repeated `Class.getDeclaredFields` copies show
   up prominently; validate unloading and real reloads as for the method cache.
2. Avoid fully decoding an already correctly sized PNG just to check dimensions.
   Both daemon and standalone resize helpers currently decode before their
   no-op size check. Preserve decode/resize behavior when dimensions differ.
3. Reuse pixel comparison buffers within a single settling operation where frame
   dimensions allow it. Preserve every capture, comparison, frame advance and
   settling criterion; do not replace exact pixels with a lossy/hash shortcut.

The profile identifies allocation opportunities, not demonstrated speedups. Each
needs isolated measurement and output checks before adoption. The runtime and
launch defaults are unchanged by this investigation.
