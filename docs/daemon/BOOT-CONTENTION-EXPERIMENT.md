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
