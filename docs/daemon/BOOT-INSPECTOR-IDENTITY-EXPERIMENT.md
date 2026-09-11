# Avoid repeated inspector identity-pattern work

The C1 semantics-sharing profile attributes 8,403 inclusive CPU samples to regex
work in `ComposeLayoutInspector.canonicalizeJvmRuntimeIdentity`; see
[the diagnostic context](BOOT-SEMANTICS-SNAPSHOT-EXPERIMENT.md). This is a candidate
for reducing application CPU and allocation without dropping an exported product.

## Change under test

Keep the exact existing patterns, replacement strings and anchored-vs-embedded
behavior. Compile each of the six patterns once, and skip lambda/object searches
when their required literal markers are absent. The cache holds only compiled
patterns, never application values, reflective members or reloadable classes.
Authored string values still bypass runtime-identity normalization entirely.

Regression cases cover plain values, invalid markers, short/long identities,
address-only lambdas, embedded object strings, and whole-key boundaries. These
cases supplement complete rendered-artifact comparisons; they do not establish a
performance saving. The formatter, 239 connector tests, 23 desktop integration tests, dependency
ownership and HTTP-server-floor checks pass. Smoke and repeated-run artifact parity and measurements are recorded below.

## Isolation

The semantics candidate classpath is already frozen without this change. Compare
it with a newly frozen classpath after compilation, under identical density and
JVM settings. Do not rebuild during a benchmark. Preserve output parity and check
application reloads before making a recommendation. Attribute this improvement to
our inspector, not Robolectric.


Both preserved classpaths contain 252 ordered entries, including 62 copied
workspace jars. Their source lists match exactly and the only differing jar hash
is `data-layoutinspector-connector`. Android engine and fixture bytes are identical.
The first parity check uses 480×1200 at explicit density 2 under default JDK17
compilation and G1/1 GiB.


## Results

The [default-JVM matrix](profiles/inspector-identity.json) runs three rotated
sequential pairs, each red plus 60 dense requests at density 2. All 183 corresponding
frames and 2,013 corresponding data artifacts match across eleven products,
allowing only the established diagnostic normalizations. The separate
[smoke check](profiles/inspector-identity-smoke-parity.json) also passes.

| Measure | Semantics sharing only | Plus pattern reuse/guards |
| --- | --- | --- |
| Total CPU seconds, trials 0/1/2 | 108.40 / 128.84 / 106.17 | 102.59 / 102.82 / 103.61 |
| Median whole-worker CPU | 108.40 s | 102.82 s (−5.1%) |
| Median last-30 mean CPU/request | 1261.7 ms | 1227.3 ms (−2.7%) |
| Median last-30 median wall/request | 931.5 ms | 877.5 ms (−5.8%) |
| End PSS MiB, trials 0/1/2 | 948.2 / 1182.9 / 979.3 | 1250.2 / 978.9 / 1015.8 |

One baseline CPU outlier reinforces the need to retain all trials. Every paired
trial uses less total CPU with the change, but no physical-memory saving is
established. These figures isolate regex work on top of semantics sharing; do not
add percentages from different profiles to claim an end-to-end gain. The final
combined candidate still needs a direct comparison against the production baseline.
