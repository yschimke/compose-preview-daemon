# Boot CPU profile and compiler-setting experiment

Follow-up to [the B2 spike](BOOT-ROADMAP-B2-SPIKE.md). This investigation samples Java and native
stacks instead of attributing elapsed time to a class-loading window.

## Workload and method

Linux, Ryzen 9 3900X (24 logical CPUs), 32 GB RAM, OpenJDK **17.0.19+10**, SDK 35,
Robolectric 4.17-beta-4, 2026-09-10. Android artifacts, extracted native libraries and OS file cache
were warm. Fixtures use the bundled/default fonts; remote font downloads and consumer application
initialization are not exercised. Every trial starts a fresh JVM with `-Xmx1g`; no application CDS archive or verification
opt-out is added. These numbers should not be compared directly with the old 4-core deployment
profile or with a cold filesystem-cache/dependency-download run.

The production `SandboxWorkerMain` boots its real `RobolectricHost`, executes its built-in warm-up,
collects garbage and announces its listening socket. The benchmark then sends a red-square request
and 30 Material-button requests over the worker protocol before requesting graceful shutdown.
It uses fixture classes already on the classpath, not a consumer overlay/adoption classloader swap.
The process's `/proc/<pid>/stat` user+system CPU time includes JIT and GC threads. Readiness wall time
is measured by the parent from process launch to the worker's listening announcement.

Separate diagnostic runs use **async-profiler 4.5**, per-thread CPU timers (`ctimer`, 1 ms), wall
sampling (1 ms), and DWARF native unwinding, recorded from JVM startup. Startup marks split the
recording into sandbox boot, warm render, and the final GC/listen interval. Native thread names are
sampled during profiling because HotSpot can retire compiler threads before readiness. The extra
sampling and profiler overhead are absent from the timing comparison below.

CPU samples describe CPU consumption, not wall time. Inclusive hotspots overlap: class definition
can occur under application creation or Activity launch, and invokedynamic linkage itself defines
classes. Do **not** add those percentages or treat them as independent removable costs. The host
JDK lacks debug symbols: native leaf frames inside `libjvm.so` are unresolved. Java callers and
thread attribution remain useful, but this is not an exact breakdown of HotSpot parser vs verifier
vs compiler internals. Sampling also cannot assign every elapsed millisecond exactly.

## Where the CPU goes

One mapped production-worker diagnostic run spent **2069 ms** booting, **2435 ms** in the
built-in warm render, and **28 ms** collecting/listening afterward (JVM startup marks).
It yielded 7012 CPU samples during boot and 8756 during warm-up. Thread attribution:

| Phase | Compiler threads | Boot/render threads | GC threads | Other |
|---|---:|---:|---:|---:|
| Boot | 69.4% | 27.4% | 2.8% | 0.3% |
| Warm render | 69.2% | 26.2% | 4.2% | 0.4% |

The CPU totals exceed elapsed time because compiler threads run concurrently. They are not
69% of the request's elapsed latency: disabling that work saves CPU much more than wall time,
as the unprofiled experiment confirms.

Inside the **boot/render thread CPU** (a different denominator), inclusive hotspots were:

| Scope | Boot | Warm render |
|---|---:|---:|
| Native runtime initialization | 26.0% | — |
| Application creation | 22.7% | 0.8% |
| BouncyCastle provider setup | 8.3% | — |
| ActivityScenario launch | — | 39.8% |
| Class definition (`defineClass1`) | 20.7% | 23.8% |
| Invokedynamic linkage (`linkCallSiteImpl`) | 14.9% | 17.9% |
| Roborazzi capture | — | 9.7% |

These scopes overlap. The result does **not** support assigning essentially all sandbox time to
invokedynamic linking. BouncyCastle is also much smaller here than the earlier class-window
estimate; upstream constructs it in `AndroidTestEnvironment`'s static initializer and installs it
regardless of the Conscrypt mode, so changing that mode does not avoid construction.

After compiler tuning, the next code experiment should target **Activity launch (B4)**. A successful
replacement must preserve lifecycle/saved-state owners, window attachment, semantics and capture;
some of that 39.8% loads View/Compose classes the replacement will still need. Native-runtime
initialization is the next boot hotspot, but the current shared native loader already caches
extraction, so another extraction-cache change will not eliminate its class/JNI initialization.
A static-shadow fork (B1) still warrants a small controlled experiment; the 15–18% linkage scope is
an observed hotspot, not a bound on its total benefit or permission to bypass shadow semantics.

The machine-readable [phase profile](profiles/boot-cpu-samples.json) records sample counts,
thread groups, overlapping scopes, and unresolved native leaves. Re-running the summarizer creates
interactive flamegraphs for inspecting full caller paths.

## Measured improvement

Three trials per variant, with order rotated between trials. Medians:

| JVM setting | Warm/listening wall time | CPU through readiness | CPU through 31 requests | Median of last 10 Material frames |
|---|---:|---:|---:|---:|
| Default tiered compilation | 4548 ms | 16300 ms | 30850 ms | 160 ms |
| `-XX:TieredStopAtLevel=1` | 4084 ms | 5940 ms | 16560 ms | 160 ms |
| `-XX:CICompilerCount=2` | 4374 ms | 10270 ms | 23450 ms | 154.5 ms |

C1-only compilation reduced readiness by **464 ms (10.2%)** and boot CPU by **63.6%**. Across the
whole short session CPU fell **46.3%**. Limiting compiler threads while retaining C2 reduced
readiness by **174 ms (3.8%)** and boot CPU by **37.0%**. All 279 requested PNGs and corresponding
hierarchy exports matched across variants/trials. There was no observed regression in the final
10-frame median, but these small fixtures and 30-frame sessions do not establish long-run throughput
for arbitrary compute-heavy composables.

[Per-trial measurements](profiles/boot-jit-comparison.json) retain the render latency sequence,
readiness and CPU values. Profiled timings are deliberately excluded from that file.

### How to use the result

For a controlled deployment experiment with short-lived workers, pass `-XX:TieredStopAtLevel=1`
on the **worker JVM command line**. For a less restrictive experiment that retains optimizing
compilation, use `-XX:CICompilerCount=2`. Normal pool workers inherit the daemon's non-agent JVM
arguments; externally launched spare workers need the flag on their own launch command. Adding a
property during spare adoption is too late to change a JVM compiler setting.

Neither setting becomes the production default here. Long-lived workers may eventually benefit
from C2 on complex workloads; a representative catalog and a sustained-load run should decide that
tradeoff. A CPU-limited deployment also starts fewer compiler threads by default, so the return
may differ from this 24-logical-CPU host. The flags are immediately usable without a Robolectric fork, and removing them restores
the existing JVM policy.

## Reproduce and inspect

Build the runtime classpath once:

```bash
./gradlew :daemon:android:writeDaemonClasspath
```

Each output directory must be new. The benchmark is Linux-only (`/proc` CPU counters):

```bash
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/daemon-harness/runtime-classpath.txt \
  --java /path/to/jdk17/bin/java --output /tmp/worker-default --renders 30

# Same command, with a fresh output directory, plus either:
# --jvm-arg=-XX:TieredStopAtLevel=1
# --jvm-arg=-XX:CICompilerCount=2
```

For a separate diagnostic run, add `--profiler /path/to/libasyncProfiler.so` from the
[official async-profiler 4.5 release](https://github.com/async-profiler/async-profiler/releases/tag/v4.5).
This writes `profile.jfr`, `worker.log` and `summary.json`. Graceful protocol shutdown flushes the
recording; killing a worker is not a valid way to finish a profile.

```bash
python3 scripts/summarize-worker-profile.py \
  --directory /tmp/worker-profile --converter /path/to/jfr-converter.jar \
  --java /path/to/jdk17/bin/java --output /tmp/worker-profile-report
```

The report contains phase-specific CPU and wall flamegraphs, collapsed stacks and `report.json`.
Use the CPU flamegraphs for resource attribution; wall flamegraphs include multiple concurrent
threads (including the parent host thread waiting on the sandbox), so their totals are not elapsed
time. The existing `SandboxBootstrapSpikeTest` also accepts `COMPOSEAI_BOOT_SPIKE_PROFILER` and a
JSON array in `COMPOSEAI_BOOT_SPIKE_JVM_ARGS` for smaller JUnit/direct comparisons, and logs phase
wall timestamps and process CPU counters. It is still opt-in.

The [method-handle follow-up](BOOT-METHOD-HANDLE-EXPERIMENT.md) extends this to rotated
91-frame mixed workloads and records which apparent JVM-setting gains survive repetition.
