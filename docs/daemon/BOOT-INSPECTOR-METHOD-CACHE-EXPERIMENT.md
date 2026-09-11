# Cache layout inspector method lookup metadata

The earlier semantics-candidate CPU profile includes repeated reflection metadata
copies under `LayoutTreeAccess.findZeroArgMethod`: 4,527 inclusive samples in
`Method.copy`, 3,882 in `Class.getDeclaredMethods`, and 1,430 in `Class.getMethods`.
These counts overlap and predate the subsequent regex and field-access changes;
they identify an experiment, not predicted savings.

The candidate caches each zero-argument method lookup by receiver class and name.
It preserves nearest-declaration-first lookup, superclass traversal, and public
interface fallback. Missing methods are cached; exceptions remain retryable.
Access checks and invocation remain at the call site, and receiver values are
never cached. A `ClassValue` owns each concurrent map so a cached `Method` cannot
turn a global class-key map into an application-classloader retention root.
Lookup stays lazy by name: eagerly scanning the whole hierarchy could encounter
an unrelated linkage failure before returning a valid method from the subclass.

All 243 connector tests pass, including regression tests for declaration order,
overloads, changing receiver values, inherited interface methods, missing methods,
and collection of a disposable loader after both successful and missing lookups.
All 23 focused desktop integration tests also pass. The isolated GC test is
supplemented by the completed real application-reload soak below.

## Measurement protocol

Compare against the full PR #66 inspector candidate, not the older production
baseline. Frozen classpaths have 252 ordered entries and differ only in the
layout-inspector connector jar. Run sequentially with no overlapping builds:

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/layout-method-default.json \
  --classpath daemon/android/build/layout-method-baseline-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/layout-method-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Use the default compiler and G1/1 GiB profile with equal GC logging. Compare
whole-worker CPU, steady-state CPU and latency, readiness, and physical memory;
check every exported product as well as PNG/UIA output. Follow with reduced-heap
application reloads before recommending the cache. The default-profile measurements are recorded below; no memory bound is established. This is our inspector
overhead, not a Robolectric defect.


## Default-profile results

[All three rotated pairs](profiles/inspector-method-cache.json) retain every trial.
All 183 corresponding frames and 2,013 exported artifacts across eleven products
match, with only the existing narrow diagnostic-order and Typeface-identity
normalization. No node IDs or visual values are normalized.

| Median measure | Previous inspector | Method cache |
| --- | --- | --- |
| Whole-worker CPU | 91.37 s | 81.81 s (−10.5%) |
| Steady mean CPU/request | 1014.3 ms | 836.3 ms (−17.5%) |
| Steady median wall/request | 676 ms | 574 ms (−15.1%) |
| Whole-worker wall | 48.168 s | 41.080 s |
| Ready wall | 4400 ms | 4363 ms |
| End PSS | 1019.6 MiB | 1011.5 MiB |

Candidate CPU totals are **111.68, 81.38, 81.81 s**, versus baseline
**91.37, 90.82, 92.60 s**. The first candidate run is a CPU regression and has
192 concurrent mark cycles; the other candidate runs have four each. Keep this
outlier: the median gain does not guarantee lower CPU on every launch. Across
all three runs, mean total CPU is essentially unchanged (91.60 s baseline vs
91.62 s candidate); aggregate CPU savings are not established by this matrix. The
association with GC activity does not alone establish why that run diverged.

Next, repeat with `ParallelGCThreads=2` and `ConcGCThreads=1` equally on both
variants, keeping default compilation. This is a diagnostic comparison, not a
production GC-default change. Then run a 256 MiB application-reload soak with
live-heap and loader-count checkpoints before accepting the cache.


## Equal GC-thread limits

[Three additional rotated pairs](profiles/inspector-method-cache-bounded-gc.json)
use two parallel GC threads and one concurrent GC thread on both variants. All
183 frame pairs and 2,013 data artifacts match. Candidate total CPU is
75.66, 74.82 and 75.65 s; baseline total CPU is 82.10, 104.89 and 106.51 s.
The first pair, with six concurrent mark cycles in each worker, improves CPU by
7.8%. The later baseline workers complete 203 and 198 mark cycles, so the full
matrix's 27.9% median CPU reduction must not be presented as purely the removed
reflection cost. Limiting GC threads does not eliminate cycle-count variability.

Median steady latency is 731 → 585 ms; end PSS is 986.2 → 941.5 MiB. These are
profile-specific observations, not a general physical-memory reduction guarantee.
The default-profile outlier remains part of the evidence. No JVM launch defaults
have changed. The completed 300-reload, 256 MiB Serial/NMT soak is reported below; its
diagnostic timings are not pooled with these performance trials.


## Next GC investigation

The bounded-GC baseline trial 1 records 170 concurrent-start pauses attributed
to `G1 Humongous Allocation`, versus six in baseline trial 0; all use 1 MiB
regions. This is a log observation, not identification of the allocating call
site. [The JDK17 G1 guide](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html)
explains that humongous allocations check the initiating occupancy threshold.
[Its tuning guide](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-garbage-collector-tuning.html)
includes increasing region size as an option for reducing humongous objects.

The queued `scripts/experiments/inspector-g1-regions.json` comparison holds the
candidate bytecode and GC thread counts fixed and varies region size across
1, 4 and 8 MiB. Run only after the reload soak finishes. Measure CPU, mark-cycle
counts, pauses and PSS; retain output parity. Larger regions can change allocation
and collection behavior in other ways, so this is neither a production tuning
recommendation nor proof that image arrays caused the observed cycles. An
allocation profile remains necessary to locate removable allocation work.


## Application reloads at 256 MiB

[The completed soak](profiles/inspector-method-cache-reload-256.json) uses Serial
GC, free-heap ratios 10/30, NMT summary, forced-GC checkpoints every 50 renders,
and live histograms. It replaces the real child-loaded application after every
render, producing 300 distinct application loader IDs. All 301 PNG/UIA pairs
match the preceding inspector candidate. Both variants retain exactly two child
loaders at all seven checkpoints, consistent with the known initial AWT root
plus the current application. The cache adds no observed per-reload loader root.

Candidate live heap in MiB at 0/50/100/150/200/250/300 is
87.81/90.68/91.08/91.48/91.88/80.57/79.76, compared with the baseline's final
79.85 MiB. Candidate PSS is 592.55/800.63/838.92/901.93/908.14/916.36/914.55 MiB;
baseline ends at 885.42 MiB. Heap and loader counts do not establish a physical
memory bound. This single pair does not prove either a general PSS improvement
or a PSS regression attributable to the method cache. Heap, GC and recycling
defaults stay unchanged. NMT and checkpoint overhead preclude timing comparisons
with the uninstrumented matrices; NMT committed memory is not PSS.
