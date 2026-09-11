# Compiler limits after the capture optimizations

A fresh dense-screen comparison finds `-XX:CICompilerCount=2` uses **13.2% less
whole-process CPU** and **154.21 MiB less median end PSS (20.9%)**, with essentially
unchanged steady-frame cost. This updates the earlier small-Material-screen
[compiler experiment](BOOT-CPU-PROFILE.md); it is not a new production default.

## Where CPU goes now

The profile uses the frozen final-snapshot-reuse renderer from PR #76, including
main's host ActionBar fix and in-memory settling. It renders a red fixture plus
60 dense dashboard frames, 480×1200 density 2, JDK 17.0.19 / Serial GC / 256 MiB
maximum / 32 MiB initial heap with free ratios 10/30. A separate async-profiler
4.5 recording samples CPU and wall stacks at 1 ms with native DWARF unwinding.
Only the post-readiness interval is summarized.

Of 50,386 CPU samples, compiler threads account for **19,723 (39.1%)**,
the SDK/worker application threads **22,165 (44.0%)**, and the VM Thread
**8,054 (16.0%)**. JVM native symbols are unresolved: VM Thread samples are not
labelled entirely GC, and class-parser/verifier/compiler-native costs cannot be
split reliably. These are CPU samples, not elapsed milliseconds.

Inclusive scopes include settling 5,637 (11.2%), pixel reads 2,077 (4.1%),
ImageIO writes 1,181 (2.3%), and image reads 315 (0.6%). These overlap and must
not be summed. Compilation is the larger measured target than another isolated
pixel-array change. [Profile data](profiles/post-snapshot-cpu.json).

## Unprofiled comparison

Three alternating fresh-worker pairs use the same frozen jars, dimensions,
fixture and Serial heap settings, plus equal GC logging. Only
`-XX:CICompilerCount=2` differs. No profiles, builds or other benchmarks overlap.
Host background load is uncontrolled and all trials are retained.

| Trial | Variant | Total CPU s | Total wall s | End PSS MiB |
| --- | --- | ---: | ---: | ---: |
| 0 | default | 64.44 | 57.550 | 737.05 |
| 0 | compiler2 | 56.02 | 50.245 | 593.84 |
| 1 | compiler2 | 55.85 | 60.001 | 582.84 |
| 1 | default | 64.32 | 56.944 | 744.11 |
| 2 | default | 64.53 | 51.205 | 703.63 |
| 2 | compiler2 | 55.98 | 56.183 | 576.51 |

Mean CPU is **64.430 → 55.950 s**; median CPU through readiness is
**14.470 → 9.470 s**. Median readiness wall time is **4.825 → 4.730 s**.
Median last-30 mean render CPU is **583.00 → 582.33 ms**, and median last-30
wall latency is **528.5 → 526.5 ms**. Median end PSS is **737.05 → 582.84 MiB**.

Whole-workload median wall time is **56.944 → 56.183 s**, but individual renders
occasionally take several seconds in both variants. Summing request durations
accounts for nearly all elapsed time; parent-side gaps do not explain it.
Logged maximum GC pauses are only around 0.1 seconds, not those multi-second
stalls. Their cause remains unresolved; the wall-time difference is not presented
as a stable latency win. Raw rows include host load, worst render durations and
maximum logged GC pause. CPU and memory improvements occur in all three pairs.

All **183 frame pairs and 2,013 exported artifacts match**. Narrow existing
normalizations cover semantics debug ordering and Typeface identities only.
[Comparison data](profiles/post-snapshot-compiler.json).

## Extension: 300 actual application reloads

A separate longer pair uses the same frozen renderer/heap settings and compiler
variants, but renders child-loaded `ReloadDashboardPreview` with `--swap-every 1`
for 300 application renders per worker. Both workers verify **300 distinct loader
identities**, and all **301 paired PNG/UIA hashes match**. Output files are reused,
so this run does not claim full-history parity for the other exported artifacts.

| Render window | Default mean CPU ms | Compiler2 mean CPU ms | Default median wall ms | Compiler2 median wall ms |
| --- | ---: | ---: | ---: | ---: |
| 1–50 | 889.6 | 844.0 | 566.5 | 534.0 |
| 51–100 | 567.4 | 563.4 | 534.5 | 512.0 |
| 101–150 | 536.6 | 527.8 | 527.0 | 511.0 |
| 151–200 | 510.0 | 504.4 | 513.5 | 512.0 |
| 201–250 | 513.6 | 507.6 | 507.5 | 502.0 |
| 251–300 | 516.8 | 493.4 | 514.0 | 498.0 |

Whole-process CPU is **192.790 → 182.320 s** (**5.4% lower**), and end PSS
**860.63 → 652.52 MiB** (**208.11 MiB / 24.2% lower**). CPU through readiness
is 15.010 → 9.360 s. Startup savings amortize, so the smaller overall CPU
improvement than the 60-render matrix is expected. None of the 50-render windows
regresses, although one ordered pair is insufficient to establish a general
long-run percentage. Whole-workload wall time is 268.240 → 261.286 s; unexplained
long waits still affect this metric.

The final worker-reported `heapAfterGcMb` is 82 versus 81. This run has no NMT,
heap histograms or diagnostic forced-GC checkpoints; it does not establish native
ownership, live-loader counts or an indefinite retention bound. Distinct loader
identities demonstrate the reload workload, not garbage collection of old loaders.
[Long-run data](profiles/compiler-long-reloads.json) retains per-window results,
flags, load, faults and final worker metrics. Default ran first, then compiler2;
no local builds/profilers/other benchmarks overlapped, but host load is uncontrolled.

To reproduce, invoke `scripts/benchmark-worker-startup.py` separately for the two
flag sets in `scripts/experiments/post-snapshot-compiler.json`, using the same
classpath, Java and heap flags as the short matrix. Set `--renders 300 --fixture
ReloadDashboardPreview --class-name benchmark.screens.ReloadDashboardPreviewsKt
--user-class-dir daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar
--swap-every 1 --reuse-output --width 480 --height 1200 --density 2 --memory` and
fresh `--output` directories. Validate the saved `swapEvery`, 301 frame hashes and
300 distinct application loader identities before accepting the result.

## Concurrent workers sharing four CPUs

A `PrintFlagsFinal` preflight with the same JDK/Serial heap shows that two allowed
CPUs already choose `CICompilerCount=2`. Four CPUs choose 3, and eight choose 4;
the explicit flag selects 2 in each case. `CICompilerCountPerCPU` also changes
from true to false. These are startup observations, not a claim that two-CPU
configurations are identical in every respect. The four-CPU case was selected
because it exercises a real difference in compiler limits.

Three alternating group pairs launch two workers sharing CPUs 0–3 (four distinct
physical cores); live-worker affinity was checked. Each worker performs 60 actual
application reloads. Both variants use the same frozen renderer and Serial heap
settings as above, without profiler, diagnostic GC checkpoints or NMT. The cores
are not exclusively reserved; host background load is uncontrolled.

| Trial | Variant | Aggregate CPU s | Group elapsed s | Observed combined peak PSS MiB |
| --- | --- | ---: | ---: | ---: |
| 0 | default | 129.30 | 44.586 | 1269.50 |
| 0 | compiler2 | 120.88 | 42.531 | 1165.76 |
| 1 | compiler2 | 122.54 | 41.510 | 1193.95 |
| 1 | default | 129.33 | 45.598 | 1260.66 |
| 2 | default | 132.29 | 46.575 | 1242.50 |
| 2 | compiler2 | 120.68 | 43.541 | 1183.72 |

Mean aggregate CPU is **130.307 → 121.367 s** (**6.9% lower**), median group
elapsed time **45.598 → 42.531 s** (**6.7% lower**), and median observed combined
peak PSS **1260.66 → 1183.72 MiB** (**76.94 MiB / 6.1% lower**). Every pair favors
the limit on these metrics. All **366 paired PNG/UIA frames match**, with 60
distinct application loader identities verified per worker. No live-loader
retention claim is made from identity counts.

Combined PSS is the sum of sequential reads while both workers are alive, sampled
roughly once per second; it is neither atomic nor an exact peak. Group elapsed
includes startup/shutdown and up to one polling interval. Aggregate process CPU
is measured through workload end and excludes shutdown. Page faults vary and do
not show a consistent benefit. [Full rows, samples and preflight](profiles/compiler-concurrent-four-cpu.json).

The existing concurrent runner now accepts `--policy compiler`, retaining the
allocator mode as its default. Reproduce with `--cpus 0,1,2,3 --renders 60`, the
same frozen classpath/user fixture jar as the long reload pair, and a fresh output
directory:

```sh
python3 scripts/experiments/benchmark-allocator-concurrent.py --policy compiler \
  --classpath daemon/android/build/reuse-final-snapshot-frozen/classpath.txt \
  --user-jar daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --java /usr/lib/jvm/java-17-openjdk/bin/java --cpus 0,1,2,3 --renders 60 \
  --output daemon/android/build/compiler-concurrent-reproduction
```

These results strengthen the case for a two-thread setting for the measured
JDK-17 single-sandbox worker profile. They do not establish a universal optimum
for different JDKs, large applications, native-image-heavy content or long
multi-sandbox daemons. Production pooled workers inherit daemon JVM arguments;
spares receive descriptor arguments, so a later default must cover both launch
paths and preserve explicit user choices. No production launch policy changes
in this experiment.

## Reproduction and decision

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/post-snapshot-compiler.json \
  --classpath daemon/android/build/reuse-final-snapshot-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/post-snapshot-compiler-reproduction \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Use a fresh directory and preserve the frozen input manifest. Rebuild/freeze the
PR #76 renderer if those local artifacts are unavailable. For profiling, use the
same render arguments with `--profiler <libasyncProfiler.so>`; exclude its timings
from comparison. The profile was converted with the existing JFR converter using
JVM startup metadata and the worker's warm/listening mark.

The flag is already available on worker JVM launch commands. Keep launch defaults
unchanged until testing sustained sessions and CPU-constrained concurrent workers.
Long-lived workers may amortize extra compilation, and CPU affinity/container
limits may already reduce compiler concurrency. Static dense previews do not
cover arbitrary compute-heavy composables, large consumer apps or native images.
The 300-reload and four-CPU concurrent results support a measured worker
profile; validate the production JDK and launch paths before changing defaults.

This reinforces R10's need to separate application, compiler and VM costs. It is
JVM launch tuning, not a Robolectric bug; applicability and impact in ordinary
unit/screenshot suites remain unmeasured.
