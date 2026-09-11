# Avoid exceptions for unreadable captured fields

The semantics candidate C1 profile contains 10,331 inclusive samples in
`AccessibleObject.checkCanSetAccessible` under `ModifierTokenResolver.fieldValues`;
7,659 include `Throwable.fillInStackTrace`. These overlapping sample counts are
diagnostics, not additive predicted savings. The two-level captured-value scan
reaches JDK strings/boxed objects whose fields are encapsulated, repeatedly using
exceptions to return the expected “unreadable” result.

The experiment changes only that field-read loop to use `trySetAccessible()`.
The [Java 17 contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/reflect/AccessibleObject.html#trySetAccessible())
returns false when access cannot be enabled, while preserving the same enabled
access flag on success. Security exceptions and field-read failures remain caught.
There is no member cache or negative-result cache, so neither classloader lifetime
nor later module-opening behavior changes. Other reflection paths stay unchanged.

A regression case checks private app fields beside encapsulated JDK values and
keeps ordinary captured strings from being mistaken for placeholder chrome.
The formatter, 240 connector tests and 23 desktop integration tests pass.
The [smoke comparison](profiles/inspector-access-smoke-parity.json) matches all
three PNG/UIA frames and 33 data artifacts across eleven products, allowing only
two process-specific Typeface identities. Reload checks and measurements are recorded below. Compare against the frozen regex candidate to isolate the effect; do not
rebuild during the running regex benchmark. This is our inspector overhead, not
an upstream Robolectric bug.


The final repeated-run matrix compares production, semantics-plus-regex, and the
full candidate in three rotated sequential trials of red plus 60 dense renders.
It uses explicit density 2, default compilation and G1/1 GiB on JDK17 with GC
logging enabled equally for all variants. This provides both a direct production
comparison and an isolated field-access comparison, without adding percentages
from unrelated JVM profiles.


The first trial of the final matrix is excluded from timing conclusions because
the required pre-commit `ktfmtCheckAll` hook overlapped its production worker.
Retain its artifact checks, then run a clean replacement trial after the matrix
finishes. Do not silently pool the contaminated row with the clean trials.


## Follow-up memory attribution

Before changing heap or recycling defaults, repeat the final candidate with
actual application reloads under the 256 MiB Serial profile. Keep forced-GC live
heap and loader counts separate from PSS/RSS. Enable JVM native-memory tracking
only in a separate diagnostic run, using `-XX:NativeMemoryTracking=summary` and
`jcmd VM.native_memory summary` (or exit statistics), to record code cache,
metaspace, GC and thread allocations.

[HotSpot NMT documentation](https://docs.oracle.com/en/java/javase/17/vm/native-memory-tracking.html)
states that third-party native allocations and JDK-library native allocations
are outside its coverage. Also, committed/reserved JVM bytes are not physical
resident bytes. Therefore do not subtract NMT committed totals from PSS and label
the residual “Skia”, or interpret stable classloader counts as proof of stable
whole-process memory. Keep NMT-instrumented timings out of the uninstrumented
performance comparison.


## Clean combined results

[Final measurements and all-artifact comparisons](profiles/inspector-final.json)
use the clean replacement plus original trials 1 and 2. The nine clean workers
render 549 frames. Each candidate is compared directly with production in every
trial: 366 corresponding frame pairs and 4,026 data-artifact comparisons pass.
The excluded trial is identified in the report and contributes no timing statistics.

| Median measure | Production baseline | Semantics + regex | Full candidate |
| --- | --- | --- | --- |
| Whole-worker CPU | 108.35 s | 102.53 s | **90.00 s (−16.9%)** |
| Last-30 mean CPU/request | 1270.3 ms | 1223.0 ms | **998.0 ms (−21.4%)** |
| Last-30 median wall/request | 946.5 ms | 884.5 ms | **663.5 ms (−29.9%)** |
| Whole-worker wall | 63.908 s | 59.469 s | 46.491 s |
| Ready wall | 4363 ms | 4364 ms | 4329 ms |
| End PSS | 1074.4 MiB | 1016.1 MiB | 1021.4 MiB |

The field-access change alone saves 12.2% whole-worker CPU against semantics plus
regex under this profile. The combined gain is measured directly, not a sum of
isolated percentages. Startup readiness barely changes. End PSS is about 53 MiB
lower than production in this matrix, but the earlier trials show substantial
physical-memory variation; no general memory guarantee, heap reduction or recycling
policy change follows from three workers. The full-candidate reduced-heap validation is recorded below.


## Full-candidate 256 MiB reload check

The [300-reload diagnostic run](profiles/inspector-final-reload-256.json) uses the
full candidate, Serial GC with a 256 MiB cap, the spare-launcher 10/30 heap-free
ratios, density 2, reused output names and the actual child-loaded dashboard fixture.
Forced GC and live histograms run every 50 renders; JVM native-memory tracking is
enabled. These diagnostic timings are excluded from the performance results above.

All 301 PNG/UIA pairs match the historical density-2 reload baseline. There are
300 distinct application loader identities and exactly two live child loaders at
all seven checkpoints. Post-GC heap MiB at 0/50/100/150/200/250/300 renders is
87.84/90.69/91.09/91.47/88.02/79.54/79.85. PSS MiB is
572.51/780.80/822.76/867.32/873.54/879.77/885.42.

At JVM exit, NMT reports committed code memory of 128.1 MiB, metaspace 111.6 MiB,
class metadata 20.7 MiB, symbols 28.4 MiB and tracking overhead/category 14.8 MiB.
These are JVM accounting categories, not resident-memory components to add or
subtract from PSS. Third-party native allocations remain outside NMT coverage.

This demonstrates unloading through 300 real application replacements and no
monotonic live-heap growth in this fixture. It does not establish a whole-process
plateau or an indefinite memory bound: PSS still increases slightly at the end.
Keep heap defaults and worker recycling policy unchanged; broader/image-heavy
workloads and native-memory attribution are needed before a general recommendation.
