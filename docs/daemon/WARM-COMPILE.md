# Warm compile

`composeai.daemon.warmCompile` puts a warm, in-process Kotlin compiler in front of the Build Tools
API (BTA) compile that `compileSources` already uses. On by default;
`-Dcomposeai.daemon.warmCompile=false` turns it off.

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

The internal API differs between compiler versions, and the Gradle plugin resolves the BTA impl at
the **consumer's** Kotlin version. So `:daemon:warm-compiler-impl` builds one jar per supported
compiler line from shared sources (`src/main`: the entry, the ABI gate) and a version-specific
pipeline (`src/k24` against `libs.versions.kotlin`, `src/k25` against `warmCompilerKotlinNext`,
currently 2.5.0-Beta2, whose frontend replaces search scopes with `JvmClasspath` and threads a
`javaInterop` through the session context). Both run the same tests (`test`, `testK25`).

`daemon-core` ships the jars as resources (`warm/warm-compiler-impl-<version>.jar`). On the first
`compileSources` the daemon reads `KotlinCompilerVersion` from the launch descriptor's BTA impl
jars, extracts the matching jar and loads it next to them, in a class loader parented to the
platform loader; it talks to it through JDK types only. Each jar also checks the compiler it was
built against (`built-against.txt`) before doing anything. On a version with no jar the daemon logs
it once and uses the Build Tools API as before.

Kotlin 2.5's Build Tools API caches compiler-plugin class loaders per `BuildSession`. The warm
compiler already loads the Compose plugin once; the Build Tools API fallback would need its
`BuildSession` kept open across compiles to benefit (`daemon-bta`, compose-preview-contracts).

## Warming up

Left alone, the first `compileSources` of a daemon JVM pays for loading the warm compiler, building
its class-path state and a cold JIT: 5.7 s on a one-preview desktop module, against about 0.3 s for
the edits after it. A client that will send `compileSources` can have that paid before the first
edit with `compileWarmUp` (PROTOCOL.md § 5): the daemon compiles a generated one-function Compose
file of its own, twice, into the warm compiler's staging directory, installs nothing, and answers
when done. The class directory is only read, so the client keeps compiling through Gradle until the
answer arrives and switches then; a `compileSources` sent earlier waits for the warm-up instead of
compiling cold.

A Gradle build that writes classes before the first warm compile changes the class directory's
fingerprint, so the warm compiler rebuilds its class-path state; the JIT stays warm.

| first edit through the MCP server, one-preview desktop module | compile | edit → pixels |
| --- | ---: | ---: |
| no warm-up | 5.7 s | 6.2 s |
| warmed up (4.8–5.2 s in the background) | 0.65 s | 0.81 s |
| warmed up, then a Gradle build wrote the classes | 1.24 s | 1.47 s |

Only the warm compiler is warmed. Where it cannot run — a Kotlin version with no bundled jar, or
`composeai.daemon.warmCompile=false` — `compileWarmUp` answers `ready: false` at once, and the first
`compileSources` loads the Build Tools API as it always did. The request is opt-in so that a client
that never compiles in process (VS Code with `composePreview.daemon.compileInProcess` off) never
pays for a warm compiler it will not use, nor keeps its class-path state in memory.

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

`-Pharness.saveLoop.kotlin=2.5.0-Beta2` runs the same benchmark the way a 2.5 consumer's descriptor
would; on it every warm save stayed warm (`compileSources` 134–178 ms).

`:daemon:warm-compiler-impl`'s tests check that a warm compile installs bytes identical to a full
stock compile of the module, and that each refusal above leaves the class directory untouched.
