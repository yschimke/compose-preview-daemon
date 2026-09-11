# Sharing a layout snapshot within one render

The layout artifact and layered SVG each walked the same captured Compose layout
tree. On the dense dashboard, the tuned baseline spent roughly 750 ms writing
layout data and 810 ms exporting SVG. Sharing the extracted payload can remove
the second walk while preserving both products.

## Implementation and lifetime

Android and desktop put a fresh `LayoutInspectorSnapshot` in their post-capture
extension context. Its payload is lazy, so a render without a layout consumer pays
no extraction cost. The layout writer and SVG exporter read the same payload.
There is no global or cross-render cache. Successful initialization drops the
builder; a missing tree is memoized, while failed initialization can be retried
by the next consumer. Callers supplying only the original context keys retain
the original extraction path.

The connector's 236 tests pass, including a new check that reusing an output name
for a later capture replaces its layout data. Android and desktop compile.
Desktop integration also passes 23 tests covering layout, SVG, scroll-end capture,
retired slots and resolved text metrics. Dependency ownership and HTTP-server-floor
checks pass. Real-worker parity and performance measurements follow below.

## Matched inputs

The baseline is main at `a1d85201`; both builds include the same benchmark fixtures.
Candidate source was backed up while the baseline was built and restored afterward.
`freeze-classpath.py` preserves workspace jars and fingerprints every classpath
entry. Only the two Android main-jar entries and layout connector differ between
these preserved classpaths. Dependency versions and fixture bytes are shared.

Two profiles are compared independently:

- **default:** unmodified Robolectric, JDK 17 JVM defaults, the harness's 1 GiB cap;
- **tuned:** guarded constant bindings, C1, method-handle threshold 30, Serial GC,
  256 MiB cap.

Neither profile uses application CDS. These are direct worker launches: they do
not include the normal spare launcher's heap-free-ratio overrides. Differences
between these profiles cannot be attributed to compiler tier alone.

## Procedure

The four-variant matrix rotates order across three sequential trials. Each fresh
worker renders red, then 36 dense dashboard frames at 480 × 1200. RSS is sampled
and final PSS/private pages recorded; no extra diagnostic GC checkpoints are added.
The final 30 requests report wall and worker CPU separately from startup totals.

```sh
python scripts/benchmark-worker-matrix.py \
  --matrix scripts/experiments/layout-snapshot.json \
  --classpath daemon/android/build/snapshot-baseline-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output /tmp/layout-snapshot-results --trials 3 --renders 36 \
  --width 480 --height 1200 --memory --fixture DenseDashboardPreview
```

The matrix paths name the preserved baseline/candidate jars, not mutable build
outputs. Generate them after building each revision with
`:daemon:android:writeDaemonClasspath`, the guarded binding preparation script for
the tuned profile, and `scripts/experiments/freeze-classpath.py`. The unpatched
profile replaces only the copied patched sandbox jar with its pinned upstream jar.

## Validation

All 12 smoke frames match PNG/UIA bytes. Tuned baseline/candidate layout, semantics
and SVG files are byte-identical. With JVM defaults, two layout files differ only
in the order of `Role` and `ContentDescription` in a diagnostic modifier string.
`compare-worker-artifacts.py --normalize-semantics-debug-order` canonicalizes only
that unambiguous two-key shape in modifier properties. It preserves role and
content changes, does not touch displayed text, and reports how many artifact
comparisons needed normalization. The structured semantics and SVG remain exact.

## Three-trial results

[Raw rows and medians](profiles/layout-snapshot.json) preserve all 12 workers. All
444 PNG/UIA frame checks pass. The six paired comparisons also pass **666 data
artifact checks**; 144 layout JSON comparisons need only the diagnostic field-order
normalization above. SVG and structured semantics remain byte-identical.

| Profile | Last-30 median wall, before → after | Last-30 mean worker CPU, before → after | Total worker CPU, before → after | Final PSS, before → after |
| --- | ---: | ---: | ---: | ---: |
| Default | 1409.5 → 1006 ms | 1923.3 → 1509 ms | 94.69 → 78.90 s | 989.5 → 1026.7 MiB |
| Tuned | 2065 → 1361 ms | 2058.3 → 1355.7 ms | 81.09 → 55.96 s | 500.3 → 501.6 MiB |

Each entry is the median across three worker trials. Sharing the walk reduces
steady request CPU by **21.5% / 34.1%** and median request wall time by **28.6% /
34.1%**, for default/tuned respectively. Whole-workload CPU falls **16.7% / 31.0%**.
Startup is effectively unchanged, as expected for a post-capture optimization.

There is **no demonstrated memory reduction**: median final PSS is 3.8% higher in
the default profile and 0.3% higher in tuned. These endpoint snapshots vary across
workers and cannot establish a leak or a sustained memory improvement. A payload
is now retained through the two consumers within one capture. No launch defaults
are changed; the large heap soak and reload checks address different lifetime
questions.

The application reload diagnostic also reproduces retained child loaders on the
baseline and candidate/default JVM. This is a separate pre-existing lifetime issue,
not evidence that the per-capture snapshot introduced a cross-render cache. See
[the reload results](profiles/reload-loader-retention.json); launch defaults remain
unchanged while the retaining references are investigated.
