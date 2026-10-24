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

The internal API differs between compiler lines, and the Gradle plugin resolves the BTA impl at the
**consumer's** Kotlin version. So `:daemon:warm-compiler-impl` builds one jar per Kotlin minor line
from shared sources (`src/main`: the entry, the ABI gate) and a line-specific pipeline:

| line | source set | built against | differs from `src/k24` in |
| --- | --- | --- | --- |
| 2.3 | `src/k23` | `warmCompilerKotlinPrevious`, currently 2.3.21 | see below |
| 2.4 | `src/k24` | `libs.versions.kotlin` | — |
| 2.5 | `src/k25` | `warmCompilerKotlinNext`, currently 2.5.0-Beta2 | search scopes become `JvmClasspath`; the session context takes a `javaInterop` |

Kotlin 2.3's frontend is the same shape as 2.4's, reached through older seams:

- `createProjectEnvironment` and `createLibraryListForJvm` are top-level functions
  (`cli.jvm.compiler.legacy.pipeline`, `cli.jvm.compiler`), not members of
  `JvmFrontendPipelinePhase`; the diagnostics collector comes from the configuration artifact, and
  `JvmFrontendPipelineArtifact` takes it too;
- a source session is told whether it is a leaf HMPP module rather than its `KmpModuleKind`, and
  FIR extension registrars come from the project, not the configuration;
- the pipeline has no `JvmSerializeCommonMetadataPipelinePhase`, and `AbstractCliPipeline` only
  offers `execute`, which gives each compile a root disposable of its own and disposes it at the end
  (harmless: the cached project hangs off the warm compiler's own disposable);
- compiler-plugin extensions live in the **project**, registered once when it is created, where
  2.4 keeps them in a per-configuration storage. A cached project would carry the first compile's
  configuration into every later one (the Compose IR extension captures its message collector), so
  the 2.3 pipeline keeps the registrars out of the configuration and registers their extensions in
  the project per compile, from that compile's configuration, under a disposable it drops before
  the next one. `an edit on cached class-path state installs the same bytecode a stock compile
  produces` is the test that would catch the Compose plugin running twice.

The shared entry reaches each line through two functions every `WarmPipeline.kt` defines:
`warmFrontendPhase` and `runWarmPipeline`.

A jar serves the compilers of its line from the oldest one it is tested against, not only the one
it was built against: the 2.4 jar serves a consumer on Kotlin 2.4.20 as well as one on 2.4.21. The
build checks that rather than assuming it: each line's tests run against the compiler it was built
against (`test`, `testK25`) and against the line's other supported releases (`testK24OnKotlin2_4_20`),
and `check` runs them all (`testK23`, `testK23OnKotlin2_3_20` for the 2.3 jar).

"Minor line" is the granularity of the jar, not a promise from Kotlin: the 2.4.20 tooling release
changed the frontend API `src/k24` calls (`JvmFrontendPipelinePhase.createProjectEnvironment`,
`createLibraryListForJvm`, `FirJvmSessionFactory.createSourceSession`), so 2.4.0 and 2.4.10 stay on
the Build Tools API. Each jar records the oldest compiler it serves (`supported-from.txt`) and
refuses an older one at load, and `testK24OnKotlin2_4_10` checks that it does. Serving 2.4.0–2.4.10
would take another pipeline source set built against 2.4.10. Likewise the 2.3 jar serves 2.3.20 and
later: 2.3.0 and 2.3.10 predate `FirJvmSessionFactory.Context` (their session factories take the
project environment, a package-part provider and shared Java components one by one), and
`testK23OnKotlin2_3_10` checks the jar refuses them.

When Kotlin releases a new patch of a bundled line, add it to the line's `alsoTested` list in
`daemon/warm-compiler-impl/build.gradle.kts`.

`daemon-core` ships the jars as resources (`warm/warm-compiler-impl-<minor>.jar`, e.g.
`warm-compiler-impl-2.3.jar`, `-2.4.jar`, `-2.5.jar`). On the first `compileSources` the daemon reads
`KotlinCompilerVersion` from the launch descriptor's BTA impl jars, extracts the jar for that
version's line and loads it next to them, in a class loader parented to the platform loader; it
talks to it through JDK types only. Each jar also checks that the compiler on its class path is of
the line it was built for, and no older than `supported-from.txt`, before doing anything. On a
line with no jar, or a compiler the jar refuses, the daemon logs it once and uses the Build Tools
API as before. A compiler of a bundled line released
after the jar was built is used untested; if the jar fails to link against it (a `LinkageError`
during a compile, thrown or reported by the compiler as an internal error), the daemon turns the
warm compiler off for the rest of the session and uses the Build Tools API.

Kotlin 2.5's Build Tools API caches compiler-plugin class loaders per `BuildSession`. The warm
compiler already loads the Compose plugin once; the Build Tools API fallback would need its
`BuildSession` kept open across compiles to benefit (`BtaCompileSession` in `:daemon-compile`).
It is not today: in kotlin-build-tools-impl 2.4.x `BuildSession.close()` is what clears the
compiler's JAR caches, so a session held across saves could read a JAR rebuilt in place through a
stale handle — the very case the content-hashed class-path snapshots exist for.

## Warming up

Left alone, the first `compileSources` of a daemon JVM pays for loading the warm compiler, building
its class-path state and a cold JIT: 5.7 s on a one-preview desktop module, against about 0.3 s for
the edits after it. A client that will send `compileSources` can have that paid before the first
edit with `compileWarmUp` (PROTOCOL.md § 5): the daemon compiles the module files the request
names — or, when it names none, a generated one-function Compose file of its own — into the warm
compiler's staging directory, installs nothing, and answers when done.

It runs passes of one file each, cycling through the named files, up to 60 passes or 6 s. Most of
a cold edit is the JIT, which keeps improving for dozens of compiles, and compiling the files that
will be edited also loads the library symbols they use, which a generated file never touches. On a
Material 3 screen with 20 helper files (warm compiler alone, 4-core sandbox):

| warm-up | took | edit 1 | edits 2–10 |
| --- | ---: | ---: | ---: |
| 2 passes, other files | 0.7–0.8 s | 252–280 ms | 145–225 ms |
| 60 passes, other files of the module | 5.5–5.7 s | 177–196 ms | 106–148 ms |
| 60 passes, the edited file | 8.1 s | 103–120 ms | 86–113 ms |

A file that does not compile (mid-edit, say) is dropped from the cycle without losing the warm
state; when none is left the generated file is used. The compiler's lock is taken per pass, and a
`compileSources` that arrives stops the warm-up after the current pass, so an edit waits for one
pass (about 0.1–0.3 s), never for the whole warm-up.

The class directory is only read, so a client could compile through Gradle until the answer and
switch then. The MCP server does not: an edit made during the warm-up goes to `compileSources` and
waits. A Gradle build competes with the warm-up for the CPU, and one that writes classes before the
first warm compile changes the class directory's fingerprint, so the warm compiler rebuilds its
class-path state (only the JIT stays warm).

| first edit through the MCP server, one-preview desktop module | compile | edit → pixels |
| --- | ---: | ---: |
| no warm-up | 5.7 s | 6.2 s |
| warmed up (4.8–5.5 s in the background) | 0.65–0.70 s | 0.81–0.91 s |
| warmed up, then a Gradle build wrote the classes | 1.24 s | 1.47 s |

Six edits sent back to back from the first render, on a 4-core machine:

| edit made during the warm-up | edit 1 | edit 2 | first six | warm-up took |
| --- | ---: | ---: | ---: | ---: |
| waits for it (what the MCP server does) | 5.8 s | 0.72 s | 13.1 s | 5.2 s |
| compiles through Gradle until it answers | 5.7 s | 4.6 s | 16.0 s | 9.0 s |

Where the warm compiler cannot run — a Kotlin version with no bundled jar, a compiler its jar
refuses, one that failed to link, or `composeai.daemon.warmCompile=false` — every edit goes to the
Build Tools API, so `compileWarmUp` warms that instead (`BtaFallbackWarmUp`): one compile of a
generated file through the fallback's own `BtaCompileSession`, which loads the `KotlinToolchains`
impl and snapshots every class-path entry. Cold, the first edit pays both: 20.7 s on a Kotlin 2.3
Wear module with a 377-entry class path, against 2.0–4.2 s for the edits after it.

The warm-up compiles into a scratch directory beside the fallback's `ic/` (`warm-up-bta/`), with its
own incremental-compilation state, so it installs nothing and the module's IC caches only ever see
the module's own compiles. Only the class-path snapshots are shared: they are content-addressed
(`cp-snapshots/<sha-256>.bin`), so the scratch directory is seeded with links to the existing ones
and the new ones are moved back atomically, never over an existing file. It is one pass, under the
compile lock: an edit sent meanwhile waits for it and then finds the fallback warm, no later than it
would have finished cold. It runs once per daemon, and not at all once the fallback has compiled.

The answer is `ready: true` with a message that says the Build Tools API was warmed and why no warm
compiler could run. `ready: false` now means nothing could be warmed: in-process compile is not
configured, the module is ineligible, or the fallback's warm-up failed. The request is opt-in so
that a client that never compiles in process (VS Code with `composePreview.daemon.compileInProcess`
off) never pays for a compiler it will not use, nor keeps its class-path state in memory.

On a one-file plain-Kotlin module (`BtaFallbackWarmUpTest`, harness), the fallback warm-up took
6.4 s and the first edit after it 0.6 s.

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
