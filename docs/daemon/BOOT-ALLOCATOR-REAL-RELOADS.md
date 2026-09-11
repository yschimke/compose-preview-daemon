# Allocator retention with verified per-render loader replacement

A corrected pair of **350 actual application reloads per worker** confirms that
limiting glibc to two arenas reduces retained memory, but leaves substantial
reclaimable pages. Both runs retain **two live application loaders** at all eight
checkpoints and match all **351 paired PNG/UIA frames**.

This supersedes the reload claim in the
[earlier trim experiment](BOOT-ALLOCATOR-TRIM-EXPERIMENT.md), which actually swapped
every 50 renders and used seven application loaders. Its 182.90 MiB reclamation
measurement remains valid for that lower-swap workload. The separate
[three-pair timing experiment](BOOT-ALLOCATOR-ARENAS-EXPERIMENT.md) correctly used
100 loader identities per worker and is unaffected by that correction.

## Results

Same frozen final renderer jars, glibc 2.44 / JDK 17.0.19, 480×1200 density 2,
child-loaded `ReloadDashboardPreview`, 256 MiB maximum / 32 MiB initial heap,
Serial GC with free ratios 10/30. NMT detail, forced GC and histograms every 50
renders make these **diagnostic runs**, not clean CPU or latency comparisons.
Default ran first, then `MALLOC_ARENA_MAX=2`. A single trim occurs at render 300
in each worker, followed by 50 more actual reloads.

| Point | Default PSS MiB | Two arenas PSS MiB |
| --- | ---: | ---: |
| Initial red frame | 551.70 | 515.39 |
| 50 reloads | 819.79 | 627.79 |
| 100 reloads | 848.44 | 640.28 |
| 150 reloads | 896.47 | 679.33 |
| 200 reloads | 905.31 | 686.09 |
| 250 reloads | 916.39 | 689.19 |
| 300, before trim | 964.04 | 702.85 |
| Immediately after trim | 680.23 | 586.93 |
| 350, after 50 more reloads | 720.30 | 672.91 |

Default trim reclaims **283.81 MiB in 21.90 ms**; two-arena trim reclaims
**115.92 MiB in 7.48 ms**. These are individual monotonic durations, not average
policy overhead. Live Java heap is almost identical between variants: about
87.75 MiB initially, 95.52 MiB at 250, 84.04 MiB at 300 and 83.43 MiB at 350.
No growing application-loader retention is observed in this finite run.

The allocator XML confirms **24 arenas versus 2**. Before trim, anonymous PSS
outside NMT reservations is 484.82 versus 168.07 MiB, but the process `[heap]`
mapping is larger with two arenas (71.24 MiB versus 15.62 MiB). Compare both
categories; arena limits can change where malloc allocations reside. Across each
trim, Java-heap, code and metaspace PSS are unchanged. The reclaimed pages come
predominantly from anonymous and `[heap]` mappings.

After 50 more reloads the two-arena worker refills about **86 MiB**, versus
40 MiB for default. This rebound prevents treating the immediate post-trim size
as sustained footprint. Neither curve proves an indefinite memory bound, nor
establishes a suitable trim/recycling interval. The previous clean three-pair
matrix measured a 1.1% process-CPU increase with two arenas; this diagnostic pair
does not override that result.

Derived reports retain full mapping groups, allocator statistics, heap usage,
loader counts, `swapEvery=1`, all checkpoint values and parity results:
[default](profiles/allocator-real-reloads-default.json),
[two arenas](profiles/allocator-real-reloads-arena2.json).

## Reproduce and verify workload identity

Use the compiled probe and archived harness patch from the
[trim experiment](BOOT-ALLOCATOR-TRIM-EXPERIMENT.md), with its same render/JVM
arguments except **`--swap-every 1`** and a fresh output directory per variant.
For default, leave allocator overrides unset; for candidate add
`MALLOC_ARENA_MAX=2`. Both set `TRIM_CHECKPOINT=300` and
`ALLOCATOR_PROBE_LIBRARY=/tmp/liballocator-probe.so`, and request 350 renders.
Do not accidentally change `--swap-every` when changing the checkpoint interval.

Before accepting each result, independently check the saved summary:

```python
assert summary['swapEvery'] == 1
assert len(summary['renders']) == 351
assert len({r['classLoaderHashCode'] for r in summary['renders'][1:]}) == 350
```

The benchmark itself checks that every requested swap changes loader identity.
Verify PNG/UIA hashes across all paired frames, then inspect live histograms for
`UserClassLoaderHolder$ChildFirstURLClassLoader` at every checkpoint. Hash-code
identity checks establish this run's distinct loaders; live-object histograms
address retention. The original low-swap report recorded seven identities, which
should have prevented its stronger reload claim.

## Decision

Keep production settings unchanged pending concurrent-worker, native-image-heavy
and periodic-trim comparisons that measure total CPU and page faults alongside
sustained PSS. There is now verified long-reload evidence for allocator retention,
not a need to reap workers simply because their render count is high. Other
resources and workloads can still require recycling.

Robolectric upstream R09 stays P3: glibc reclamation is established, but no
Robolectric ownership or ordinary unit/screenshot-suite impact is established.
