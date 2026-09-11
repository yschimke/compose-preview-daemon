# Attribute reload memory at matching checkpoints

The in-memory settling experiment ended near 79.8 MiB live heap but around
900 MiB PSS after 300 application reloads. Its shutdown NMT report was not
contemporaneous with the earlier PSS checkpoints, and commitment is not residency.
The harness now has an opt-in `--native-memory-checkpoints` diagnostic that records
NMT detail, `/proc/<pid>/smaps` and JVM flags at each forced-GC checkpoint. It requires
`-XX:NativeMemoryTracking=detail` and does not change normal benchmark behavior.
Snapshots are sequential rather than atomic; no application request runs between
these diagnostics, but JVM background work can continue.

`scripts/summarize-native-memory.py` attributes PSS only when an entire Linux VMA
belongs to one NMT reservation. VMAs crossing reservation boundaries remain in an
explicit mixed category; no proportional division is invented. Anonymous mappings
outside reservations include JVM malloc arenas and retained allocator pages as
well as third-party allocations, so they must not be labelled an upstream native
leak. [JDK17's NMT documentation](https://docs.oracle.com/en/java/javase/17/vm/native-memory-tracking.html)
also excludes native allocations in JDK class libraries and describes NMT's runtime
overhead; diagnostic timings are not optimization evidence. Four parser tests cover complete attribution, boundary overlaps, conservation
of PSS and rejecting incomplete inputs. A three-frame real-worker smoke run validates
the output format and checkpoint collection.

The comparative diagnostic uses the final in-memory renderer from #72, the same
256 MiB maximum heap, Serial GC, free ratios 10/30 and NMT detail. One variant keeps
the ergonomic initial heap; the other adds `-Xms32m`. Each performs 300 actual
application-loader replacements with the child-loaded service dashboard, reuses
output names, and collects checkpoints/histograms every 50 renders. Timings from
these instrumented runs are not CPU-performance evidence. No launch default changes.

The ergonomic initial heap on this host/JDK17 is 256 MiB despite an 8 MiB reported
MinHeapSize. The smoke snapshot shows 256 MiB heap committed, about 88 MiB live,
and 176 MiB heap PSS. The smaller initial-heap experiment tests an explicit resource
policy; it does not assume that reducing commitment will solve all RSS/PSS growth.

## Completed reload comparison

[Contemporaneous attribution](profiles/native-memory-checkpoints.json) and
[heap/loader parity checks](profiles/initial-heap-reload-256.json) cover both
300-reload runs. Each has 300 distinct application-loader identities, two retained
application loaders at every checkpoint, and all 301 PNG/UIA frame pairs match.
Final live heap is 79.78 MiB for ergonomic initial size and 82.94 MiB for `-Xms32m`;
neither result establishes an indefinite lifetime bound.

| At checkpoint 300 | Ergonomic initial heap | `-Xms32m` |
| --- | --- | --- |
| Total PSS from smaps (MiB) | 920.12 | 855.18 |
| Heap committed (MiB) | 256.00 | 166.20 |
| Heap PSS (MiB) | 192.00 | 162.12 |
| JIT-code reservation PSS (MiB) | 116.61 | 117.00 |
| Anonymous PSS outside NMT reservations (MiB) | 413.92 | 398.72 |

The 64.94 MiB total PSS difference is a single instrumented pair, not a guaranteed
saving. Heap residency accounts for about 29.88 MiB of it. Remaining differences
include anonymous malloc mappings; this does not identify allocator ownership.
Some VMAs crossing reservation boundaries remain explicitly unassigned. In
particular, about 19 MiB overlapping a Metaspace reservation in the first run
must not be mistaken for lower metaspace use relative to the second run.

Within the ergonomic-initial run, PSS grows 589.27 → 920.12 MiB. Heap residency
grows only 176 → 192 MiB; code residency grows 22.65 → 116.61 MiB; anonymous PSS
outside NMT reservations grows 207.39 → 413.92 MiB. The major growth therefore
lies outside the Java heap. JIT warm-up and allocator-retained pages are plausible
contributors; these snapshots do not establish a Robolectric leak. Source/category
ownership must be demonstrated before reporting anything upstream.

## Uninstrumented initial-heap tradeoff

[Three rotated 61-frame dense-screen pairs](profiles/initial-heap-32-serial-256.json)
compare identical final-renderer jars and 256 MiB Serial-GC settings, differing only
in `-Xms32m`. They omit NMT, forced-GC checkpoints and histograms. No builds or
profiles overlap. All 183 frame pairs and 2,013 exported artifacts match, with only
the previously documented diagnostic-order/Typeface-identity normalizations.

| Metric | Ergonomic initial heap | `-Xms32m` |
| --- | --- | --- |
| Total CPU per trial (s) | 65.00 / 65.97 / 65.07 | 65.83 / 65.27 / 65.04 |
| Mean total CPU (s) | 65.347 | 65.380 |
| Median last-30 mean CPU (ms) | 593.0 | 604.3 |
| Median last-30 wall latency (ms) | 516.0 | 523.0 |
| Median ready wall latency (ms) | 4532 | 4762 |
| Median total wall latency (ms) | 38007 | 38409 |
| End PSS per trial (MiB) | 743.7 / 828.7 / 803.7 | 727.3 / 683.5 / 708.1 |
| Median end PSS (MiB) | 803.7 | 708.1 |

Mean CPU is essentially unchanged (+0.05%), steady CPU rises 1.9%, steady median
latency rises 1.4%, and readiness median rises 5.1%. Median end PSS falls 95.6 MiB
(11.9%), with substantial run-to-run variability. This supports `-Xms32m` as a
memory-focused option for this exact profile, not a universal speed win.

**Decision:** retain production launch defaults. The production launch profile,
other maximum heaps/JDKs and concurrent workers still need validation before a
general initial-heap change. The immediate larger investigation is anonymous
memory outside NMT reservations: distinguish live allocations from allocator-held
free pages and JIT-compilation residue before attributing ownership or choosing a
recycling policy. No new Robolectric leak is established.

## Reproduction

The memory run uses the existing `benchmark-worker-startup.py` with `--renders 300`,
`--class-name benchmark.screens.ReloadDashboardPreviewsKt`,
`--fixture ReloadDashboardPreview`, the child-loaded fixture jar, `--swap-every 1`,
`--reuse-output`, `--gc-checkpoint-every 50`, `--heap-histograms`,
`--native-memory-checkpoints`, and `--jvm-arg=-XX:NativeMemoryTracking=detail`.
Other arguments and fixture identities are retained in the linked reports. Run
`scripts/summarize-native-memory.py <run-directory> --output <report.json>` to
correlate NMT and smaps. `scripts/test-summarize-native-memory.py` tests the parser.

The uninstrumented matrix is
`scripts/experiments/initial-heap-32-serial-256.json`, with three trials, 60 dense
renders plus the initial red frame, 480×1200 pixels, density 2 and `--memory`.
Frozen classpaths retain their inputs and SHA-256 manifests under each build output.
Do not run compilation, another benchmark or profiling concurrently with timings.
