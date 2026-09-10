# Sandbox boot: the road to 2-3 seconds

[STARTUP.md](STARTUP.md) has the profile: on an idle box a Robolectric sandbox
boots in ~4 s, its warm render costs another ~6-8 s, and neither number is
CPU queueing — it is the JVM defining ~6,000 classes and spinning ~6,500
`java.lang.invoke` hidden classes for Robolectric's invokedynamic dispatch,
per JVM, per boot. This page is the menu of what could change that, ranked by
what each item returns against a **2-3 s** target for "a catalog that was not
resident can render", with the short-term items that already shipped at the
bottom. Costs quoted are the idle-4-core figures; the deployed box runs 2-3×
slower per boot under memory pressure.

The one-line conclusion: **no JVM flag or Robolectric tuning gets a cold boot
plus warm render under ~8 s. The target is reached by not booting — a warmed
sandbox is adopted, restored, or pre-linked — and a Robolectric fork that
binds shadows statically is what makes the cold path itself cheap enough to
stop mattering.**

## Tier A — don't boot: adopt a warmed sandbox

### A1. Pre-booted generic workers, keyed by overlay signature

A sandbox does not depend on the catalog. The catalog's classes ride the
disposable child loader (`UserClassLoaderHolder`), and the boot-time warm
render touches none of them. So a worker booted and warm-rendered against
*no* catalog can be handed to whichever daemon needs a slot next, and pays
only that catalog's first real render (1-2 s, since Compose, the font stack
and the PNG encoder are already hot). A warmed idle worker is ~570 MB RSS.

The catch is the parent classpath. `ServeBundleDaemon.bundleDaemonClasspaths`
puts each catalog's own Compose/AndroidX/coroutines overlay jars *ahead* of
the sidecar on the daemon `-cp`, so a catalog compiled against a newer
Material or Lifecycle resolves its own versions. A generic worker is
therefore generic only within one **overlay signature** (the ordered set of
overlay jars). The spare pool is keyed by that signature; the common
signature on a box (all the design-artifacts catalogs on one Compose BOM) is
pre-booted, a new signature pays a cold boot once — "first can be slower".

What has to change:

- **The worker protocol gains `configure`** (`SandboxWorkerProtocol`): user
  class dirs, user packages, previews manifest. Today those are sysprops read
  once at `SandboxWorkerMain` start; the holder's URL list is fixed at
  construction. `swap()` already rebuilds the child loader, so a
  `configure` that replaces the URL list and swaps is a small change.
- **Slot 0 stops being special.** The daemon JVM hosts a sandbox too, and it
  is the one that cannot be adopted. Either the daemon becomes a thin router
  whose every slot is a worker (the interactive slot pinned to one of them),
  or the daemon itself is the pre-booted unit: serve launches N generic
  daemons per signature ahead of demand and `initialize` carries the catalog
  (user class dirs, previews manifest) instead of the launch descriptor. The
  second is less code and keeps the protocol shape; it needs a new
  `initialize` parameter in `compose-preview-contracts`.
- **Serve owns the spares.** `ServeSharedDaemonPool` / `ServePerPreviewDaemonPool`
  currently open a daemon per catalog on demand and reap replicas after 60 s
  idle — the churn that produced 60 boots in 8 h on the deployed box. With
  spares, reaping a replica returns it to the spare pool (after a classloader
  swap that drops the catalog's classes) instead of killing three JVMs.

Cost to build: protocol + serve pool work, a week-ish. Return: a resident
signature's "boot" becomes adopt (ms) + first render, i.e. the target, on
the JDK and Robolectric we have.

### A2. CRaC checkpoint of a warmed worker

Coordinated Restore at Checkpoint: boot, warm-render, checkpoint the worker
JVM to disk; every later worker `restore`s in well under a second and dials
the parent. Same overlay-signature keying as A1, same "first is slow". It
needs a CRaC-enabled JDK (Azul Zulu CRaC or the OpenJDK CRaC builds —
Temurin does not ship it) and CRIU privileges in the container
(`CAP_CHECKPOINT_RESTORE`, or `--privileged`), and the Robolectric native
runtime (`libandroid_runtime`, software GL) has to survive checkpoint, which
mostly means no open sockets or GPU handles at checkpoint time — the
worker's parent socket would have to be opened after restore. Higher
operational risk than A1 for a similar payoff; worth a spike once A1 exists,
because it also covers the *first* worker of a signature after a deploy.

## Tier B — make a cold boot itself cheap (a Robolectric fork)

Picking this up? Start from [BOOT-ROADMAP-B-HANDOFF.md](BOOT-ROADMAP-B-HANDOFF.md):
it carries the post-Tier-A numbers, the spike that decides each item, and the order.

Robolectric is where the cold cost lives, and every item here is a change
to it. The daemon already runs an unusual Robolectric — one sandbox held
open for the JVM's life, no per-test reconfiguration — and several of
Robolectric's costs exist to support exactly the flexibility the daemon
never uses. That is the argument for a fork with upstreamable opt-ins.

### B1. Closed-world shadow binding (the big one)

Every method call in instrumented code is an `invokedynamic` whose bootstrap
asks `ShadowWrangler` whether a shadow applies, then links a `MethodHandle`
chain. That indirection exists so a *later test* can install different
shadows. The daemon's shadow set is fixed at boot, so every one of those
6,500 hidden classes per JVM is paid for a flexibility that is never used.

A fork would add an instrumentation mode that, given the shadow map at
instrumentation time, emits **direct calls**: `invokevirtual` to the real
method where no shadow applies, `invokestatic` to the shadow method where one
does. `android-all-instrumented` would be re-instrumented once per
(Robolectric version, shadow set) — a build-time job, published as our own
`android-all` coordinate (see B3). Effect: the `java.lang.invoke` cost
disappears from both the boot (~3 s of 4) and the warm render (roughly half
of it), and — because nothing is defined at runtime any more — every class
becomes archivable (B5). This is the change that makes the cold path itself
approach the target. Upstream shape: `-Drobolectric.instrumentation=static`
with the shadow set frozen after the first sandbox.

### B2. Boot without JUnit, on the sandbox / simulator API

The daemon boots through `JUnitCore.runClasses(SandboxRunner)` with a dummy
`@Test` that holds the sandbox open. The [B2 spike](BOOT-ROADMAP-B2-SPIKE.md)
implements a test-only comparison that bypasses JUnit execution while preserving
the daemon runner's configuration and application lifecycle.

Robolectric 4.17-beta-4's simulator can build a sandbox outside JUnit, but its
`AppLoader` still calls `AndroidTestEnvironment.setUpApplicationState`. Its builder
also lacks hooks for the daemon's custom acquisition rules and extra shadows.
Switching entry points alone therefore does not remove application setup,
framework-service initialization, or crypto-provider registration. Reducing those
costs is separate work, potentially paired with B4. Keep the production runner
until a measured saving justifies owning its configuration and lifecycle.

### B3. Our own SDK artifact

Robolectric's `MavenArtifactFetcher` downloads `android-all-instrumented`
(200 MB, the whole framework) into `~/.m2`. The deploy image bakes it, so the
download is not the problem; the *contents* are. A published
`compose-preview-android-all` would be: pre-instrumented **statically** (B1),
trimmed of the framework services a preview never reaches, with a lazy
`SystemServiceRegistry` (register on first `getSystemService`, not in
`<clinit>`), and shipped alongside a pre-built class archive (B5) and the
pre-extracted native runtime. SDK management stays Robolectric's — one
coordinate per SDK level — it just points at our artifact.

### B4. A capture path without an Activity

The [B4 spike](BOOT-ROADMAP-B4-SPIKE.md) now implements a test-only standalone
`PhoneWindow` host with a `ComposeView`, explicit tree owners, and native
PixelCopy capture. Three fresh-JVM trials per mode preserved PNG and hierarchy
parity for four static fixtures. Against a matched minimal Activity host, median
first-render time fell from **1551 to 1336 ms**, with **411 fewer loaded classes**;
warm captures fell from **43.5 to 34.5 ms**. These are experimental host timings,
not production worker readiness or full-engine throughput.

The original ~700-class estimate was a hypothesis. Much of Activity launch's
work moves into window attachment and composition rather than disappearing.
The spike also found and removed a two-second Compose-root wait caused by
pending attachment on Robolectric's paused looper. Broad consumer compatibility,
Activity-dependent extensions, interactive sessions and general capture geometry
remain prerequisites for an opt-in production host with fallback.

### B5. Make the sandbox archivable

CDS/AppCDS can archive classes from custom loaders, but only ones the JVM
saw come from a jar (`source:` in the classlist). `SandboxClassLoader`
reads bytes and calls `defineClass` for *everything* it acquires, so the JVM
records no source and nothing is archived — the shipped archive covers the
~3,500 builtin-loader classes only. Two fork changes: delegate
un-instrumented classes to `URLClassLoader.findClass` so they become
archivable, and — with B1 — stop needing a custom loader at all, at which
point Project Leyden's AOT cache (JDK 24+, `-XX:AOTCache`, loaded *and
linked* classes with training-run profiles) covers the whole sandbox and a
cold boot is sub-second before the warm render.

### B6. Parallel class definition

`SandboxClassLoader` is not registered parallel-capable, so every
`loadClass` on it serializes on the loader. With the boot class list known
(it is deterministic per classpath), a fork could pre-define the list from
N threads while the main bootstrap proceeds. Useful only until B5 removes
the definition cost; skip if B1/B5 land first.

### B7. Smaller items

- `libandroid_runtime.so` and the font set are extracted from the
  nativeruntime jar into a temp directory on every JVM start; pointing
  `robolectric.nativeruntime.fontdir` (and a matching library-dir override,
  which 4.17 lacks) at a pre-extracted image path saves the I/O on a box
  whose page cache is under pressure.
- BouncyCastle: `AndroidTestEnvironment` constructs and installs the
  provider unconditionally (~0.75 s, 709 classes from a signed jar). A fork
  makes it lazy; nothing outside a fork can.
- Pure-JVM libraries (`kotlinx.serialization`, guava, okio) are acquired by
  the sandbox by default and re-defined per JVM; `doNotAcquirePackage` moves
  them to the parent (and into the archive). Coroutines is the trap:
  `Dispatchers.Main` is discovered by `ServiceLoader` from core, and the
  `kotlinx.coroutines.android` factory is invisible from the parent.

## Tier C — the other runtimes

- **GraalVM native-image** is the very long-term version of B1+B5: a closed
  world where class loading, verification and call-site linking are done at
  build time and the process starts in ~100 ms. It is incompatible with
  Robolectric as it stands — runtime bytecode instrumentation and custom
  loaders do not exist under native-image — but a statically bound
  Robolectric (B1) plus a fixed sidecar is a closed world, and the JNI
  native runtime is configurable. The catalog's classes would have to be in
  the image too, so it becomes "build a native image per overlay signature
  on first sight, minutes, then start every daemon from it in a fraction of
  a second". Leyden gets most of that without leaving HotSpot.
- **Layoutlib** (what Android Studio previews render with) is a rewrite of
  the renderer, not a boot optimisation; it removes Robolectric's costs by
  removing Robolectric, at the price of every shadow-based data product.

## Shipped

- **A1, first cut** — spare workers. `SandboxSparePool` (`:daemon-client`)
  keeps generic, warm `SandboxWorkerMain` JVMs per overlay signature and
  hands their ports to each Android daemon launch; `RobolectricHost.start()`
  adopts them before its own sandbox boots and returns on them
  ([SANDBOX-POOL.md § "Spare workers"](SANDBOX-POOL.md#spare-workers-adopt-dont-boot)).
  A reaped daemon releases its adopted workers back to the pool (a fresh
  handshake on the same stdout), so the next open adopts the *same* warm
  sandboxes rather than replenished ones. Not yet in this cut: charging
  spares to the server's live-seat budget, and the serve-side wiring itself.
- Worker `i+1` boots underneath worker `i`'s warm render
  ([SANDBOX-POOL.md](SANDBOX-POOL.md)).
- Serve-spawned catalog daemons get a per-classpath auto-created CDS archive
  and skip remote bytecode verification, with JVM logging routed off the
  JSON-RPC stdout (`ServeBundleDaemon.androidDaemonStartupJvmArgs`).

## Suggested order

1. A1 (adoptable warmed workers keyed by overlay signature) — reaches the
   target on today's Robolectric; the serve-side churn fix falls out of it.
2. B2 then B1 in a fork, published as B3 — makes the first boot of a
   signature and every capacity burst cheap, and unlocks B5.
3. B4 alongside, since it also speeds every catalog's cold first render.
4. A2 / Leyden as spikes once 1-2 exist.
