# Disposable user classloader

The daemon splits its classloader hierarchy so the user's compiled
classes can be discarded and reloaded between renders without paying
the Robolectric sandbox bootstrap cost on every recompile.
Cross-referenced from
[DESIGN.md § 8](DESIGN.md#8-staleness-cascade--when-do-we-re-render).

## Parent/child classloader split

| Classloader | Lifetime | Loads |
|---|---|---|
| **Parent** (long-lived, expensive to bootstrap) | Per daemon JVM | `android-all` (Android) or system classes (desktop), AndroidX, Compose runtime / foundation / ui / tooling, kotlinx-*, Roborazzi, the daemon module's own helpers, the bridge package |
| **Child** (disposable, per-recompile) | Per `fileChanged({ kind: "source" })` cycle | The user's compiled-class output (`build/intermediates/built_in_kotlinc/<variant>/classes/` or `build/classes/kotlin/<variant>/main`) |

The user's `build/intermediates/...` directory is **excluded from the
parent's classpath**. Java's parent-first delegation means the child
must own the user-class lookup; if the parent could resolve them, the
child would never get a chance.

### Lifecycle

- **Daemon spawn:** parent classloader is constructed (Robolectric
  `InstrumentingClassLoader` for Android, `URLClassLoader` for
  desktop). The user's compiled-class directory is **not** on its URLs.
- **First render:** `RenderEngine` lazily allocates a child
  `URLClassLoader` whose parent is the long-lived classloader and
  whose URLs are the user's `build/intermediates/...` directory.
  `Class.forName(spec.className, true, currentChildLoader)` resolves
  the preview class via the child. Parent classes (Compose runtime,
  AndroidX) flow up via parent-first delegation.
- **`fileChanged({ kind: "source" })` arrives:** the daemon drops the
  strong reference to the current child loader, then allocates a new
  one. The next render goes through the new loader, which reads the
  current bytecode off disk on demand. Old child loader becomes
  GC-able once any retained Compose state is cleared.
- **Daemon shutdown:** drop both loaders. Parent's
  `InstrumentingClassLoader` releases its sandbox; child is just a
  `URLClassLoader`, trivially closeable.

Cost per recompile cycle: tens of ms — `URLClassLoader` allocation is
free; first lookup pays the `.class` file read; subsequent lookups in
the same render are cached. The 4–5s Robolectric sandbox bootstrap is
paid **once at daemon spawn**.

## Tip reload (`composeai.daemon.tipReload`)

Off by default. When on, the first child loader becomes a **base** that lives for the session, and
each `swap()` layers a **tip** loader over it instead of replacing every user class:

```
framework parent (Robolectric / Compose, warm)
  └─ base: every user class as first loaded (warm, kept)
       └─ tip: changed classes + every class that references them (replaced per swap)
```

The base reads from a private copy of the user-class directories, taken once when it is allocated,
with each file's size and mtime. On the next allocation the holder walks the live directories once
(one stat per file) and compares stamps, reading bytes only on a mismatch. A file modified within 2
seconds of the copy is "racily clean", as git calls it: its stamp cannot vouch for its bytes, so it
is compared by bytes until a comparison made after that window has verified it. The tip owns:

- every class file added, changed or deleted since the base; and
- every base class whose constant pool or descriptors name an owned class, transitively.

The second set is what makes the split correct. An unchanged class loaded by the base links against
the base's copy of whatever it references, so a caller of an edited composable must be redefined in
the tip to see the edit. Everything outside the closure (themes, unrelated screens, their static
state) stays loaded in the base. Tips are taken against the base, never against the previous tip,
so edits accumulate. A tip owns a deleted class too, so it is not served from the base.

The tip holds its classes' bytes in memory and defines them from there, so it writes nothing to disk.

The base is replaced, as without tip reload, when a user-class jar or a non-class resource changed,
or a class file cannot be parsed. An output identical to the base reuses the base loader. The
reference index is built from the base on the first tip and cached for the base's lifetime.

Limits: a reference made only by name at runtime (reflection, `ServiceLoader`, a class name in a
string) is not followed, so such a caller keeps the base's version. Static state held by a base
class that captured an instance of an owned class keeps that old instance. Interactive sessions are
still closed on swap, as before.

Because the base reads from its copy, a failed or in-progress incremental compile cannot remove a
class the base has not resolved yet. Callers must still only `swap()` after a successful compile;
the next tip reads the live output. Retired copies survive while their loader is reachable, and are
removed on close, garbage collection or normal JVM shutdown; a killed process can leave one behind.

### Measured cost

Compose Desktop, a generated module of 200 screens, 20 shared components and a theme (422 classes),
JDK 21, the real `UserClassLoaderHolder`. Per edit: in-process incremental compile, `swap()`, render
the edited preview, then render 10 other previews. Median of 30 measured edits per configuration
(two fresh JVMs each, after 3 warm-up edits), in ms:

| Edit | Classes reloaded | Reload, off | Reload, tip | Reload + renders, off | Reload + renders, tip |
| --- | ---: | ---: | ---: | ---: | ---: |
| One screen's body | 2 | 0.5 | 4.1 | 213 | 177 |
| A component 30 screens use | 63 | 0.5 | 3.5 | 203 | 181 |
| The theme every screen uses | 403 | 0.6 | 11.2 | 216 | 223 |

The compile, identical in both modes, took 500–780 ms of each edit. A local edit saves 5–17% of the
reload-and-render time, mostly in the other previews, whose classes stay warm; an edit everything
depends on costs a few percent more. With the mode off the timings match a build without this
change. Android, where the child loader also rewrites each user class it defines, is not measured
yet. The harness is not checked in.

## Implementation seams

**Android (`:daemon:android`):**

- `SandboxHoldingRunner` already overrides
  `RobolectricTestRunner.createClassLoaderConfig()` for the bridge
  package. Extended to **also exclude** the user's
  `build/intermediates/built_in_kotlinc/<variant>/classes/` from the
  parent classpath. Path list comes from the launch descriptor (see
  `AndroidPreviewClasspath`).
- `RobolectricHost` gains a `currentChildLoader: URLClassLoader?`
  field protected by the existing render-thread invariant.
  `RenderEngine.render` is updated to `Class.forName(spec.className,
  true, currentChildLoader)`.
- The fileChanged → recycle path replaces `currentChildLoader` with a
  new `URLClassLoader` over a fresh copy of the user-class directories.
  The render thread's pending queue is drained before the swap (no
  mid-render cancellation — DESIGN § 9 invariant).

**Desktop (`:daemon:desktop`):**

- No `InstrumentingClassLoader`; the parent is the daemon process's
  own app classloader. `DaemonMain` constructs the initial child
  `URLClassLoader` from the user-class directories in the launch
  descriptor.
- Same `RenderEngine.render` change; same fileChanged → recycle path.
- Skiko / Compose Desktop runtime is on the parent — no special
  handling needed for native libs.

**Shared infrastructure (`:daemon:core`):**

- `UserClassLoaderHolder` owns the `currentChildLoader` lifecycle.
  Both hosts implement against it.
- The `fileChanged` notification handler in `JsonRpcServer` routes the
  recompile signal into the holder.

## Fresh-Recomposer-per-render invariant

Both backends construct Compose state **per-render**, so cross-render
`Recomposer` retention is not a problem:

- Desktop's
  [`RenderEngine`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  allocates `ImageComposeScene(width, height, density)` inside `render`
  and `try/finally`-closes it. `ImageComposeScene.close()` disposes
  the scene's internal `Recomposer` + `Composition`.
- Android's
  [`RenderEngine`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
  builds a `createAndroidComposeRule<ComponentActivity>()` inside the
  per-render `Statement`. The rule's outer wrapper closes the
  `ActivityScenario` on `evaluate()` return; `Activity.onDestroy()`
  triggers `ComposeView` composition disposal which drops the
  composition's `Recomposer`.

Each render's `Recomposer` is scoped to that render. After the render
returns, no live `Recomposer` retains a strong reference to user-class
objects from the disposed child classloader. The classloader becomes
GC-able as soon as the next major collection runs.

## Risks and mitigations

### `@PreviewParameter` provider classes

Compose runtime sometimes reflects on user-defined classes — most
notably `PreviewParameterProvider` implementations. **Fix:** install
the child classloader as the
`Thread.currentThread().contextClassLoader` for the duration of the
render dispatch. Compose's reflection paths use the context
classloader by default; this redirects them. The host's render thread
restores the original context classloader in `try/finally`.

### `static` field state in user classes

Re-reading the user's `.class` from a fresh classloader means
re-running the class's `<clinit>`. Any user `object` declarations or
`companion object`s with side-effecting initialisers will run again on
each recompile. For a render-only daemon this is the right behaviour —
the user just edited the source; they want the new behaviour, including
new static state.

### Bridge package and `doNotAcquirePackage`

`SandboxHoldingRunner` adds `ee.schimke.composeai.daemon.bridge` to
Robolectric's `doNotAcquirePackage` so the cross-classloader handoff
queues are loaded once. Bridge classes resolve via the parent's parent
(the system classloader), unaffected by the child. Verified with a
unit test that the bridge queues stay shared across child swaps.

The same "shared process-static" rule applies to the `previewOverride*`
named-override runtime (`ee.schimke.composeai.overrides.*`, notably
`PreviewOverrideController`): the daemon connector seeds it before the
preview composes and the preview reads it back during composition, so
the two must see one `Class<?>`. `UserClassLoaderHolder.mustDelegateToParent`
forces both `ee.schimke.composeai.daemon.*` and
`ee.schimke.composeai.overrides.*` to the parent even when the child's
URLs carry them. This is load-bearing for the **bundle-backed live
daemon** (`ServeBundleDaemon`, the engine behind `--catalogs`
`liveBundle` / `preview.coo.ee`), where the bundle's resolved maven
classpath — including `:data-preview-overrides-runtime` — is on the
child loader's URLs; without the delegation the child would load a
second copy of the controller, the daemon's seed and the preview's read
would land on different statics, and every content override (e.g.
`previewOverrideString("label", "Filled")`) would silently no-op.

### Whose Compose runs (`composeai.daemon.appCompose`)

Issue [compose-ai-tools#6016](https://github.com/yschimke/compose-ai-tools/issues/6016).
The Android daemon distribution bundles its own Compose on the parent
classpath. The Gradle lane never notices: there the consumer's own Compose
*is* the parent classpath (the renderer is compiled `compileOnly` against a
compat floor, docs/RENDERER_COMPATIBILITY.md), and `userClassDirs` carries
only build-output directories. The **Bazel** (`compose-preview bazel`) and
**bundle** (`bundle daemon`, `ServeBundleDaemon`) lanes are different: the
target's runtime jars — Compose included, possibly a newer or unreleased one —
ride `userClassDirs` onto the child loader, and `mustDelegateToParent` sends
every `androidx.` lookup to the parent first. Measured behaviour before the fix
(`AppComposeEscapeHatchTest`, `AppComposeSelectionTest.childLoaderPrefersTheDaemonsAndroidxClasses`):

- every Compose class the daemon also bundles is the daemon's copy — the
  app's jar is never read for it; and
- a class only the app's Compose carries falls through `URLClassLoader`'s
  fallback and is defined by the **child** loader, linked against the
  daemon's Compose. A newer Compose therefore ran *split* across two loaders,
  not merely downgraded.

Why the obvious fixes are wrong:

- **Child-first `androidx.` for the preview only.** The renderer calls
  Compose itself (`setContent`, `captureToImage`, semantics, the ui-test
  rule). A preview on the child's Compose and a renderer on the sandbox's
  would pass a `Composer` across the seam — `ClassCastException` /
  `LinkageError`. It also breaks the shared-state rule above.
- **Loading the renderer into the child as well.** The renderer lives in
  `ee.schimke.composeai.daemon.*`, which must stay one copy (bridge, overrides),
  and the child is disposable per recompile; it would mean re-homing the
  whole render body per swap.

**What the daemon does.** Robolectric's `SandboxClassLoader` defines every
acquired class — all of `androidx.` — itself, but reads the bytecode through
`URLClassLoader.getResourceAsStream`, i.e. its **parent's** `getResource`
first, and takes that parent from the thread context loader when the sandbox
is built. When the decision below says the app's Compose runs,
`RobolectricHost.runJUnit` installs an `AppComposeFirstLoader` as that context
loader: it answers `androidx/…` and `META-INF/androidx.*` resources from the
user classpath first and everything else from the daemon's classpath. The
sandbox then defines **one** Compose, from the app's bytes, for the renderer,
the bundled libraries and the preview alike; the child still delegates
`androidx.` to the sandbox, and the shared-state packages stay parent-only.
It is the Gradle lane's "consumer's Compose wins, renderer runs on it"
arrangement, reproduced without a resolver. `AppComposeFirstLoader` is
deliberately not a `URLClassLoader`: Robolectric would take its URLs as the
sandbox's whole classpath and expose the user's classes to the sandbox loader.

**The decision** (`AppComposeSelection.decide`, logged once per JVM as
`compose-ai-daemon: [appCompose] mode=… runs=app|daemon app=… daemon=… reason=…`):

| `composeai.daemon.appCompose` | User classpath has `androidx/compose/runtime/Composer.class` | Runs |
|---|---|---|
| any | no | daemon (`user-classpath-has-no-compose`) |
| `daemon` | yes | daemon (`escape-hatch`) — the pre-fix behaviour |
| `app` | yes | app (`forced`) |
| `auto` (default) | yes, version newer, unknown or the daemon has none | app (`app-newer`, `app-version-unknown`, `daemon-has-no-compose`) |
| `auto` (default) | yes, same or older version | daemon (`same-version`, `app-older`) |

Versions are read from `META-INF/androidx.compose.runtime_runtime.version`.
An **older** app Compose keeps the daemon's because that is the direction that
already works — the preview, compiled against the older Compose, runs
forward-compatibly — whereas preferring it would run the daemon's bundled
libraries (Material 3, ui-test) below the Compose they were compiled against.
`app` is there for a lane that wants it anyway.

Consequences and limits:

- **Per JVM, fixed at sandbox boot.** Robolectric caches one sandbox per
  process. A `fileChanged({ kind: "classpath" })` that brings a different
  Compose needs a process restart to change whose Compose runs. A pre-booted
  **spare** worker booted with no user classes, so on the daemon's Compose; it
  refuses a `configure` whose catalog would run its own
  (`SandboxWorkerMain.appComposeRefusal`) and the pool cold-boots that slot.
- **Only `androidx/` prefers the app.** Kotlin, `kotlinx.coroutines`,
  Robolectric and the daemon keep the daemon's copy. A Compose that needs a
  newer coroutines or stdlib than the daemon bundles still fails, as it would
  with a Gradle resolution that did not upgrade them.
- **Android resources** are unchanged: the resource APK / `R` values come from
  whatever the lane already supplies. `androidx/**/R*.class` on the user
  classpath is preferred along with the rest of `androidx/`.
- **Desktop** is unchanged: its parent is the daemon's own app loader, not a
  rebuildable sandbox.

### Compose compiler plugin's per-class metadata

The Compose compiler emits per-`@Composable` synthetic metadata
classes (`*$$composable_*`) referenced by name from the runtime. After
a child swap, those metadata classes are re-loaded by the new child.
The runtime's name-based lookups resolve them via the child (parent-first
up to the `androidx.compose.runtime.*` types on the parent; the
user-emitted metadata classes on the child).

### Native libraries loaded by the parent

Skiko's native bundle and Robolectric's `android-all` native libraries
are loaded once by the parent classloader. Child-loader swaps don't
affect them. Skiko stays on the parent.

## WeakReference soak probe

Belt-and-braces leak detection: forced GC + `WeakReference` probe
after each recycle. If a recycled loader doesn't collect within 2
GCs, log `userClassloaderLeaked`. After N events, recycle the whole
sandbox.

## State of B2.0 (implemented)

The parent/child classloader split landed in `:daemon:core`'s
[`UserClassLoaderHolder`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/UserClassLoaderHolder.kt)
and both backends. Per-render fresh-`Recomposer` invariant verified;
the soak `WeakReference` probe is live.

DoD met:
[`S3_5RecompileSaveLoopRealModeTest`](../../daemon/harness/src/test/kotlin/ee/schimke/composeai/daemon/harness/S3_5RecompileSaveLoopRealModeTest.kt)
un-`@Ignore`d for desktop and passes. Sandbox-classloader-leak
detection extended to also cover child loaders.

Outstanding:
- B2.0c (per-preview resource-read tracking) is still open — see
  Resource changes below.
- Android `S3_5` is gated on a Compose-Android compiler-mangled-method-name
  fix.

## `fileChanged` semantics

- `kind: "source"` → child-loader recycle (this doc).
- `kind: "classpath"` → full sandbox recycle (Tier 1, see DESIGN § 8).
- `kind: "resource"` → see Resource changes below.

## Resource changes

**v1 (current behaviour).** A `fileChanged({ kind: "resource", path:
... })` notification triggers **all previews in the module marked
stale** (the Tier-3 conservative fallback from DESIGN § 8). Combined
with the Tier 4 visibility filter, the user only ever pays for
re-rendering what they're looking at.

**v2 follow-up — per-preview resource-read tracking.** Instrument the
Resources lookup path during a render to record which resource IDs
the composition actually read; resolve the changed file's resource IDs
via the merged `R.txt` and look them up in a reverse index. Not blocking.
