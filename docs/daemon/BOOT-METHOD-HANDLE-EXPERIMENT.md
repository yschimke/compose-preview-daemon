# Method-handle compilation and sustained worker rendering

Follow-up to [the CPU profile](BOOT-CPU-PROFILE.md), using the actual spare-worker socket protocol.
The experiment changes JVM launch arguments only. No production defaults change.

## Why test this

JDK 17's `java.lang.invoke.MethodHandleStatics` reads two internal properties:
`java.lang.invoke.MethodHandle.COMPILE_THRESHOLD` (default 0) and
`java.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD` (default 127). These are unsupported JDK
implementation switches, not daemon tunables. They were verified in the installed **17.0.19+10**
bytecode with `javap -c -p`; their names or behavior must not be assumed portable to another JDK.
The profile's substantial method-handle/class-definition work made deferred compilation worth
measuring before attempting a Robolectric instrumentation fork.

A four-process screen, each with 13 mixed frames, found:

| Setting | Ready wall time | Ready CPU | Last-six-frame median |
| --- | ---: | ---: | ---: |
| Default | 5557 ms | 15890 ms | 169 ms |
| Compile threshold 30 | 4631 ms | 15670 ms | 172.5 ms |
| Customization disabled (-1) | 5105 ms | 18100 ms | 185.5 ms |
| Compilation disabled (-1) | 5466 ms | 17980 ms | 350 ms |

All PNG/hierarchy hashes matched. These are single trials in fixed order, so the apparent threshold-30
startup win was only a lead. Disabling compilation regressed later rendering substantially.

## Repeated result

Three trials per variant, rotated process order, **1092 frames** total. Every process starts with
RedSquare, then renders 30 cycles of a Material button, serif text and a dialog. All PNG bytes and
default UI Automator hierarchy bytes match across all processes and repetitions. This does not
prove parity of every semantics property or of a whole consumer catalog.

Linux Ryzen 9 3900X, 24 logical CPUs, JDK 17.0.19+10, SDK 35, `-Xmx1g`, warm dependencies and OS
file cache. Each trial starts a fresh production worker; no profiler or CDS archive is added.
Wall time includes the built-in warm render and readiness announcement. CPU is process user+system
CPU and includes JIT/GC threads. [Recorded measurements](profiles/method-handle-matrix.json) retain
per-frame timings, launch settings, host load and expected hashes.

| Variant | Median ready wall | Median ready CPU | Median of last-30-frame medians | Median total CPU (91 frames) |
| --- | ---: | ---: | ---: | ---: |
| Default | 4437 ms | 16290 ms | 170.5 ms | 59170 ms |
| Compile threshold 30 | 4448 ms | 16600 ms | 169 ms | 56840 ms |
| C1 only | 4087 ms | 6000 ms | 164 ms | 39710 ms |
| C1 + compile threshold 30 | 4056 ms | 5590 ms | 153 ms | 37030 ms |

**Threshold 30 alone does not reproduce the initial startup gain.** C1 again makes the much larger
CPU difference. Threshold 30 adds a small startup change to C1 (31 ms in the medians) and a larger
late-frame improvement on this workload. The combination uses about 66% less readiness CPU and 37%
less total CPU than default, with about 9% lower readiness wall time. Three trials are directional
measurements, not a confidence interval or proof of a globally optimal configuration.

## Reproduce and extend

`benchmark-worker-startup.py` now accepts repeated `--fixture` arguments, records each frame's
fixture and PNG/hierarchy hashes, and records total wall time and starting host load.
`benchmark-worker-matrix.py` runs variants sequentially in rotated order and fails on any
PNG/hierarchy mismatch. It saves individual logs/summaries and an incremental matrix summary.

```json
[
  {"name": "default", "jvmArgs": []},
  {"name": "defer30", "jvmArgs": ["-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30"]},
  {"name": "c1", "jvmArgs": ["-XX:TieredStopAtLevel=1"]},
  {"name": "c1-defer30", "jvmArgs": ["-XX:TieredStopAtLevel=1", "-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30"]}
]
```

Save that as `matrix.json`, then:

```sh
./gradlew :daemon:android:writeDaemonClasspath
python3 scripts/benchmark-worker-matrix.py \
  --matrix matrix.json --java /path/to/jdk17/bin/java \
  --classpath daemon/android/build/daemon-harness/runtime-classpath.txt \
  --output /tmp/method-handle-trials --trials 3 --renders 90 \
  --fixture MaterialButtonInteractionState --fixture SerifTextPreview \
  --fixture DialogWindowSurface
```

Use a fresh output directory. A variant can supply its own `java` path to compare JDK versions.
The later matrix runner automates the same rotation and hash checks used to collect the table.

## Broader JDK comparison

A follow-up uses twelve fixtures: the three above plus an opaque image, linear and radial
gradients, annotated/emoji text, graphics layers and a wide vector, an icon input row, a lazy list,
an editable text field, and a generic outline shape. Each worker renders RedSquare and six cycles
of those twelve fixtures. All variants use C1 plus compile threshold 30 and native access enabled;
JDK effects are compared with those settings held constant.

| JDK | Median ready wall | Median ready CPU | Median of last-30-frame medians | Median total CPU (73 frames) |
| --- | ---: | ---: | ---: | ---: |
| 17 | 4009 ms | 5850 ms | 160.5 ms | 34420 ms |
| 21 | 4044 ms | 5520 ms | 168.5 ms | 33560 ms |
| 25 | 3858 ms | 5450 ms | 154.5 ms | 31580 ms |

Three rotated trials each, **657 frames with exact decoded-pixel and default-hierarchy parity**.
[Raw results](profiles/jdk-mixed-matrix.json) include exact installed JDK versions. JDK 25 improves
median readiness by 151 ms (about 4%) versus JDK 17 here; JDK 21 does not improve wall time. These
broader numbers must not be compared directly with the earlier three-fixture throughput table.

The first byte-based JDK screen deliberately failed on JDK 25: all thirteen PNG encodings differed
while decoded RGBA dimensions/pixels and hierarchy bytes were identical. This is not a rendering
regression, but it does matter to consumers that key caches by PNG bytes. The matrix runner retains
byte comparison by default. Its explicit `--compare-pixels` option (requires Pillow) compares decoded
pixels and records `pngByteParity` separately. Decoding happens **after** the worker exits so it
cannot distort the measured workload or JIT opportunity between requests. JDK 21 also preserved
PNG bytes; JDK 25 did not. No runtime/JDK default is changed on this evidence.

To reproduce, provide per-variant `java` paths in the matrix, keep the same JVM arguments, pass
`--compare-pixels --renders 72`, and repeat `--fixture` for the twelve function names recorded in
the raw result. The initial JDK screen used one cycle (12 renders) and default tiered compilation;
it is exploratory evidence only, not the repeated timing result above.

## Remaining exploration

The cold path still takes about four seconds here; this is not the end of the startup work.
Next candidates include more detailed application/native boot attribution, the general
Activity-free host, class archives and closed-world shadow binding. In particular,
`InvokeDynamicSupport.bindWithFallback` builds an exact-invoker/folded fallback and SwitchPoint
guard for each linked site. A fixed-shadow experiment can test how much that invalidation
machinery costs before committing to a full static-instrumentation fork. It must explicitly
exclude shadow-map changes; removing invalidation from general Robolectric would be incorrect.

One source check also corrects an assumption in the original handoff: Robolectric 4.17-beta-4's
`AndroidTestEnvironment` constructs its static BouncyCastle provider unconditionally. Conscrypt ON
still constructs it and later ensures BC is registered. Switching Conscrypt mode cannot eliminate
that initialization; a lazy-provider experiment needs an actual initialization-path change and
crypto-behavior checks, not merely a different launch flag.
