# Cache modifier field metadata

**Status: deferred; no production runtime change.** The measured array variant
and its tests are preserved in [the experiment patch](../../scripts/experiments/inspector-field-cache.patch),
which applies to commit `7bc8561e` (the method-cache candidate).

The [allocation profile](profiles/inspector-allocation-profile.json) after worker
readiness attributes about 923 MiB of sampled allocation weight to
`Class.getDeclaredFields` beneath `ModifierTokenResolver` during one red and 36
dense renders. This is sampled allocation traffic, not live memory or predicted
CPU savings. The profile uses the preceding method-cache candidate.

The candidate reuses declared-field metadata at the eight enumeration sites in
`ModifierTokenResolver`. A `ClassValue` owns a shared metadata array for each class;
no global class-key map, instance, or field value is retained. Existing filters,
field order, superclass traversal, access checks, invocation and exception
fallbacks remain at their original call sites. Failed metadata computation is
not cached. There is no eager enumeration of a superclass that the caller would
otherwise never visit.

All 245 connector tests pass, including shared metadata with changing receiver
values, separate parent declarations and collection of a
disposable loader after its cached field was made accessible. A reachability
fence keeps the cache alive during the collection assertion. This focused test
must be supplemented by a real application-reload soak.

## Measurement protocol

The frozen method-cache baseline and candidate have 252 ordered classpath
entries; only the inspector connector jar differs. Run sequentially with no
builds or other benchmarks in parallel:

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/inspector-field-default.json \
  --classpath daemon/android/build/layout-method-candidate-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/inspector-field-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Keep default compilation and G1/1 GiB, with equal GC logging. Retain every trial,
including concurrent-mark outliers, and compare CPU, wall time, physical memory
and every exported artifact. Follow with allocation profiling and a 256 MiB
application-reload soak before accepting the change. Performance and real-reload
validation are still pending; no launch defaults change.


## Initial list-cache results

[All three default-profile pairs](profiles/inspector-field-cache.json) match 183
frame pairs and 2,013 data artifacts across eleven products. Median total CPU is
81.40 → 81.37 s (essentially unchanged); steady mean CPU is 822.7 → 829.7 ms and
steady median latency 561.5 → 552.5 ms. End PSS is 974.1 → 973.6 MiB. These results
do not establish a CPU or physical-memory saving. The allocation-only candidate
profile is running separately to determine whether the intended field-copy
traffic was actually removed. The cache remains experimental.


## Allocation evidence and revised array candidate

[The matched allocation profiles](profiles/inspector-field-allocation-comparison.json)
show sampled workload allocation declining from 5,848 to 4,964 MiB (about 15%).
The roughly 923 MiB attributed to modifier field enumeration disappears.
These sampled weights measure allocation traffic, not retained memory. The list
variant introduces about 35 MiB beneath `UnmodifiableCollection` and increases
`ArrayList.iterator` weight from 23 to 64.5 MiB; inclusive categories may overlap.

The revised candidate retains the original array-based iteration instead of
wrapping field metadata in a list. The cache remains internal; callers read the
shared array and must not replace its entries. Existing call sites only inspect
or derive filtered collections from it. All 245 connector tests pass again.
The new frozen classpath differs from the method-cache baseline only in the
connector jar. `inspector-field-array-default.json` compares this revision
against the unchanged method-cache baseline; the original list measurements
remain above and are not relabeled as array measurements.


## Array-cache timing results

[The fresh array comparison](profiles/inspector-field-array-cache.json) passes
all 183 frame pairs and 2,013 exported artifacts. Median total CPU is
82.76 → 81.98 s (0.9% lower), steady CPU 842.7 → 842.0 ms, and steady latency
564 → 562 ms. Whole-worker wall is 41.302 → 41.715 s and end PSS 1024.6 →
1009.0 MiB. These small, mixed timing differences do not establish a meaningful
speedup over the method-cache baseline, nor an advantage over the earlier list
variant. Allocation profiling and reload checks remain separate acceptance
criteria; do not advertise the 15% sampled allocation reduction as a CPU gain.


**Retained regression:** candidate total CPU is 81.38, 81.98 and **133.03 s**,
versus baseline 83.16, 82.76 and 80.56 s. Mean total CPU therefore rises from
82.16 to 98.80 s (20.2%). The small median difference must not hide this tail.
The cache is not accepted on these measurements. A paired Serial-GC diagnostic
can separate the recurring G1 concurrent-mark variability from other overhead;
it will not replace these production-profile results or change launch defaults.


## Serial diagnostic and decision

[Three 256 MiB Serial-GC pairs](profiles/inspector-field-array-serial.json), with
free-heap ratios 10/30 and default compilation, pass all 183 frame pairs and
2,013 data artifacts. Median total CPU is 71.01 → 70.42 s (0.8% lower), steady
CPU 695.3 → 685.7 ms, and steady latency 614.5 → 608.5 ms. End PSS is
755.5 → 747.3 MiB. This is a small profile-specific benefit, not a justification
for changing collectors or claiming a reliable physical-memory reduction.

The final array allocation profile records 4,884 MiB sampled allocation weight,
16.5% below the method-cache baseline's 5,848 MiB; iterator traffic returns near
baseline and the targeted field-copy traffic disappears. That reduction is real
within the sampled workload, but neither default-profile CPU nor resident memory
shows a sufficiently convincing benefit to adopt the cache now. The G1 tail
regression remains visible, not explained away by the Serial diagnostic.

Defer production adoption and investigate the higher-cost redundant PNG decode
next. The runtime and test additions have been removed after archiving the exact
array patch; reverse-check and clean reapplication checks both pass. A real
application-reload soak was not run for this deferred change, so only the focused
disposable-loader test supports its lifetime design. Revisit if a representative
loaded-server workload demonstrates material benefit; do not claim the field
cache has completed production validation.


## Revisit with the current renderer and warmed fonts

The archived array patch was reapplied after in-memory settling, bulk ARGB
snapshots, two-compiler-thread launch defaults and sampled metrics were available.
The 29 focused cache/modifier tests pass, including mutable-value reads and
collection of a disposable loader with cached accessible fields. The frozen
candidate differs from the sampled-metrics baseline only at connector classpath
entry 21; changed jar entries are the field cache and modifier resolver classes
(including two helper classes whose source positions moved).

Three separate comparisons use the same warmed offline font cache and preserve
all output. They answer different questions and must not be combined as one
universal effect:

| Profile | Baseline mean CPU | Candidate mean CPU | Other result |
| --- | ---: | ---: | --- |
| Two workers / four CPUs, Serial256/Xms32, metrics every 5, trim every 15 s; 60 actual reloads each | 106.030 s | 104.507 s | CPU -1.4%; median group elapsed -0.06%; median observed peak combined PSS 1235.55→1252.08 MiB (+1.3%). |
| Single worker, G1/Xmx1g, compiler count 2, every-render metrics, no trim; 60 dense frames | 62.523 s | 61.767 s | CPU -1.2%; median total wall 28.426→28.214 s; median end PSS 781.07→770.38 MiB. |
| Same G1 workload with the JVM's default compiler count | 72.937 s | 81.827 s | CPU +12.2%; median total wall 28.641→28.809 s; median end PSS 971.43→988.02 MiB. |

The [concurrent report](profiles/field-cache-revisit-concurrent.json) verifies
366 paired PNG/UIA frames, 60 distinct application loaders per worker, final SVG
parity, matching JVM policies, CPU affinity and unchanged fonts. Combined PSS is
sampled sequentially once per second and is not an exact peak. The reused output
paths do not preserve historical other artifacts or provide a live-loader census.

The [two-compiler G1 report](profiles/field-cache-revisit-g1.json) and
[default-compiler G1 report](profiles/field-cache-revisit-g1-default-compiler.json)
each verify 183 paired frames and 2,013 data artifacts, with only the existing
semantics diagnostic-order and Typeface-identity normalizations. One two-compiler
CPU pair regresses slightly despite the favorable aggregate. Under the default
compiler setting, **all three candidate CPU totals are higher**: 73.94→97.54,
72.58→74.53 and 72.29→73.41 seconds. The large first-pair regression is not hidden
by the almost-flat median wall time or favorable steady-frame medians.

In that slow candidate, G1 humongous-allocation concurrent starts increase from
42 to 178, and remark/cleanup counts from seven each to 177 each. Summed rounded
GC CPU log fields increase from 16.77 to 22.79 seconds. This is evidence of
substantially different GC activity, not a complete attribution of the 23.60-second
CPU increase: these log fields do not account for all concurrent CPU work. No
claim that the cache reduces allocation in this revised workload is made without
a fresh allocation profile; the earlier allocation result retains its own scope.

**Decision: keep the field cache deferred and remove the reapplied runtime/test
prototype again.** The small current tuned-profile gains do not justify an
unconditional change with a reproduced default-compiler CPU regression. The
original archived patch and new frozen/report artifacts preserve the experiment.
No new real-runtime lifetime soak is claimed; only focused loader collection and
the concurrent runs' distinct loader identities have been verified for this cache.

Next investigate allocations of the full-frame settling snapshots, which directly
create large arrays, while preserving every sample, comparison and final PNG
validation. Do not reintroduce the field cache merely because it copies fewer
reflection objects.

### Reproducing the revisit

Reapply the archived patch, format it, run the focused tests and freeze a candidate
classpath with only the connector jar replaced. The comparison helper accepts a
candidate classpath while keeping JVM policies equal:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --output daemon/android/build/field-cache-revisit-concurrent-repeat \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --candidate-classpath daemon/android/build/field-cache-revisit-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --cpus 0,1,2,3 --renders 60 --policy classpath \
  --font-cache daemon/android/build/bulk-font-cache
```

Run `scripts/benchmark-worker-matrix.py` with
`scripts/experiments/field-cache-revisit-g1.json` or
`scripts/experiments/field-cache-revisit-g1-default-compiler.json`, a fresh output
directory, `--trials 3 --renders 60 --fixture DenseDashboardPreview --width 480
--height 1200 --memory`, and the same baseline classpath/JDK. Keep builds, profilers
and other benchmarks stopped during timings.
