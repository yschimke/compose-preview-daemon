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
The next check is whether CPU/PSS savings persist across hundreds of renders and
actual application reloads without sacrificing warmed throughput.

This reinforces R10's need to separate application, compiler and VM costs. It is
JVM launch tuning, not a Robolectric bug; applicability and impact in ordinary
unit/screenshot suites remain unmeasured.
