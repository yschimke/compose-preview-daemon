# Warm compile

`-Dcomposeai.daemon.warmCompile=true` puts a warm, in-process Kotlin compiler in front of the
Build Tools API (BTA) compile that `compileSources` already uses. Off by default.

## Why

A one-file edit to a Compose screen costs the stock compiler about 270 ms even with the compiler
already loaded and JIT-warm, and the daemon's BTA path about 1 s. Almost none of that is the edit:
each compile rebuilds the project environment, re-indexes every class-path jar, rebuilds the FIR
library session and re-reads about 920 class files from the jars, and BTA additionally SHA-256s
every class-path entry to look up its snapshot. None of that depends on the edit.

The Build Tools API cannot keep any of it: a `BuildSession` holds an executor and the shared
IntelliJ application environment, nothing per module (2.4.21; 2.5.0-Beta2 adds a cache of compiler
plugin class loaders and nothing else).

## How

`:daemon:warm-compiler-impl` links against kotlin-compiler-embeddable's CLI pipeline and runs the
stock phases — configuration, Fir2Ir, the JVM backend, output writing — with one replacement:
its frontend phase builds the project environment and the FIR shared and library sessions once and
hands them to `SessionConstructionUtils.prepareSessions` on every later compile, so each edit only
builds a source session for its own files. The Compose plugin is loaded once and injected into each
compile's configuration, instead of through `-Xplugin`, which builds a new plugin class loader per
compile.

It compiles only the changed files, against the module's class path plus the module's existing
classes, into a staging directory, and installs the result into the class directory **only when
every recompiled class keeps its ABI** (non-private members, Kotlin metadata, constant values).
That invariant is what makes the cached library session sound: it may have deserialized the
module's own classes, and an ABI-identical class needs no re-read.

Everything else goes to the Build Tools API's incremental compile, which also recompiles dependents:

| Edit | Why it cannot stay local |
| --- | --- |
| ABI change | dependents may need recompiling |
| declares a non-private `inline` function | callers inlined the old body |
| new source file | its facade is missing from `META-INF/*.kotlin_module`, which a partial compile cannot rewrite |
| removed file, non-Kotlin source, no change set | outside a one-pass compile |
| multi-file class part | the facade aggregates every part |

Files the warm compiler compiled since the last fallback are added to that fallback's change set,
so BTA's incremental caches catch up with them. Any class written behind the warm compiler's back
(a fallback, a Gradle build) changes a cheap fingerprint of the class directory, and the next
compile rebuilds the cached state.

## Isolation and versions

The impl jar ships inside `daemon-core` as a resource and is loaded next to the BTA impl jars the
launch descriptor supplies (which carry kotlin-compiler-embeddable), in a class loader parented to
the platform loader. The daemon talks to it through JDK types only.

The internal API differs between compiler versions, and the Gradle plugin resolves the BTA impl at
the **consumer's** Kotlin version, so the jar refuses to load unless the compiler on its class path
is the version it was built against (written into `built-against.txt` at build time). On any other
version the daemon logs it once and uses the Build Tools API as before.

## Results

`SaveToPixelsRealModeTest` (harness, desktop real mode) times an editor save end to end on a
generated 60-file Compose module — `compileSources`, `renderNow`, `renderFinished` — and checks the
pixels of every iteration:

```
./gradlew :daemon:harness:test -Pharness.host=real --tests '*SaveToPixels*'
```

| | BTA | warm |
| --- | ---: | ---: |
| leaf screen edit, save→pixels | 1318 ms | 446 ms |
| shared component edit, save→pixels | 1253 ms | 397 ms |
| `compileSources` | 960–1060 ms | 110–170 ms |

`:daemon:warm-compiler-impl`'s tests check that a warm compile installs bytes identical to a full
stock compile of the module, and that each refusal above leaves the class directory untouched.
