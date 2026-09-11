# Compilation after settling buffer reuse

## Fresh CPU profile

[Profile analysis](profiles/post-buffer-cpu.json) uses the frozen renderer from
[PR #88](https://github.com/yschimke/compose-preview-daemon/pull/88), commit
`eeb73cfe3e024900444134275501bb7000efccf9`, with a red warmup and 60 dense
480 × 1200 frames at density 2. Settings: JDK 17, Serial/Xmx256m/Xms32m,
free-heap ratios 10/30, two compiler threads, metrics every five renders,
native trimming every 15 seconds, and warmed offline fonts. Async-profiler 4.5
samples CPU and wall stacks at 1 ms using native DWARF unwinding. No other local
build or benchmark overlaps the profile.

The post-readiness interval contains 42,341 CPU samples. Compiler threads
account for 17,545 (41.4%): 12,621 C2 and 4,924 C1. The SDK main thread
accounts for 21,018 (49.6%), and VM Thread for 3,269 (7.7%). Native JVM symbols
are unresolved; VM Thread must not be labelled entirely GC. These are sample
counts, not elapsed time or exclusive estimates of removable CPU cost.

| Inclusive scope | CPU samples | Share |
| --- | ---: | ---: |
| Modifier extraction | 4,005 | 9.46% |
| Visual settling | 4,139 | 9.78% |
| ImageIO writes | 1,079 | 2.55% |
| Pixel reads (`getRGB`) | 572 | 1.35% |
| ImageIO reads | 332 | 0.78% |
| Snapshot copying (`snapshotArgb`) | 73 | 0.17% |

Scopes overlap and must not be summed. All 61 frames match across eleven
exported artifacts against the same frozen renderer's unprofiled G1 run;
normalization is limited to semantics debug order and Typeface identity.
The font cache hashes are unchanged. Profiled timing is not speedup evidence.

Compilation is a larger measured target than further snapshot-copy work.
The previous C1-only comparison used a small Material fixture and 30 requests;
it cannot establish throughput on the current complex screen or long reload
sessions. Removing C2 compilation can also make application code slower.

## Unprofiled dense-screen comparison

`scripts/experiments/post-buffer-c1.json` compares normal tiered compilation
with `-XX:TieredStopAtLevel=1`. Both variants use the frozen PR #88 renderer and
the profile settings above, without a profiler, in three alternating fresh-worker
pairs with 60 DenseDashboardPreview renders. [All three paired results](profiles/post-buffer-c1.json) match 183 frames across
11 artifacts (2,013 comparisons), with metrics cadence and JVM argument differences
verified.

| Measure | Tiered compilation | C1-only |
| --- | ---: | ---: |
| Mean total CPU | 49.807 s | 35.270 s |
| Median workload wall time | 28.245 s | 31.208 s |
| Median last-30 mean CPU/frame | 507.33 ms | 442.67 ms |
| Median last-30 wall/frame | 316.0 ms | 390.5 ms |
| Median end PSS | 600.47 MiB | 519.04 MiB |

Total CPU falls 29.2% and final PSS 81.43 MiB (13.6%), while workload wall time
rises 10.5% and steady-frame latency 23.6%. This is a resource/latency tradeoff,
not a universal speed improvement. CPU includes concurrent compiler work, so
lower process CPU can coexist with a slower individual render. End PSS is one
sample rather than a peak or retained-memory census.

Median readiness improves 4562 → 4161 ms. Across the three trials, the first ten
dense frames average 1112.33 → 683.33 ms process CPU; the last ten average
457.33 → 435.00 ms. Last-ten median wall time is 307.5 → 385.5 ms. The CPU
saving narrows from 38.6% to 4.9% as tiered compilation progresses. A 60-frame
aggregate therefore cannot predict long-lived worker capacity.

The concurrent runner's `--policy c1` uses these same common JVM settings and
actual ReloadDashboardPreview application reloads. It requires warmed fonts and
rejects `--candidate-classpath`; only the compilation tier differs. The completed longer
comparison is below. No launch defaults change in this study.


## Next application-code target

`ModifierTokenResolver.invokeNoArg` contributes 2,012 inclusive samples. Within
that call, 1,946 include `Class.getMethod`, 1,856 include
`NoSuchMethodException`, and 1,855 include `Throwable.fillInStackTrace` (4.38% of
all samples). These overlapping scopes identify repeated missing-method lookup
as a better target than another snapshot-copy change. Descendant-only matching
avoids attributing reflection higher in the call stack to this getter helper.

A candidate should cache public zero-argument method lookup, including absence,
with class-owned metadata. Preserve `Class.getMethod` selection and accessibility;
the existing semantics method cache searches private declarations too and cannot
be substituted directly. Cache only lookup metadata, never getter results or
receiver objects; preserve changing receiver state and invocation failures.
Validate inherited/interface/covariant methods, inaccessible classes, missing
methods and class unloading before matched artifact and performance comparisons.
A prototype and focused tests are written but remain unbuilt and unmeasured
while the C1 timing run is active. It is our extractor work,
not evidence of a Robolectric bug.


The existing reload fixture instantiates library-owned RoundedCornerShape objects;
it does not by itself exercise application-owned classes as public-method cache
keys. An app-owned custom-shape reload fixture is now written with public corner
getters and absent getters on separate classes. Its child-loader assertions,
exported shape data and live-loader census must be verified after building it,
alongside the focused disposable-loader test. A cross-package
hidden-class fixture also checks that cached lookup does not bypass invocation
access restrictions. These tests remain pending execution while timing runs.


## Concurrent workers, 300 reloads each

[Full report](profiles/post-buffer-c1-concurrent-300.json). Three alternating pairs
of two workers share four physical CPU cores. Each worker performs 300 actual
complex-screen reloads. Both use the frozen PR #88 renderer, Serial256/Xms32,
free ratios 10/30, two compiler threads, metrics every five renders, 15-second
native trimming and warmed offline fonts. Only TieredStopAtLevel=1 differs.
No profiler, NMT or extra diagnostic GC checkpoints overlap these timing runs.

| Measure | Tiered | C1-only |
| --- | ---: | ---: |
| Mean combined CPU | 293.237 s | 304.810 s |
| Median group elapsed | 113.616 s | 139.713 s |
| Median observed peak combined PSS | 1315.80 MiB | 1096.94 MiB |
| Median late-30-second combined PSS | 1248.68 MiB | 1034.71 MiB |
| Mean minor page faults | 426,144 | 328,229 |

C1-only uses 3.95% more CPU and takes 22.97% longer. Both regress in every pair.
Peak combined PSS falls in every pair, with a median reduction of 218.86 MiB
(16.6%). Minor faults fall 23.0%. This reverses the short-session CPU conclusion;
C1-only cannot be recommended as a general capacity improvement for long-lived
workers. Its lower physical-memory usage is a separate tradeoff.

All 1,806 paired PNG/accessibility frames and both final SVG products match.
Every worker verifies 300 distinct application loader identities. All per-frame
metrics cadence and compilation-only argument differences pass checks. Fifty-reload
CPU/latency windows, sequential one-second PSS samples and trim durations are
retained in the report. PSS samples are neither atomic nor exact peaks, and loader
identities do not prove collection. Host load is uncontrolled.

Keep normal tiered compilation as the default. Investigate the public-method
lookup hotspot next; it targets repeated extractor work without intentionally
restricting generated-code optimization. The new custom-shape fixture and cache
are a separate follow-up, not part of the C1 timing inputs.
