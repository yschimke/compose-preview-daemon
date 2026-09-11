# Worker CPU and memory under contention

Wall time, CPU demand and memory footprint are separate optimization targets.
The earlier single-worker GC trials rejected Serial GC for isolated latency, but
that does not establish which collector serves a loaded host best.

## Method

`benchmark-worker-startup.py --memory` samples Linux RSS every 100 ms and records
kernel RSS high-water, anonymous/file resident memory, PSS and private resident
pages at readiness and workload end. PSS/private snapshots use `smaps_rollup`;
regular samples only read `status`. All configurations use the same measurement
mode. These instrumented timings are not compared directly against older runs
without memory snapshots.

`benchmark-worker-contention.py` launches four real worker processes concurrently
on the same four logical CPU IDs, selected from four distinct physical cores of
the Ryzen 9 3900X. The remaining host CPUs are not restricted. Each worker has a
separate output directory and speaks the production render protocol. Cohorts are
sequential; variant order rotates over three trials. There is no background busy
loop or unrelated process termination. This models CPU contention, not an
external memory-pressure or OOM test.

Each worker renders RedSquare followed by six cycles of the existing 12-fixture
mixed workload (73 checked frames). Four configurations compare G1 with a 1 GiB
heap cap, G1 with 512 MiB, 512 MiB G1 with two parallel/one concurrent GC threads,
and 512 MiB Serial GC. All use JDK 17, C1, method-handle threshold 30, Robolectric
4.17 and guarded constant bindings, without application CDS or concurrent BC.
These direct-worker experiments do not set `MaxHeapFreeRatio` or `MinHeapFreeRatio`.
The normal `SandboxSparePool` launcher supplies 30 and 10 respectively unless the
descriptor overrides them. Consequently these are controlled worker comparisons,
not measurements of the complete spare-launch configuration. A launch-setting
recommendation must also test those existing heap-shrinking settings.
HotSpot sees the shared four-CPU affinity, so default GC ergonomics already differ
from the unrestricted single-worker runs.

Cohort throughput includes startup, rendering and shutdown. CPU totals include
worker startup and all timed frames, but exclude shutdown and the Python harness.
P95 uses nearest-rank request latency within each cohort; readiness reports median
and maximum across only four workers, not a population tail estimate.

Memory values are KiB. Summed worker RSS high-water marks are **not** a simultaneous
physical-memory peak: peaks may occur at different times and RSS double-counts
shared pages. PSS/private values are per-worker snapshots at workload end, not a
synchronized whole-cohort measurement. A heap cap is not a process-memory cap;
native rendering, mapped libraries, metaspace and other allocations remain.

## Reproduction

Prepare the guarded current-version classpath, then run the committed four-variant
matrix:

```sh
python scripts/benchmark-worker-contention.py \
  --matrix scripts/experiments/contention-gc-heaps.json \
  --classpath daemon/android/build/frozen-current-provider/constant-classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output /tmp/contention-results --cpus 0,1,2,3 \
  --workers 4 --trials 3 --renders 72 \
  --fixture MaterialButtonInteractionState --fixture SerifTextPreview \
  --fixture DialogWindowSurface --fixture OpaqueImageSquare \
  --fixture GradientBackgroundCard --fixture RadialGradientBackgroundCard \
  --fixture EmojiAndAnnotatedText --fixture GraphicsLayerAndWideVector \
  --fixture IconButtonRowInputBar --fixture LazyColumnListPreview \
  --fixture EditableTextFieldSquare --fixture GenericOutlineShapeSquare
```

Use CPU IDs available on the target host and check their topology. The committed
report includes the exact matrix. A two-worker smoke run verified launch timing,
artifact parity and memory collection before the larger comparison.

## Results

All **3504 checked PNG/UIA frames** matched across 12 cohorts (48 fresh workers).
Launch spread was at most 7 ms. The table takes medians across the three cohorts
for each configuration, including medians of the per-cohort memory snapshots.

| Configuration | Frames/s | Worker CPU/frame, including startup | Ready median | Request P95 | End PSS/worker | End private/worker |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| g1-1g | 10.37 | 341.1 ms | 5676 ms | 472 ms | 559.4 MiB | 550.5 MiB |
| g1-512m | 10.50 | 346.5 ms | 5692 ms | 458 ms | 568.3 MiB | 560.7 MiB |
| g1-512m-2gc | 10.49 | 334.6 ms | 5578 ms | 429 ms | 589.7 MiB | 580.0 MiB |
| serial-512m | 10.94 | 328.8 ms | 5160 ms | 412 ms | 530.5 MiB | 523.9 MiB |

Serial GC with the 512 MiB cap uses **3.6% less worker CPU**, has **5.2% lower
end-of-workload PSS**, and delivers **5.5% more cohort throughput** than 1 GiB G1.
Its median request latency is nevertheless 4.6% higher; its request P95 is 12.7%
lower. Startup and tail behavior matter to the completed-cohort rate, so median
request latency alone would miss the result.

Reduced-thread G1 lowers CPU but uses more resident memory in this run. Merely
halving the default G1 heap cap does not reliably reduce RSS/PSS. These are
measured process footprints, not arithmetic extrapolations from heap caps.

The earlier isolated-worker rejection of Serial GC was a latency result, not a
server-capacity conclusion. This controlled contention test justifies evaluating
Serial GC for constrained worker pools. It does not establish a universal default:
longer-lived workloads, smaller heaps, different concurrency/CPU budgets, and
synchronized whole-pool PSS measurements remain useful next experiments. The
production launcher is unchanged. [Raw cohort results](profiles/contention-gc-heaps.json).

Validation also includes Python syntax checks and a failed-worker negative control:
a worker that exits before readiness rejects its cohort and produces no success
summary. This is in addition to the real-worker smoke run and full artifact parity.


## Smaller Serial heaps

A follow-up uses the same four-worker/four-CPU setup and fixture cycle, with
144 renders after the initial red frame per worker. Three rotated trials of
512, 384 and 256 MiB heaps completed **5220 matching PNG/UIA frames**. The
[raw cohort report](profiles/contention-serial-heaps.json) records every worker.

| Serial heap cap | Frames/s | Worker CPU/frame including startup | End PSS/worker |
| --- | ---: | ---: | ---: |
| 512 MiB | 13.23 | 279.4 ms | 562.4 MiB |
| 384 MiB | 13.19 | 276.0 ms | 532.8 MiB |
| 256 MiB | 13.43 | 274.7 ms | 492.1 MiB |

The 256 MiB cap reduced median end PSS by 12.5% and CPU/frame by 1.7%
relative to 512 MiB. This does not establish safety for long-lived workers or
complex screens. The matrix is `scripts/experiments/contention-serial-heaps.json`;
reproduce with the command above, replacing the matrix and using `--renders 144`.

### Long-lived diagnostic method

`DenseDashboardPreview` composes 24 cards and 288 chart bars with text, vectors,
gradients and semantics. Use `--width 480 --height 1200` to exercise a larger
frame; at the default device density, 12 cards are visible and the remaining
composed content is clipped. `benchmark-worker-startup.py --gc-checkpoint-every 100` records explicit
GC command output, heap-generation usage and PSS/private memory at checkpoints.
Add `--heap-histograms` to persist live-object histograms at the same boundaries;
this requests an additional diagnostic GC. These diagnostic checkpoints add work and are not suitable for direct timing
comparison with ordinary runs. Checkpoints are persisted while the run is active.

The production renderer already calls `System.gc()` through
`SandboxMeasurement.collect` after each render. Its lifecycle counters explicitly
document that automatic recycling has not landed; the exposed
`maxRendersPerSandbox` configuration has no enforcement reference in this tree.
The standalone-worker experiment therefore must not assume that crossing 1000
renders causes a restart. No smaller heap or recycling default is enabled here.

Use `scripts/summarize-worker-growth.py summary.json ... --output report.json`
after completion to verify within-run and cross-run PNG/UIA parity and extract
Serial-GC heap checkpoints. First/last request windows are descriptive and must
contain comparable fixture mixes before interpreting a latency trend. A rising
RSS high-water mark alone does not demonstrate retained-heap growth.

### Recycling requirements to validate

The worker's `SandboxLifecycleStats` belongs to the host created in
`SandboxWorkerMain`, and its render count survives `configure` / `release`.
`SandboxProcessPool.shutdown` normally **releases adopted workers** back to their
spare pool; returning a worker therefore does not reclaim its process memory.
A future render-count policy should use the worker-lifetime counter returned in
render metrics, rather than restart a parent-local counter on every adoption. The existing metric
counts successful render completions; a robust policy must also count failed
attempts, which may allocate heavily before throwing and return no render metrics.

Retirement must happen after the current response, coordinate with slot selection,
and arrange a replacement. Merely marking a worker dead removes capacity; merely
releasing it passes its retained state to the next daemon. Whole-process retirement
also needs to distinguish owned workers from borrowed spares and inform their owner.
Staggered replacement avoids synchronized cold boots; prewarming a replacement
adds a temporary memory overlap that matters on a loaded server. No age or render
threshold is recommended until the soak and repeated-process comparisons finish.

The dense fixture also exposes duplicate CPU work: both `LayoutInspectorExtension`
and `ComposeFigmaSvgExtension` build the layout payload from the same captured root
and slot tables. Baseline phase logs show approximately 749 ms for layout output
and 812 ms for SVG output. A draft change supplies a lazy snapshot in each render's
extension context so these products share one walk. It has no cross-render cache;
null results are memoized and failed initialization remains retryable. The connector tests and both renderer compilations pass; matched artifact and
CPU/memory measurements are recorded in the layout snapshot experiment below.


The initial soak uses a distinct output base name for every request, as the
startup harness normally does. `--reuse-output` overwrites one base name per
fixture for a complementary repeated-preview diagnostic. Comparing these modes
can separate per-output bookkeeping growth from growth with a fixed set of
preview names. Save hashes while rendering in reuse mode: only the final artifact
for each fixture remains on disk.

`compare-worker-artifacts.py BASELINE_DIR CANDIDATE_DIR` compares corresponding
completed runs, checking PNG/UIA hashes plus layout, semantics and SVG files.
It requires distinct output names and identical fixture sequences and dimensions.
`--normalize-jvm-lambdas` only canonicalizes JVM-generated lambda identities in
JSON modifier properties; SVGs, node IDs and all other values stay strict.
The report counts artifact comparisons that required this normalization. Two
existing 145-frame workers pass 435 artifact comparisons (72 require lambda
normalization), and a deliberately changed component is rejected with the option
on. The snapshot optimization has a separate matched baseline/candidate report below.

### Completed 256 MiB dense soak

The first dense soak completed 1100 dashboard renders after one red frame in
2359 seconds (about 39 minutes), with 2278 seconds of worker CPU including startup
and diagnostic checkpoints. All **1101 PNG/UIA pairs** matched their fixture's
first output, and all **1100 dense SVGs** were byte-identical. The same worker PID
crossed 1000 renders and completed 1100 without being recycled.

[Condensed results and exact inputs](profiles/growth-dense-256.json) retain every
heap checkpoint. Post-GC heap ranged from 70.3 to 85.1 MiB after the first 100 dense
renders; it rose and fell rather than increasing monotonically. PSS increased from
501.1 MiB at 100 to 555.6 MiB at 600, then reached 571.2 MiB at 900 and stayed there
through 1100. Final RSS was 579.7 MiB, with a 585.7 MiB lifetime high-water mark.
The first/last 100 dense requests had median wall times of 2108/2089.5 ms and mean
worker CPU of 2089.2/2042.3 ms: no late slowdown appeared in this trial.

This is evidence that the smaller heap sustains this particular complex workload;
it is not a bound for image-heavy screens or indefinite operation. It also shows
why the heap cap is unsuitable as a worker's physical-memory budget. The matched 512 MiB comparison below evaluates the heap-size tradeoff.

### Application classloader churn remains a separate check

The daemon fixture package is deliberately parent-first in `UserClassLoaderHolder`.
The completed dense soak therefore measures repeated rendering in one sandbox,
not unloading changing application classes. `benchmark.screens.ReloadDashboardPreviewsKt`
provides a separate composition outside that package and asserts that its anchor
class was loaded by `ChildFirstURLClassLoader`; it does not delegate the screen
body to the daemon fixture.

The harness accepts `--class-name`, `--user-class-dir` (directories or jars), and
`--swap-every N`. It checks that the returned render classloader identity changes
after each acknowledged swap. With `--heap-histograms`, checkpoints also persist
`VM.classloader_stats`. Swaps occur after completed requests and before the GC
checkpoint. The positive diagnostic completes 60 renders with 60 distinct application loader
identities at 256 MiB; PNG/UIA stay identical while one output name is reused. The
negative control omits the application path and fails the fixture's loader assertion,
confirming it cannot silently exercise parent-loaded classes instead.

This test **does show retained loaders**: forced-GC checkpoints at 10/20/30/40/50/60
renders report 11/21/31/41/51/61 live child loaders. Live Serial heap is 92.1 MiB at
10 and 93.0 MiB at 60; final PSS is 500.3 MiB. The count grows with swaps despite
reusing the output name. These diagnostics are not a clean throughput benchmark.
Both controls reproduce it: 30 renders on the baseline tuned build and 30 on the
candidate with the unmodified/default JVM each leave 31 child loaders after GC.
The retention therefore predates the shared snapshot and does not require the
experimental bindings or JVM flags. All 120 dashboard renders across these runs
have identical PNG/UIA hashes. [Checkpoint results](profiles/reload-loader-retention.json)
record the counts and heap readings. Root-path analysis remains outstanding; these
counts do not identify the retaining cache or its eventual size. The static
1100-render result does not establish safety under application reload churn.

### Matched 512 MiB soak

The 512 MiB comparison also completed 1100 dense renders after red, in 2286 seconds
with 2244 seconds of worker CPU. The two runs have matching PNG/UIA hashes for all
1101 corresponding frames and **3303 byte-identical layout, semantics and SVG
artifacts**, without identity normalization. [Both runs and comparison](profiles/growth-dense-heaps.json)
retain the complete checkpoint curves.

| Heap cap | Final post-GC heap | Final PSS | Peak RSS | Total worker CPU | Workload wall |
| --- | ---: | ---: | ---: | ---: | ---: |
| 256 MiB | 85.1 MiB | 571.2 MiB | 585.7 MiB | 2278.4 s | 2359.0 s |
| 512 MiB | 85.8 MiB | 642.1 MiB | 666.3 MiB | 2244.1 s | 2286.0 s |

The smaller heap saves **70.9 MiB / 11.0%** of final PSS, but costs **1.5% more
worker CPU** and **3.2% more wall time** in this pair of trials. This differs from
the shorter mixed-workload CPU result: workload and lifetime matter. Both runs'
late resident footprint levels off; neither shows monotonically growing live
heap. The 512 MiB trial's last 100 requests had median wall/mean CPU of
2162.5/2106.9 ms versus 2045/2035.1 ms for its first 100, while the smaller-heap
trial's late requests were slightly faster than its early requests. These are
single sequential trials, so do not infer a precise sustained CPU advantage from
the small timing differences.

For this fixture, 256 MiB sustained the full workload without reaping. That does
not prove an indefinite lifetime bound or cover image-heavy screens and application
classloader churn. The normal spare launcher's heap-free-ratio settings and other
compilation tiers remain separate comparisons before changing launch defaults.

The implementation and matched performance procedure for the shared layout walk
are recorded in [the layout snapshot experiment](BOOT-LAYOUT-SNAPSHOT-EXPERIMENT.md).

The growing locale-cache owner has now been traced and fixed; see
[locale cache retention](BOOT-LOCALE-CACHE-RETENTION.md) for heap-root evidence and
the repeat showing two live child loaders throughout 60 application reloads.

A three-trial [explicit-GC comparison](BOOT-EXPLICIT-GC-EXPERIMENT.md) found a
4.1% request latency reduction but only 0.9% lower total CPU and 47.2 MiB higher
final PSS when disabling explicit GC. This does not change the launch recommendation.
