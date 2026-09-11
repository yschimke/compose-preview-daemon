# Sharing semantics projections within one capture

After sharing layout extraction, the dense-screen profile still showed three
independent semantics projections: the JSON artifact, wireframe/spatial semantics,
and layered SVG. JSON projection alone took about 30 ms per late request. Reuse
within a captured frame removes duplicate tree walks without changing the products
or forcing consumers to agree on density semantics.

## Scope and lifetime

Android and desktop install a fresh `ComposeSemanticsSnapshot` in each post-capture
context. It projects lazily and caches by the requested nullable density. `null`
remains distinct from `1f`: unknown density must still be omitted from the wire
payload. The normal renderer consumers request the same explicit density and share
one projection; standalone callers without a snapshot retain the original path.
Failed builds are retryable. Neither the snapshot nor its root is stored on an
extension or retained across captures.

## Inputs and validation

The baseline is the preserved worker after the locale-cache fix in #60. Both
classpath manifests contain identical dependencies and fixture jars; only the two
Android main-jar entries and the layout-inspector connector differ. JDK 17 uses
unmodified Robolectric, default compilation tiers and G1 with a 1 GiB cap. There is
no application CDS or spare-launcher heap-free-ratio override.

The 238 connector tests and 23 desktop integration tests pass. Smoke renders at
densities 1 and 2 have identical PNG/UIA hashes. The six directly affected products
(layout JSON, semantics JSON, SVG, wireframe PNG/SVG and spatial semantics) match,
allowing only diagnostic Role/ContentDescription ordering in layout JSON. Other
artifacts are also checked; font reports contain process-specific
`android.graphics.Typeface@<hex>` resolved-family strings, so their identities are
ignored only in that field while all other font metadata remains exact.

The benchmark harness now passes and records `--density`; comparison tools keep
different densities distinct. This does not change production renderer defaults.

## Performance procedure

Three rotated sequential trials compare fresh baseline/candidate workers. Each
renders red then 60 dense dashboard requests at 480 by 1200, density 1. Reports keep
whole-worker CPU and the final 30 request CPU/wall separately, with RSS sampling and
final PSS. There are no extra GC checkpoints or profiler instrumentation.

## Initial results and unresolved CPU variance

[Raw trial and phase measurements](profiles/semantics-snapshot.json) retain all
three trials for both default compilation (61 frames/worker) and the separate
C1-only isolation (37 frames/worker). The latter adds only
`-XX:TieredStopAtLevel=1`; it is not the earlier Serial/256 MiB/frozen-binding profile.

| JVM profile | Baseline total CPU seconds, trials 0/1/2 | Shared total CPU seconds, trials 0/1/2 |
| --- | --- | --- |
| Default compilation | 113.11 / 154.29 / 180.91 | 110.00 / 111.85 / 109.17 |
| C1 only | 71.14 / 71.13 / 71.78 | 111.76 / 100.00 / 67.81 |

The default medians favor sharing, but the C1 result reverses that apparent CPU
benefit in two trials. Do not headline a percentage CPU improvement from these
medians: two removed projections cannot yet explain this worker-level variability.
In C1 trial 0, median wireframe and SVG phases fall from 137/92 ms to 101/55 ms,
but whole-worker CPU rises. This needs attribution to application, compiler and
GC work before accepting a production performance claim.

C1 end PSS is 712.7/717.7/713.8 MiB baseline versus 788.7/802.9/721.5 MiB shared.
No memory saving is established. Output sharing remains per-capture, and reload
lifetime validation is still required. Follow-up profiler/GC diagnostics must be
reported separately from these unprofiled timings.


## Artifact parity and diagnostic profile

[All-artifact parity results](profiles/semantics-snapshot-parity.json) cover eight
matched pairs: both density smoke tests, all default trials and all C1 trials.
That is 300 corresponding frames (600 rendered frames) and 3,300 corresponding
data-artifact comparisons across eleven products. The optional normalizations
remain limited to diagnostic semantics property order and the font-report Typeface
object identity field. PNG bytes and UIA hashes remain exact.

A separate [async-profiler diagnostic pair](profiles/semantics-snapshot-profile.json)
uses C1, G1/1 GiB, 36 dense renders, 1 ms CPU/wall sampling and GC logging. From
worker readiness to workload end, application CPU samples fall 52,203 → 49,288
(about 5.6%), compiler samples are 7,938 → 7,978, and GC samples rise 7,531 → 8,240.
Whole-worker CPU is 81.44 → 79.65 seconds. These are instrumented diagnostics,
not another unprofiled timing trial. They support reduced application work but
**do not reproduce or explain the large high-CPU outliers**. G1 reports 18 parallel
and 5 concurrent workers on this 24-CPU host; testing a bounded GC-worker budget
is a useful next isolation, not yet a recommended setting.


The harness now defaults to density 2, preserving the old omitted RenderSpec
value (confirmed by historical semantics artifacts). The matrices above ran with
the interim density-1 default: reproduce them with explicit `--density 1`.
The density-1 reload check completed 60 distinct application reloads at 256 MiB,
with two child loaders at all seven forced-GC checkpoints and stable dense images.
It is not comparable to the historical density-2 reload images. Missing density in historical summaries is interpreted as 2 by the
comparison/growth tools, not silently as 1.


## Matching-density reload result

The [density-2, 256 MiB Serial-GC reload check](profiles/semantics-reload-256-density2.json)
completed 60 distinct application loader replacements. All 61 PNG/UIA pairs match
the historical locale-fix baseline at density 2. Live child-loader counts remain
**2/2/2/2/2/2/2** at checkpoints 0/10/20/30/40/50/60. This checks real child-loaded
application classes, not merely repeated renders through one loader.

Post-GC heap usage is 87.83/90.20/90.40/90.52/90.59/90.69/90.75 MiB; PSS is
553.39/690.36/736.07/766.59/739.88/746.82/750.15 MiB. There is no observed per-reload
loader accumulation, but this short run does not establish indefinite heap or
native-memory bounds. Forced GC/histogram overhead is included in its timing,
so use the unprofiled matrices for performance conclusions. Heap defaults and
worker recycling policy remain unchanged.


## Next local hotspots

The candidate's post-ready CPU stacks attribute 8,403 inclusive samples to regex
work under `ComposeLayoutInspector.canonicalizeJvmRuntimeIdentity` (of 49,288
application-group samples in this diagnostic run). The function constructs three
regexes for each value and searches even strings with no runtime-identity markers.
These counts overlap other stack frames and are not an additive speedup forecast.
A separate follow-up can precompile the exact patterns and skip impossible matches,
with adversarial identity-string tests and complete artifact parity. This is our
extractor overhead, not a Robolectric upstream defect.

The same profile points at reflection enumeration in modifier inspection and
layout access. Before caching reflective members, prove classloader lifetime
safety (e.g. class-owned cache semantics), preserve missing-member fallbacks, and
rerun the application-reload check. Avoid reintroducing a strong global map keyed
by application classes.


## Bounded-GC isolation

[Three further rotated trials](profiles/semantics-snapshot-bounded-gc.json) add
`-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Xlog:gc*=info` to the C1-only profile,
keeping G1, the 1 GiB cap, explicit density 1 and all jar inputs unchanged. These
are a separate JVM profile, not extra samples to pool with default compilation.
All 111 corresponding frames and 1,221 corresponding data artifacts pass parity.

Baseline total CPU is 66.13/65.68/74.51 s; sharing is 61.99/62.09/61.94 s. Median
whole-worker CPU falls **6.3%**, last-30 mean CPU **7.2%**, and last-30 median wall
**7.3%**. This supports a real application-work reduction under this profile, but
does not prove that GC parallelism alone caused the earlier unbounded outliers.
The baseline still has one higher-CPU trial. Median end PSS rises from 725.7 to
743.0 MiB; memory savings are not established. Keep heap/GC defaults unchanged.
