# Android daemon compiler default

Generated Android `DaemonLaunchPlan` descriptors include `-XX:CICompilerCount=2`.
This favors lower process CPU and memory when starting and reusing render workers.
It is a resource tradeoff, not an unconditional render-latency improvement.

The scope is the daemon launch plan. Shared `RobolectricLaunch.jvmArgs()` remains
neutral, so standalone tools and ordinary test launches that use it do not acquire
this policy. Desktop descriptors do not change. `SubprocessDaemonClientFactory`
forwards descriptor arguments; pooled workers inherit them from the daemon JVM,
and `SandboxSparePool` forwards them to spares. Compiler flags participate in the
spare signature, so workers with different policies are not mixed.

Custom descriptors retain control. To select another count, replace the generated
flag; to restore JVM ergonomics, remove it:

```kotlin
val generated = plan.describe()
val withoutCompilerDefault =
  generated.jvmArgs.filterNot { it.startsWith("-XX:CICompilerCount=") }
val explicit = generated.copy(jvmArgs = withoutCompilerDefault + "-XX:CICompilerCount=4")
val ergonomic = generated.copy(jvmArgs = withoutCompilerDefault)
```

Both descriptors are forwarded as supplied. No heap, collector or recycling
policy changes. There is no schema or public API signature change. The tuning
flag has been exercised on OpenJDK 17 and 21; arbitrary JVM implementations and
workloads are not covered by these measurements.

## Evidence and tradeoff

The [JDK 17 dense-screen profile](BOOT-DENSE-COMPILER-PROFILE.md) found 39.1% of
post-readiness CPU samples on compiler threads. Three short-session pairs reduced
process CPU 13.2% and median end PSS 154 MiB with steady-frame cost essentially
unchanged. The [longer/concurrent follow-up](https://github.com/yschimke/compose-preview-daemon/pull/78)
retained a 5.4% CPU / 208 MiB PSS reduction across 300 actual reloads, and 6.9% CPU /
77 MiB observed peak-PSS reduction with two workers sharing four CPUs. A two-CPU
JVM already chooses two compiler threads in the inspected configuration, so no
extra thread-cap benefit is assumed there.

Production launches use either the descriptor's Java path or the launcher's JVM;
there is no single fixed runtime version. The local default is JDK 21, so it was
measured separately before adopting this default. OpenJDK **21.0.12+8**, three
alternating fresh-worker pairs, red plus 60 dense frames, 480×1200 density 2,
256 MiB Serial heap / 32 MiB initial heap / free ratios 10/30, equal GC logging,
identical frozen renderer jars and no profilers or overlapping builds:

| Metric | JVM default | Two compiler threads |
| --- | ---: | ---: |
| Mean process CPU | 62.330 s | 56.797 s |
| Median readiness CPU | 12.430 s | 8.910 s |
| Median readiness wall | 4.806 s | 4.591 s |
| Median whole-workload wall | 38.749 s | 39.449 s |
| Median last-30 mean render CPU | 596.67 ms | 614.33 ms |
| Median last-30 wall | 529.5 ms | 539.0 ms |
| Median end PSS | 699.18 MiB | 609.73 MiB |

Total CPU falls **8.9%**, and end PSS **89.45 MiB (12.8%)**. However, steady CPU is
**3.0% higher**, and median whole-workload and steady wall time are about **1.8%
higher**. The third candidate is slower than the other two and remains in the
report. The choice prioritizes process CPU/memory and cold readiness over a small
measured steady-latency penalty. Consumers that prioritize warmed latency should
compare their workload and can restore JVM ergonomics as above. Large applications,
other JDKs and native-image-heavy content can have different compilation needs.

All **183 frame pairs and 2,013 exported artifacts match** on JDK 21. This is
within-JDK policy parity, not a claim of cross-JDK identical rendering. Narrow
existing normalizations cover semantics debug ordering and Typeface identities.
[Full trial rows and parity](profiles/compiler-jdk21.json).

## Verification and reproduction

All 56 daemon-client tests pass, including the complete Android/desktop descriptor
goldens and explicit compiler forwarding/removal/signature tests. Shared Robolectric
launch arguments remain unchanged. Formatter and `:daemon-client:checkKotlinAbi`
pass. Benchmarks exercise the same compiler flag that the launch plan now emits;
the descriptor tests verify its forwarding to the spare command.

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/compiler-jdk21.json \
  --classpath daemon/android/build/reuse-final-snapshot-frozen/classpath.txt \
  --java /usr/lib/jvm/java-21-openjdk/bin/java \
  --output daemon/android/build/compiler-jdk21-reproduction \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --density 2 --memory
```

Use a fresh output directory and preserve the ordered frozen-jar manifest. The
frozen renderer includes the merged host ActionBar fix and final-snapshot reuse.
The benchmark chooses its heap/GC explicitly; this change does not impose those
settings on generated production descriptors. CPU and PSS percentages are therefore
specific to the measured configurations, not guarantees for every daemon launch.
