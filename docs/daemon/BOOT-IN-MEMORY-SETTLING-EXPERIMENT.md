# Settle through the existing Roborazzi image writer

This change uses Roborazzi 1.74.0's existing `AwtImageWriter` hook to
receive each cropped/scaled image before PNG encoding. The settling state machine
and its exact ARGB snapshots are shared with the old path: every capture, frame
advance, dimension comparison and settle outcome remains. No previously deferred
pixel-buffer/comparator prototype is included.

Only the accepted final frame is encoded with the original writer, retried through
the existing PNG decoder validation, and checked for exact decoded-pixel equality.
The decoded final image is passed to the existing size-correction handoff. Captured
reports retain their order and original contents but are delivered only after
successful final encoding/validation. This deliberately changes intermediate file
visibility and failure-report timing for supported plain recordings.

Supported plain recording captures now use this path automatically. The file-based
path remains active for non-PNG files, compare/verify tasks, custom encoders/loaders/
reporters, UI-tree annotations or non-ARGB8888 pixel configuration. These cases may
depend on file availability or encoding semantics. No heap/GC/recycling defaults
change. Existing public file-capture settling overloads retain their contracts.

Measurements used a temporary `composeai.render.inMemorySettling` switch to compare
identical frozen jars. That switch is removed from the implementation; the
[benchmark-only patch](../../scripts/experiments/in-memory-settling-benchmark-toggle.patch)
restores it for reproducing the experiment. Flags in the experiment configuration
refer to those frozen benchmark jars, not a published runtime setting.

## Validation

All 19 focused renderer tests and 29 daemon integration tests passed with the experimental path enabled.
The same 48 tests pass after removing the switch, under normal defaults. The lifetime test uses the actual `AwtRoboCanvas`,
including cropping, `release()` before returning, deferred encoding and PNG text
metadata. It covers all three settle outcomes and exact sample/clock counts.
Other tests cover missing image delivery, capture exceptions, decoder validation
for invalid custom encoders, task/format eligibility, ordered report delivery after
encoding, suppression of queued success reports on final I/O failure, and immediate
file visibility for custom reporters on the fallback path. Daemon tests cover
overrides/gutters, fixed and wrap-content dialogs, popup overlays, multiple Compose
roots, wrappers and size bounds. The pure-JVM test must
set the capture type explicitly: the default options consult Robolectric's
`ConfigurationRegistry`. The painter is an explicit test-only dependency; no new
production dependency is added.

The real daemon smoke comparison matches three frame pairs and all 33 exported
artifacts at 480×1200, density 2. Short smoke CPU/latency results are not used as
performance evidence. Frozen inputs contain 252 entries; the controlled timing
matrix uses identical classpaths and changes only the opt-in JVM property.

[The completed matrix](profiles/in-memory-settling.json) contains three rotated
fresh-JDK17/G1 pairs, each one red plus 60 dense-dashboard captures, 1 GiB heap,
default compilation/GC threads and equal GC logging. No builds or profiling
interfered with timings. All 183 frame pairs and 2,013 exported artifacts match;
only documented diagnostic-order and Typeface-identity normalization is used.
Every trial is retained.

| Metric | Flag disabled | Flag enabled |
| --- | --- | --- |
| Total CPU per trial (s) | 81.11 / 79.72 / 80.50 | 75.86 / 75.53 / 74.98 |
| Mean total CPU (s) | 80.44 | 75.46 |
| Median last-30 mean CPU (ms) | 824.3 | 743.7 |
| Median last-30 wall latency (ms) | 575.5 | 495.5 |
| Median total wall time (s) | 51.48 | 53.55 |
| Concurrent mark cycles | 6 / 6 / 6 | 4 / 6 / 4 |
| Median end PSS (MiB) | 1024.6 | 1001.5 |

Mean CPU falls 6.2%; steady CPU falls 9.8% and steady median latency falls 13.9%.
Whole-run median wall time is 4.0% worse, so these results do not establish a total
wall-time improvement. End PSS from short trials is not proof of lower live memory
or a lifetime bound. Separate allocation and actual application-reload diagnostics
are completed below.

## Final default-enabled implementation at 256 MiB

[Three uninstrumented Serial-GC pairs](profiles/in-memory-settling-serial-256.json)
compare the flag-disabled frozen prototype with the final default-enabled renderer.
Only classpath entry 19 differs; all other 251 entries match. Both variants use
`-Xmx256m`, Serial GC, free ratios 10/30 and equal GC logging. There is no NMT or
forced-GC checkpoint instrumentation, and no overlapping build/profile workload.
All 183 frame pairs and 2,013 exported artifacts match.

| Metric | File-based baseline | Default in-memory candidate |
| --- | --- | --- |
| Total CPU per trial (s) | 71.52 / 70.96 / 71.37 | 65.44 / 65.44 / 65.23 |
| Mean total CPU (s) | 71.28 | 65.37 |
| Median last-30 mean CPU (ms) | 687.3 | 595.0 |
| Median last-30 wall latency (ms) | 611.0 | 525.0 |
| Median total wall time (s) | 43.72 | 38.46 |
| Median end PSS (MiB) | 768.8 | 793.5 |

Mean CPU falls 8.3%; steady CPU falls 13.4%, steady median latency falls 14.1%,
and whole-run median wall time falls 12.0%. End PSS is higher in this profile,
reinforcing that allocation savings do not establish a general resident-memory
reduction. These settings are a comparison profile, not a changed launch default.

## Allocation and 300-reload diagnostics

[Separate allocation profiles](profiles/in-memory-settling-allocation.json) use
one red plus 36 dense captures, equal G1 thread bounds (2 parallel/1 concurrent),
512 KiB sampling and post-readiness filtering. Total sampled allocation weight
falls from 6,048,613,315 to 5,453,869,081 bytes (9.8%). Inclusive PNG-reader weight
falls from 822.6 to 152.2 MiB; bitmap-conversion weight remains roughly unchanged.
Inclusive categories overlap and must not be summed. These are sampled allocation
weights, not exact counts or live heap. All 37 frame pairs and 407 exported artifacts
match. Instrumented timings are excluded from the timing results above.

[Paired 300-reload diagnostics](profiles/in-memory-settling-reload-256.json) use
identical frozen jars, 256 MiB heap, Serial GC, free ratios 10/30, NMT summary,
forced-GC checkpoints and histograms every 50 renders. The child-loaded service
dashboard contains cards, text, icons and charts; every request replaces the
application classloader. Both runs have 300 distinct loader identities and match
all 301 PNG/UIA frame pairs. The output name is reused to avoid growing artifact
cardinality as a confounder.

| Checkpoint | Baseline live heap (MiB) | Candidate live heap (MiB) | Live application loaders, both |
| --- | --- | --- | --- |
| 0 | 87.82 | 87.84 | 2 |
| 50 | 90.71 | 90.74 | 2 |
| 100 | 91.11 | 91.15 | 2 |
| 150 | 91.50 | 91.53 | 2 |
| 200 | 91.91 | 91.94 | 2 |
| 250 | 79.47 | 92.38 | 2 |
| 300 | 79.77 | 79.79 | 2 |

No additional retained application-loader or final live-heap growth is observed.
This is a finite-run result, not an indefinite bound. PSS still grows during these
runs: baseline 571.0 → 925.4 MiB, candidate 564.4 → 908.2 MiB, including 892.6 →
908.2 MiB in the candidate's final 50 renders. Do not claim a flat whole-process
footprint or a general PSS saving. NMT committed memory is not PSS and does not
cover all third-party native allocations. No recycling-policy change follows from
this experiment; loaded/concurrent-worker and longer native-memory behavior remain
separate questions. Diagnostic timings are not used for CPU/latency claims.

## Integration base

The follow-up carries #70's already reviewed experiment records because #70 was
merged into its parent branch shortly after #69 had merged to main; those records
were absent from main. They remain archived experiments, not enabled alternatives.

## Follow-up work

- Extend performance coverage to loaded/concurrent workers and additional capture workloads.
- Reuse the final sample’s already-computed ARGB snapshot for encode validation instead of allocating a duplicate snapshot; retain exact output checks.
- Discuss an explicit supported in-memory capture policy with Roborazzi rather than relying indefinitely on default writer/loader identities. Unknown identities safely retain the old path.

This is our use of an existing Roborazzi API, not a missing Robolectric entrypoint.
The benefit applies to multi-sample settling loops; an ordinary one-shot screenshot
would not avoid intermediate encodes. The source and existing-API ownership are
recorded in [the preceding investigation](BOOT-SETTLE-IMAGE-COMPARISON-EXPERIMENT.md).
