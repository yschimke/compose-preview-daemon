# Dynamic CDS and sandbox class loading

The existing Robolectric 4.17-beta-4 sandbox loader can use a JDK 17 dynamic class
archive. A standard jar-loading rewrite is not required. This corrects the earlier
B5 roadmap hypothesis that byte-array definition prevents sandbox classes from
being archived.

These measurements retain the experimental constant bindings, C1-only compilation
and method-handle threshold 30 from [the binding experiment](BOOT-FROZEN-BINDINGS-EXPERIMENT.md).
They do not change production settings or remove the first run needed to train an
archive. A trained archive is specific to its JVM and classpath; readiness figures
below refer to fresh JVMs reusing an already trained archive on a warm filesystem.

## Experiment

The optional `JarBackedSandboxClasses` patch leaves Robolectric's instrumentation
choice intact. When an uninstrumented class belongs to `androidx.`, `kotlin.` or
`kotlinx.`, it calls `URLClassLoader.findClass` instead of defining the bytes itself.
Other classes follow the original path. Android framework resolution is untouched.
The patch pins the exact `SandboxClassLoader` bytecode hash and checks its branch
shape. It is a measurement probe, not a general classloader replacement: alternate
resource-provider precedence, protection domains and package sealing need separate
consideration. It still reads/parses class bytes to make the instrumentation choice.

Both loader variants trained an archive by running RedSquare and three Material
button renders, then shutting the worker down normally. Archive writing completes
at process exit. The comparison uses twelve distinct fixtures, so most fixtures in
the measurement were not in training.

The unchanged loader's archive contains 8,073 classes; the jar-backed variant
contains 8,075. `-XX:+PrintSharedArchiveAndExit` with the matching classpath identifies
`androidx.compose.runtime.Composer` as an `unregistered_loader` entry in the
unchanged loader's valid archive. A class-list dump's lack of source entries does
not establish that dynamic CDS cannot archive these classes.

## Reproduce

First create the constant-binding classpath using the preparation command in the
binding report. Train its control archive:

```sh
python3 scripts/benchmark-worker-startup.py \
  --classpath daemon/android/build/frozen-experiment/constant-classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/cds-training --renders 3 \
  --jvm-arg=-XX:TieredStopAtLevel=1 \
  --jvm-arg=-Djava.lang.invoke.MethodHandle.COMPILE_THRESHOLD=30 \
  --jvm-arg=-XX:ArchiveClassesAtExit=/tmp/constant-control.jsa
```

For the jar-backed comparison, prepare a separate jar and train a separate archive
using its `classpath.txt`:

```sh
python3 scripts/experiments/prepare-jar-backed-sandbox.py \
  --classpath daemon/android/build/frozen-experiment/constant-classpath.txt \
  --jdk /usr/lib/jvm/java-17-openjdk \
  --output daemon/android/build/jar-backed-experiment
```

Use `benchmark-worker-matrix.py` with four variants: the two classpaths, each with
and without its own archive. Keep the compiler flags above fixed. Archived variants
add `-Xshare:on` and `-XX:SharedArchiveFile=/absolute/path/to/their/archive.jsa`.
Run three rotated trials and the same 72-request twelve-fixture cycle listed in the
binding report (73 frames including the initial RedSquare). Keep jar paths and
contents fixed between training and measurement. Diagnostic archive/class logging
belongs in separate runs, not in the timed matrix.

The jar-backed screen and archive reload both pass `--exercise-recovery`, including
configure/swap, throwing-composable diagnostic and unchanged post-error PNG/UIA.

## Results

Three rotated trials per variant, 876 frames total, exact PNG bytes and UIA hashes
matching at every frame index. Median values below; render latency is each worker’s
last-30-render median.

| Variant | Ready wall ms | Ready CPU ms | Render ms | Total wall ms | Total CPU ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| constant | 3783 | 5220.0 | 142.0 | 15759 | 27550.0 |
| constant-cds | 2726 | 3980.0 | 139.5 | 14454 | 25060.0 |
| jar-backed | 3871 | 5280.0 | 142.5 | 16025 | 28330.0 |
| jar-backed-cds | 2829 | 4110.0 | 140.5 | 14674 | 25160.0 |

CDS lowers readiness by 1,057 ms (27.9%) with the existing loader. The jar-backed
rewrite adds 103 ms to archived readiness and is rejected for this workload.
This isolates CDS on top of constant bindings; the contribution of constant bindings
with CDS enabled still needs its own controlled comparison.

The unchanged-loader diagnostic reload reports `androidx.compose.runtime.Composer`
with `source: shared objects file (top)`. It restores 1,780 AndroidX, 340 Kotlin and
520 kotlinx classes from shared objects, the same counts as the jar-backed reload.
It also passes the configure/swap/throw/recover checks. The archived cases retain
substantial initialization and native-runtime work: these results do not establish
an absolute minimum or a first-ever-launch time of 2.726 seconds.

[Raw trials](profiles/cds-loader-matrix.json) and
[diagnostic class-source counts](profiles/cds-loader-class-sources.json).
