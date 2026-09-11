# Cost of per-render explicit GC

`SandboxMeasurement.collect` calls `System.gc()` after the engine stops its render
timer. Earlier dense-screen runs showed 65–87 ms between that timer and the external
request duration, but that gap also includes result handling. This experiment
measures the GC pauses and the net worker cost separately.

## Procedure

Use the same frozen worker jars at `e7802398`, JDK 17, unpatched Robolectric, default
JVM compilation and G1 with the harness's 1 GiB cap. Neither profile uses application
CDS or the spare launcher's heap-free-ratio overrides. Both log `-Xlog:gc*=info`;
only the experimental profile adds `-XX:+DisableExplicitGC`. Three sequential trials
rotate variant order. Each worker renders red then 36 dense dashboard frames at
480 by 1200, with RSS sampling and final PSS.

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/worker-gc.json \
  --classpath daemon/android/build/locale-weak-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output /tmp/worker-gc-results --trials 3 --renders 36 \
  --width 480 --height 1200 --memory --fixture DenseDashboardPreview
python scripts/summarize-worker-gc.py /tmp/worker-gc-results/*-gc
```

The matrix requires a preserved classpath produced by `freeze-classpath.py` after
`:daemon:android:writeDaemonClasspath`; it does not point at mutable build jars.
The summary script accepts completed worker directories and parses completed GC
pause lines, not the separate pause-start records. GC CPU fields are rounded
HotSpot diagnostic samples, not an exclusive CPU profile; total worker CPU is the
net comparison. Logging itself adds diagnostic overhead.

## Results

[All rows, GC events and parity results](profiles/worker-explicit-gc.json) retain the
evidence. Entries below are medians across the three worker trials.

| Metric | Explicit GC | Explicit GC disabled |
| --- | ---: | ---: |
| Last-30 median request wall | 997.5 ms | 957.0 ms |
| Last-30 mean worker CPU/request | 1466 ms | 1423 ms |
| Total worker CPU | 78.76 s | 78.04 s |
| Final PSS | 1014.5 MiB | 1061.8 MiB |

The explicit profile performs 39 full collections per worker (including startup),
with about 2.2 s total pause time and a late median of 62.5–63.6 ms per forced full
collection. Disabling them removes those pauses, but automatic collections replace
some of the work: total worker CPU falls only **0.9%**, while late request wall time
falls **4.1%** and final PSS increases **47.2 MiB / 4.7%**. Late mean request CPU is
2.9% lower. These small CPU differences across three trials do not establish a
reliable sustained CPU saving.

All **222 PNG/UIA frame checks** pass, as do **333 paired data artifact checks**.
Only 36 layout JSON comparisons need diagnostic Role/ContentDescription order
normalization; SVG and structured semantics remain exact. One explicit-GC worker
has a 9.1 s readiness outlier with ordinary startup CPU, so whole-workload wall
medians are not used to attribute a startup benefit to this change.

## Decision

Do not change production behavior on this evidence. The loaded-server tradeoff is
modest latency improvement with more resident memory and no convincing total CPU
reduction. Disabling explicit GC also means `heapAfterGcMb` no longer represents a
post-GC reading. Any future sampled or optional telemetry mode must state its
semantics explicitly rather than silently keeping that field name with a different
meaning. The current experiment is a diagnostic flag comparison, not a launch
recommendation or a renderer code change.
