# Periodic native trimming with sampled render metrics

Sampling post-GC metrics reduces CPU and latency but increases resident memory.
This experiment tests whether HotSpot's existing periodic native-heap trimmer
recovers some of that cost while keeping the CPU gain. No runtime defaults change.

## Runtime support and scope

The installed OpenJDK **17.0.19+10** exposes `TrimNativeHeapInterval` as a product
flag, defaulting to 0. Launching with `-XX:TrimNativeHeapInterval=5000` and
`-Xlog:trimnative=debug` confirms that the native heap trimmer starts. The installed
21.0.12+8 also exposes the flag. Support must be verified on the actual vendor/update
build, not inferred merely from the major version.

The [OpenJDK option documentation](https://github.com/openjdk/jdk/blob/master/src/java.base/share/man/java.md)
describes periodic native trimming on Linux/glibc, with shorter intervals trading
more eager reclamation for overhead. This returns eligible allocator pages; it
does not free live objects or establish that retained native memory is a leak.
`SandboxSparePool.Config.trimNativeHeapMs` already exposes an optional launch path
with a conservative JDK-21-or-newer/current-launcher guard. This experiment passes
the verified JVM flag directly and does not relax that guard.

## Controlled comparison

Three rotated trios use identical frozen jars from the sampled-metrics implementation,
JDK17, Serial GC, Xmx256m/Xms32m, free ratios 10/30 and two compiler threads. All
variants use the same warmed font cache in offline mode and equal trim logging.
Each worker renders red followed by 60 dense-dashboard frames at 480×1200, density 2.

1. Measure every render; trimming disabled.
2. Measure every fifth render; trimming disabled.
3. Measure every fifth render; trim every 5000 ms.

The [full report](profiles/sampled-metrics-trim.json) retains every run, artifact
comparison, trim event, font-cache hash and late-RSS summary.

| Metric | Every render | Every fifth | Every fifth + trim |
| --- | ---: | ---: | ---: |
| Mean total CPU | 54.167 s | 49.537 s | 49.607 s |
| Median total wall | 32.314 s | 28.093 s | 28.137 s |
| Median steady-frame CPU | 576.3 ms | 504.0 ms | 507.7 ms |
| Median steady-frame wall | 421.5 ms | 315.0 ms | 313.5 ms |
| Median end PSS | 572.00 MiB | 636.77 MiB | 582.74 MiB |
| Median of final-10-second median RSS | 603.61 MiB | 649.27 MiB | 591.22 MiB |

Relative to sampling alone, trimming reduces median end PSS **54.03 MiB (8.5%)**
with **0.14% higher mean CPU** and almost unchanged total wall. That CPU difference
is too small to establish a precise overhead. End PSS falls in all three paired
comparisons: 643.1→582.7, 636.8→586.7 and 607.6→579.2 MiB. Native-trim memory can
refill between events, so end PSS is not treated as a durable floor. The separate
late-RSS measurement supports a sustained difference during this short workload;
RSS and PSS are different measurements and should not be compared directly.

Relative to every-render measurement, the combined profile uses **8.4% less CPU**
and **12.9% less total wall**, with **10.74 MiB (1.9%) higher median end PSS**.
This recovers most of the sampled-metrics memory penalty in this single-worker
workload. It does not establish a universal memory reduction.

All **366 paired frames and 4,026 data artifacts match**: each of the two candidate
variants is compared against every-render measurement in all three trials. Only
the existing documented diagnostic-order and Typeface-identity fields are normalized.
The sampled variants continue emitting complete metrics only on measured frames;
trimming does not change that schedule or turn skipped frames into heap measurements.

Each trimmed run logs five actual periodic trims. Maximum logged trim duration per
run is 7.280, 4.053 and 4.385 ms. These are operation durations, not exclusive CPU
cost or an end-to-end stall bound. Do not sum reclaimed bytes across trims as if
they were distinct permanent savings: freed pages can be allocated and trimmed again.

## Decision and next validation

Keep this as a measured opt-in launch-policy candidate. Five seconds was tested,
not established as optimal. It has not yet been tested with concurrent workers,
long application-reload runs, another collector, another OS/libc or older JDK
updates. Do not infer the same saving on a server from a single-worker PSS endpoint.
The next step is a controlled concurrent/reload comparison with the same warmed
fonts, CPU limits and output checks, retaining whole-process CPU and combined PSS.

This is a HotSpot/glibc policy opportunity, not a Robolectric defect or a reason
to add another Robolectric native-memory API. The existing metrics default, heap
limits, worker recycling behavior and spare-pool trim default remain unchanged.

## Reproduction

Prepare the warmed font cache described in the font-cache benchmark correction,
then use a fresh output directory:

```sh
python3 scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/sampled-metrics-trim.json \
  --classpath daemon/android/build/sampled-metrics-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/sampled-metrics-trim-matrix \
  --trials 3 --renders 60 --fixture DenseDashboardPreview \
  --width 480 --height 1200 --memory
```
