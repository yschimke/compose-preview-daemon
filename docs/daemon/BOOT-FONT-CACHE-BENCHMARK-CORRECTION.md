# Font downloads contaminated worker latency measurements

The low-level worker benchmark launches `SandboxWorkerMain` directly. It sets
rendering properties but, unlike `RobolectricLaunch.systemProperties()` via
`DaemonBackend.fontSystemProperties()`, did not set `composeai.fonts.cacheDir`.
The normal generated Android/desktop daemon launch already supplies that cache.

`GoogleFontsWoff2Resolver` only persists successful downloads when `cacheDir` is
provided. The Android SVG extension constructs a resolver for each export.
Consequently, the benchmark repeatedly fetches Google Fonts CSS and WOFF2 files
while exporting the dense screen's SVG, instead of reusing downloaded fonts.
This is our benchmark setup mismatch; it is not a Robolectric waiting bug or an
established missing production cache. Standalone integrations that also omit the
property can encounter the same cost.

## Direct profile evidence

A diagnostic run on PR #80's frozen renderer captured CPU and wall stacks during
300 actual application reloads, with the same JDK17, heap, GC and compiler settings
as the preceding comparison. It reproduced 13 frames over two seconds; the worst
was 5.307 s wall and 0.540 s process CPU.

The [recorded wall-stack summary](profiles/bulk-argb-stall-profile.json) contains
210,245 samples on `SDK 35 Main Thread`, of which **69,887 (33.2%)** include
`GoogleFontsWoff2Resolver` and 69,711 also include socket frames. The largest
individual stack has 67,705 samples through SVG font resolution, HTTP response
reading and the native socket read path. These are sample counts from a nominal
1 ms wall interval, not exact exclusive phase timers. Full stacks are retained in
the report. Profiled timings must not be used as an unprofiled performance trial.

The original summaries lack exact per-render start/end timestamps, so this
aggregate does not assign an exact number of network milliseconds to each slow
request. The harness now records `startedElapsedMs` and `finishedElapsedMs` from
the same monotonic origin used for total wall, and captures its corresponding
Unix origin before spawning the worker. Later profiles can select individual
request windows without reconstructing them from duration sums.

## Controlled follow-up

Warm the existing cache with one real reload-dashboard render, then launch both
baseline and bulk-copy workers with the same cache and `composeai.fonts.offline=true`.
Offline mode prevents network variation during the comparison; it does not disable
font embedding. The warm-up SVG is byte-identical to the previous profile's SVG,
including embedded WOFF2 data. The cache contains Roboto 400 and 500 WOFF2 files
alongside the renderer's TTF files; retain their hashes with the result.

The follow-up uses 300 distinct application loaders per worker, the same frozen
jars and JVM flags, and per-frame PNG/UIA parity. Keep the earlier measurements as
uncached-network evidence, but do not attribute their multi-second wall outliers
to the ARGB copy, GC, or Robolectric without the controlled follow-up.

New worker and matrix runs now default to an isolated `output/font-cache`, unless
`-Dcomposeai.fonts.cacheDir=...` supplies an explicit cache. Effective JVM arguments
record the selected directory. The first run still pays cold download/setup cost;
use a separately warmed cache plus offline mode when isolating rendering work.
`--uncached-fonts` explicitly reproduces historical behavior and rejects a conflicting
cache property. Earlier benchmark commands without either option now use the new
default; compare old measurements using their recorded arguments and this opt-out.

## Cached comparison results

The [complete 300-reload pair](profiles/bulk-argb-cached-reloads.json) uses the same
warmed cache and offline mode for both workers, retaining the cache SHA-256 manifest.
The cache is unchanged after both workers finish.

| Metric | Baseline | Bulk ARGB |
| --- | ---: | ---: |
| Whole-process CPU | 181.880 s | 174.860 s (−3.9%) |
| Whole-session wall | 143.493 s | 136.535 s (−4.8%) |
| End PSS | 629.72 MiB | 634.58 MiB (+4.86 MiB) |
| Final worker-reported heap after GC | 93 MiB | 93 MiB |
| Frames over 2 seconds | 0 | 0 |
| Slowest frame | 1.008 s | 0.984 s |

Every 50-reload block has lower mean CPU and median frame wall time with bulk ARGB.
All 301 PNG/UIA pairs match between variants **and** each variant's uncached run.
The final SVGs also match their uncached counterparts byte-for-byte, including
embedded fonts. Historical full SVG parity is not claimed because output names
are reused. The observed multi-second stalls disappear with network access disabled
and the existing font cache populated; combined with the direct socket stacks,
this supports the benchmark setup as the earlier tail-latency problem, rather than
a demonstrated ARGB-copy regression. One ordered pair does not establish a universal
percentage or guarantee no tail event in other workloads.

The final heap values differ from the prior uncached pair's 81 MiB because these
are different runs/configurations; do not infer that the snapshot optimization
changed live heap by 12 MiB. Within this pair both endpoints are 93 MiB. Likewise,
the smaller PSS difference does not establish a memory reduction or lifetime bound.

The existing three uncached pairs and this controlled cached pair all favor bulk
ARGB on CPU. The draft blocker arising from the unexplained waits is resolved for
this measured workload. Keep the no-memory-win qualification and all earlier
results visible.

Validation of harness timing fields: all 602 recorded request windows are ordered
and agree with their rounded duration to within 1 ms. Explicit cache arguments
are honored without adding a second cache property. A separate default-cache smoke
run checks cache creation and embedded output using the updated harness.

Reproduce using the frozen classpaths from the ARGB experiment. First warm the
font cache with the real fixture (network enabled), then run the paired script:

```sh
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/bulk-argb-frozen/classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --renders 1 --fixture ReloadDashboardPreview \
  --class-name benchmark.screens.ReloadDashboardPreviewsKt \
  --user-class-dir daemon/android/build/locale-weak-frozen/1-testFixtures-classes.jar \
  --width 480 --height 1200 \
  --output daemon/android/build/bulk-font-cache-warmup \
  --jvm-arg=-Dcomposeai.fonts.cacheDir=daemon/android/build/bulk-font-cache \
  --jvm-arg=-Xmx256m --jvm-arg=-Xms32m \
  --jvm-arg=-XX:+UseSerialGC --jvm-arg=-XX:CICompilerCount=2
python3 scripts/experiments/bulk-argb-cached-reloads.py
```

The paired script uses [the explicit cached configuration](../../scripts/experiments/bulk-argb-cached.json)
and asserts 300 distinct loader identities and PNG/UIA parity. Output directories
must be fresh. It does not manufacture missing offline font bytes: verify successful
warm-up and the retained embedded-font SVG before treating a run as equivalent.
