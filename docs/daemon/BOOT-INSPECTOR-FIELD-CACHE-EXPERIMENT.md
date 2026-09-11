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
