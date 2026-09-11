# Cache public shape-getter lookup

**Status: draft implementation; default-G1 CPU variability remains a qualification caveat.**

The [post-buffer profile](https://github.com/yschimke/compose-preview-daemon/blob/9d559219a0677aa8269bc476f0de6b982c1b6985/docs/daemon/BOOT-POST-BUFFER-COMPILATION.md)
finds 1,855 of 42,341 CPU samples beneath exception stack construction during
`ModifierTokenResolver.invokeNoArg`: repeated public shape-getter misses account
for about 4.4% of sampled CPU. This is our extraction code, not a Robolectric bug.

## Candidate

A separate `PublicZeroArgMethodCache` uses ClassValue-owned maps of method name
to public Method or absence. The lookup still calls `Class.getMethod(name)` on a
cache miss, preserving public/inherited/covariant selection. Only
NoSuchMethodException becomes cached absence; other lookup errors propagate to
the existing caller exception handler. Invocation failures and getter results
are never cached. No accessibility override is applied.

This differs deliberately from the existing semantics zero-argument cache, which
searches private declarations. Substituting that cache would change behavior.
Neither receiver objects nor a global map of application classes is retained.
Class unloading remains an acceptance criterion, not an assumption based on
using ClassValue.

## Correctness checks

All 36 focused tests pass, including six new tests for public and covariant
selection, inherited/default methods, private/argument-taking/missing methods,
changing receivers and values, invocation failures, inaccessible declaring
classes and collection of a disposable classloader. The loader test keeps the
cache alive with a reachability fence through the collection assertion.

The [frozen inputs](profiles/public-method-cache-inputs.json) use the renderer
from PR #88 as baseline. Only classpath entry 21 (the
layout-inspector connector jar) changes. The generated supertypes helper differs
in metadata but has identical `javap -c -p` output; executable changes are confined
to ModifierTokenResolver and the new cache classes.

The new ReloadCustomShapeDashboardPreview adds two app-owned shapes to the
existing complex dashboard. Both assert their classloader is the application
ChildFirstURLClassLoader. One has public corner getters; the other has no corner
getters or Shape fields. This exercises both successful and absent lookup metadata
on reloadable types, unlike the original library-shape fixture alone.

[The smoke comparison](profiles/public-method-cache-custom-smoke.json) passes all
four frame pairs and 44 artifacts across red warmup plus three actual reloads.
Each worker observes three distinct application loader identities. Every custom
frame exports `6.0dp` for the public-getter shape and no corner-radius token for
the opaque shape. This is correctness evidence, not a timing result or a live
loader census.

## G1 performance comparison

`scripts/experiments/public-method-cache-g1.json` runs three alternating pairs
with 60 dense renders: JDK17, G1/Xmx1g, two compiler threads, every-render metrics,
no native trimming, warmed offline fonts, and no profiler.

[All three G1 pairs](profiles/public-method-cache-g1.json) pass 183 frames and
2,013 artifact comparisons. Mean CPU falls 60.160 → 58.363 s (−2.99%);
median workload wall time falls 28.596 → 26.702 s (−6.62%). All three pairs
improve CPU and wall time. Median last-30 mean CPU is 683.67 → 653.33 ms and
median last-30 wall latency 371.0 → 337.5 ms. Median final PSS increases
783.12 → 792.56 MiB; memory changes are mixed, so no memory saving is claimed.
No allocation or candidate CPU profile has yet verified elimination of the
specific exception samples.

## Reload retention diagnostic

A separate paired diagnostic runs 300 actual custom-shape reloads with
Serial/Xmx256m/Xms32m, two compiler threads, metrics every five renders and native
trimming every 15 seconds. Forced-GC checkpoints and live histograms every 50
reloads will measure loader retention. Their timings must not be used as clean
performance evidence. No launch or recycling defaults change.


[The paired retention report](profiles/public-method-cache-retention.json) records
two live application loaders at all seven checkpoints in both variants, through
300 custom-shape reloads. Every worker verifies 300 distinct loader identities.
All 301 paired PNG/accessibility frames, both final SVG products and final
custom-shape tokens match.

After reload 50, used heap ranges from 94.14–101.19 MiB in the baseline and
93.17–100.93 MiB in the candidate. Final PSS is 652.12 versus 690.92 MiB,
with fluctuations across checkpoints. This diagnostic does not establish a memory
saving or a natural-GC plateau. It finds no growing application-loader retention
under forced collection; no fixed recycling age is inferred.

The concurrent runner now accepts explicit fixture and class names, retaining
the original reload fixture as its default. A three-pair, two-worker 300-reload
comparison of this custom-shape fixture is next, without diagnostic checkpoints.

## Reproduction

Build the connector and custom fixture after focused tests, then freeze the
candidate classpath before timing:

```sh
./gradlew --no-daemon :data-layoutinspector-connector:test \
  --tests '*PublicZeroArgMethodCacheTest' --tests '*ZeroArgMethodCacheTest' \
  --tests '*ModifierTokenResolver*' :data-layoutinspector-connector:jar \
  :daemon:android:bundleLibRuntimeToJarDebugTestFixtures
```

Start from `daemon/android/build/settle-buffer-reuse-frozen/classpath.txt`, the
PR #88 renderer. Replace only ordered entry 21 with the rebuilt connector jar,
then run `scripts/experiments/freeze-classpath.py` into a fresh output directory.
The study uses `daemon/android/build/public-method-cache-frozen/classpath.txt`.
Copy the new runtime test-fixtures jar into a separate immutable fixture directory;
the study uses `daemon/android/build/public-method-custom-fixture/testFixtures-classes.jar`.
Record SHA-256 hashes of both classpaths and the fixture jar. Do not silently use
a jar selected from the many older versions that can coexist in `build/libs`.

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py \
  --policy classpath \
  --classpath daemon/android/build/settle-buffer-reuse-frozen/classpath.txt \
  --candidate-classpath daemon/android/build/public-method-cache-frozen/classpath.txt \
  --user-jar daemon/android/build/public-method-custom-fixture/testFixtures-classes.jar \
  --fixture ReloadCustomShapeDashboardPreview \
  --class-name benchmark.screens.ReloadCustomShapePreviewsKt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java --cpus 0,1,2,3 --renders 300 \
  --font-cache daemon/android/build/bulk-font-cache \
  --output daemon/android/build/public-method-cache-concurrent-300
```

Use fresh output directories and a verified warmed offline font cache. Run no
local builds, other benchmarks or profilers alongside timing. The default-compiler
G1 check uses `scripts/experiments/public-method-cache-g1-default-compiler.json`
with the same three-pair/60-dense-frame matrix command as the two-compiler-thread
check. Candidate CPU profiling must run separately from both matrices.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/public-method-cache-g1-default-compiler.json \
  --classpath daemon/android/build/settle-buffer-reuse-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory \
  --output daemon/android/build/public-method-cache-g1-default-compiler
```

For the two-compiler-thread comparison, substitute
`public-method-cache-g1.json` and a different fresh output directory.

## Concurrent custom-shape reloads

[Full report](profiles/public-method-cache-concurrent-300.json): three alternating
pairs of two workers sharing four physical cores, 300 reloads each, using the
custom-shape fixture. The Serial256/compiler2/metrics5/trim15s settings are equal;
only the frozen connector jar differs. No profiler or diagnostic checkpoints run
alongside timing. The CPU cores are not exclusively reserved.

| Measure | Baseline | Public-method cache |
| --- | ---: | ---: |
| Mean combined CPU | 297.510 s | 280.520 s |
| Median group elapsed | 115.675 s | 105.560 s |
| Median observed peak combined PSS | 1311.08 MiB | 1328.86 MiB |
| Median late-30-second combined PSS | 1243.88 MiB | 1287.57 MiB |

CPU falls 5.71% and elapsed time 8.74%, with improvements in all three pairs.
Group elapsed includes shutdown and up to one second of polling delay. Both
workers' PSS is sampled sequentially; the peak is observed, not exact or atomic.
Median peak combined PSS rises 17.78 MiB (1.36%), and late-window PSS rises
43.68 MiB (3.51%). This is a CPU/latency improvement with mixed-to-higher physical
memory, not a memory optimization. Minor faults decrease substantially in one
pair but increase in the other two; their lower aggregate mean is not a consistent
fault-rate improvement.

All 1,806 paired PNG/accessibility frames, both final SVG products and final
custom-shape tokens match. Each worker verifies 300 distinct application loaders;
metrics cadence, JVM settings, fixture identity and frozen input hashes are
checked. The separate diagnostic above supplies the live-loader evidence.

The following sections retain the default-compiler regression and investigate
its relationship to collector state before an adoption decision.

## Default-compiler G1 regression

[The default-compiler comparison](profiles/public-method-cache-g1-default-compiler.json)
passes all 183 frame pairs and 2,013 artifacts, but does not pass the CPU acceptance
criterion. Candidate CPU is 70.24, 69.98 and **95.72 s**, versus baseline 72.18,
71.17 and 71.38 s. The outlier raises mean CPU from 71.577 to 78.647 s (+9.88%).
Median workload wall time improves 28.734 → 26.723 s, and median final PSS falls
978.60 → 932.06 MiB. Those medians must not hide the CPU regression.

[GC logs](profiles/public-method-cache-default-compiler-gc.json) in the outlier
show 611 completed pauses versus 113 in its baseline, and 176 humongous-allocation
concurrent-start pauses versus 16. Rounded logged GC CPU rises 14.39 → 22.82 s;
explicit-full-GC CPU is 13.70 → 14.17 s. This explains part, not all, of the extra
24.34 s process CPU; the logs are not an exclusive CPU profile and omit some
concurrent work. Similar candidate-side G1 tails occurred in earlier allocation
optimizations, so unconditional adoption is withheld. The Serial/two-compiler
wins and forced-GC loader evidence retain their narrower scope.

The separate candidate profile below checks whether the targeted repeated
exception work disappears. That mechanism check cannot negate the default-compiler
regression or replace clean paired CPU measurements.

[The matched CPU profiles](profiles/public-method-cache-cpu.json) confirm the
intended mechanism under Serial256/compiler2/metrics5/trim15s: helper samples
fall 2,012 → 16, with no candidate samples observed beneath NoSuchMethodException
or Throwable.fillInStackTrace in that helper, versus 1,856 and 1,855 respectively
in the baseline. Total sampled CPU is 42,341 versus 39,676, but profiled totals
are not used as an unprofiled speedup measurement. All 61 frame pairs and 671
artifact comparisons match, with identical JVM arguments and unchanged fonts.
The default-compiler G1 regression remains unresolved; the cache is not accepted
unconditionally on the strength of this mechanism result.

## Investigating the G1 tail

In the slow default-compiler candidate, humongous-allocation concurrent starts
continue through 28.293 seconds; its paired baseline's last such start is at
7.145 seconds. Per-ten-frame CPU remains elevated throughout the candidate run,
so this is not simply extra boot work. Post-explicit-GC heap capacities are mostly
317–334 MiB in the candidate versus 327–330 MiB after early frames in baseline;
those capacities alone do not establish the reason for the marking behavior.

The earlier [G1 region study](BOOT-INSPECTOR-G1-REGIONS-EXPERIMENT.md) also recorded
an intermittent high-cycle baseline worker under different GC-thread settings.
Thus a candidate-side outlier does not by itself establish cache causality. The
failed CPU gate is retained while the mechanism is investigated.

Oracle's [JDK17 G1 tuning guide](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-garbage-collector-tuning.html)
describes adaptive initiating occupancy based on prior behavior, and diagnostic
GC logging. The next paired diagnostic adds `gc+ihop=debug,gc+ergo=debug` to both
otherwise unchanged variants using `public-method-cache-ihop-diagnostic.json`.
It observes prediction and initiation decisions before changing policy. Detailed
logging is diagnostic evidence, not a replacement for the clean timing runs.

The [three diagnostic pairs](profiles/public-method-cache-ihop-diagnostic.json)
do not reproduce the high-cycle case. Every worker reports zero active adaptive
predictions. Mean CPU is 71.430 → 68.953 s and median wall 28.578 → 26.593 s;
all 183 frame pairs and 2,013 artifacts match. These additional quiet trials do
not remove the original outlier or establish its cause.

The installed JDK17 reports G1AdaptiveIHOPNumInitialSamples=3 (experimental),
G1UseAdaptiveIHOP=true and InitiatingHeapOccupancyPercent=45. A bounded one-pair
mechanism diagnostic lowers the initial-sample requirement to one in both
variants, with experimental options unlocked and the same detailed logging.
This is an attempt to reproduce active prediction, not a proposed launch option
or a fair substitute for the default-policy results.

[The one-pair early-activation diagnostic](profiles/public-method-cache-ihop-early.json)
reproduces the high-cycle state in **both** variants. Baseline/candidate CPU is
96.83/96.47 s; humongous-allocation marking starts are 174/181. Active prediction
reports a zero-byte threshold 170/169 times. All 61 frame pairs and 671 artifacts
match. This demonstrates that the cache is not required for the behavior; it does
not establish equal default-policy probabilities or erase the original comparison.

The [upstream jdk17u implementation](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/gc/g1/g1IHOPControl.cpp)
subtracts predicted old-generation growth during marking and a young-generation
buffer from its internal target, clamping the initiating threshold at zero. The
candidate's final logged inputs are about 723.6 MB/s, 549.83 ms, a 162.5 MB buffer,
and a 325.7 MB target: predicted demand exceeds that target. This is consistent
with the observed zero threshold. This source explanation does not identify why
marking-time estimates persist or prove a specific OpenJDK defect.

A matched one-pair control disables adaptive IHOP in both variants while keeping
the early-sample diagnostic setting and all other arguments equal. It tests
whether suppressing the adaptive calculation removes this induced high-cycle
state. No production flag changes are proposed.

[The static-IHOP control](profiles/public-method-cache-ihop-static-control.json)
returns both workers to the quiet state: 12/8 humongous-allocation marking starts
and 70.80/69.22 s CPU, versus 174/181 starts and 96.83/96.47 s with early adaptive
prediction. All 61 frame pairs and 671 artifacts match. The cache remains faster
in this control; disabling adaptive IHOP is not proposed as a production default.

Together, the induced episode and control support adaptive prediction as a
mechanism for the high-cycle state, independent of the cache. They do not measure
its probability under the default three-sample policy, nor explain whether the
cache changes that probability. The original default-policy outlier remains an
unresolved qualification caveat while upstream behavior is investigated.

Oracle's [older adaptive-IHOP diagnosis](https://ops.java/performance/jvm/articles/g1-adaptive-ihop-drops/)
describes the same visible zero-threshold/back-to-back-cycle symptom for
JDK-8245511, involving short-lived humongous objects reclaimed by young GC.
The article explicitly says JDK17 and later include that fix. Our JDK17 result
must not be identified as that old bug from symptom similarity alone. Ownership,
minimal reproduction and the precise reason prediction remains pessimistic are
still open. No OpenJDK or Robolectric bug has been filed from this observation.


## Full-GC interaction hypothesis

The [jdk17u policy implementation](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/gc/g1/g1Policy.cpp)
updates marking-duration prediction when tracking reaches a mixed collection;
a full collection resets that tracking. The shown full-collection path does not
clear the predictor's prior duration samples. In the induced candidate run,
549.83 ms persists across all 181 active predictions, with only one completed
mixed pause. Baseline has three mixed pauses and eventually holds 746.76 ms for
169 updates. This supports a hypothesis that repeated per-render full collections
prevent fresh duration samples while allocation-rate estimates continue updating.
It is not yet a verified OpenJDK defect or a minimal reproducer.

The next control will reduce metrics frequency from every render to every five
renders, keeping the induced early-activation condition. This retains real
post-GC metrics on measured frames and explicitly marks intervening frames as
unmeasured; it does not disable GC while pretending metrics remain equivalent.
The control is not part of the proposed cache's default behavior.
