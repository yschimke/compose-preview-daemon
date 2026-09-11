# JDK 25 AOT cache versus dynamic CDS

This experiment tests whether AOT linking improves on ordinary dynamic CDS for a
real SDK-35 spare worker. Every variant uses the same JDK 25.0.4.1, constant-binding
jar, C1-only compilation, method-handle threshold 30 and native-access setting.
Production launch settings remain unchanged.

Both caches were trained with RedSquare followed by three Material button renders
and graceful worker shutdown. The timed workload uses RedSquare plus six cycles
through the twelve-fixture set in the [binding report](BOOT-FROZEN-BINDINGS-EXPERIMENT.md).
Each variant starts a fresh JVM. Training time is separate from archive reuse, and
all measurements use warm filesystem/dependency caches.

## Reproduce

Prepare the constant-binding classpath as described in the binding report. Train
the two caches separately, with new output directories:

```sh
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/frozen-experiment/constant-classpath.txt \
  --java /usr/lib/jvm/java-25-openjdk/bin/java \
  --output daemon/android/build/jdk25-aot-training --renders 3 \
  --jvm-arg=-XX:TieredStopAtLevel=1 \
  --jvm-arg=-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30 \
  --jvm-arg=--enable-native-access=ALL-UNNAMED \
  --jvm-arg=-XX:AOTCacheOutput=/tmp/constant-jdk25.aot
```

For ordinary CDS, repeat with a new output directory and replace `AOTCacheOutput`
with `-XX:ArchiveClassesAtExit=/tmp/constant-jdk25.jsa`.

Use `benchmark-worker-matrix.py` with three variants and the common flags above:

- `no-cache`: no additional application cache flags (the JDK's default base archive remains).
- `cds`: `-Xshare:on -XX:SharedArchiveFile=/tmp/constant-jdk25.jsa`.
- `aot`: `-XX:AOTMode=on -XX:AOTCache=/tmp/constant-jdk25.aot`.

Run three rotated trials, 72 fixture requests after the initial red frame, and the
same twelve `--fixture` arguments as the binding report. Required cache modes make
an incompatible/missing archive an error rather than a silent uncached run. Keep
jar paths and contents fixed after training.

This is a within-JDK comparison using PNG byte hashes and UIA hashes. Comparisons
to JDK 17 require decoded pixel comparison because the JDKs encode identical pixels
into different PNG bytes, as recorded in the earlier JVM experiment.

## Results

Three rotated trials per variant, 657 frames total, every PNG byte hash and UIA
hash matching the JDK 25 control. A separate 73-frame comparison against the
JDK 17 constant-binding/CDS reference matches every decoded RGBA pixel and UIA
hash. Median values:

| Cache | Ready wall ms | Ready CPU ms | Render ms | Total wall ms | Total CPU ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| no-cache | 3615 | 5080.0 | 138.0 | 15161 | 26180.0 |
| cds | 2480 | 3740.0 | 137.5 | 13725 | 24050.0 |
| aot | 2565 | 4190.0 | 138.5 | 14106 | 24440.0 |

Ordinary CDS is the leading configuration. AOT is 85 ms (3.4%) slower to readiness
and 381 ms (2.8%) slower over the workload; it does not demonstrate an advantage
here. Its trained cache is 132,579,328 bytes, compared with the CDS size recorded
below. This is a result for this worker/configuration, not a general verdict on
JDK AOT. Small differences still need repetition on the eventual deployment host.

The 2.480s median is a fresh JVM using a previously trained cache with experimental
constant bindings. It is not first-run training time or production-default latency.
[Raw rotated trials](profiles/jdk25-aot-matrix.json).

CDS cache size: 108,376,064 bytes.

The chosen JDK 25 CDS configuration also passes `--exercise-recovery`: configure,
user-classloader swap, the expected throwing-composable diagnostic, a subsequent
identical red render and graceful shutdown. This supplements static parity; full
JDK 25 interaction/IME compatibility is not claimed.
[Validation and per-run host load](profiles/jdk25-aot-validation.json).
