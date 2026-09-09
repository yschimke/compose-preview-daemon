# Preview daemon — sandbox pool

## Status

The pool is **out-of-process** (issue #3072): the daemon JVM hosts exactly
one Robolectric sandbox and every additional sandbox is a worker JVM.

- **Slot 0** — the sandbox in the daemon JVM. Serves renders and backs held
  interactive / recording sessions.
- **Slots 1..N-1** — one worker JVM each
  ([`SandboxProcessPool`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/pool/SandboxProcessPool.kt)
  spawns them,
  [`SandboxWorkerMain`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/pool/SandboxWorkerMain.kt)
  is what runs inside one), talking newline-delimited JSON over a loopback
  socket.
- `RobolectricHost(sandboxCount = N)` is unchanged as the public surface;
  `sandboxCount = 1` spawns nothing and behaves exactly as it always has.

## Why processes — the constraint that killed the in-JVM pool

Robolectric's native-graphics runtime binds to a **single classloader per
process**. `DefaultNativeRuntimeLoader` loads `libandroid_runtime.so` once
and registers its JNI natives against whichever sandbox's instrumented
framework classes got there first; its `loaded` flag lives in a static owned
by *that sandbox's* classloader. A second sandbox in the same JVM therefore
sees `loaded == false`, re-runs `Typeface.loadPreinstalledSystemFontMap()`
against an already-loaded library, and gets back a font map with no default
family:

```
NullPointerException: Cannot read field "mStyle" because "family" is null
  at android.graphics.Typeface.create(Typeface.java:928)
  at android.graphics.Typeface.setSystemFontMap(Typeface.java:1410)
  at android.graphics.Typeface.loadPreinstalledSystemFontMap(Typeface.java:1550)
  at org.robolectric.nativeruntime.DefaultNativeRuntimeLoader.ensureLoaded(…)
```

The next native text call then takes the whole process down with a `SIGSEGV`
in `libandroid_runtime.so` (exit 134). This is a hard constraint, not a
version bug — Robolectric 4.16.1 and 4.17-beta-2 behave identically.

Workarounds that were tried and **do not work** (don't re-try them):

| attempt | result |
|---|---|
| Drop the per-worker sandbox-cache discriminator so workers share one sandbox | Font crash gone, but slot 1 never registers — `start()` hangs to the full boot timeout |
| Parent-load `org.robolectric.nativeruntime` so `loaded` is process-global | Font crash gone, replaced by `UnsatisfiedLinkError` from `RenderNode` — the runtime registers its JNI natives against one sandbox's instrumented framework classes |
| Robolectric 4.16.1 instead of the pinned 4.17-beta-2 | Identical NPE |

Two *sequential* single-sandbox hosts in one JVM are fine — they share one
`InstrumentationConfiguration`, so Robolectric hands back the same cached
sandbox and the native runtime initialises exactly once. That is why the
pre-#3072 per-worker cache-key discriminator (a synthetic
`doNotAcquireClass("composeai.sandbox.uniq.RunnerN")` in
`SandboxHoldingRunner`) is **gone**: it existed purely to defeat that cache,
which is the one thing that must not happen.

## Anatomy

```
 ┌──────────────────────────────────────────────┐
 │ daemon JVM                                   │
 │   JsonRpcServer                              │
 │   RobolectricHost(sandboxCount = N)          │
 │     ├── slot 0: in-process Robolectric sandbox
 │     │            └── held interactive session lives here
 │     └── SandboxProcessPool                   │
 │           ├── worker JVM ── slot 1 sandbox   │
 │           ├── worker JVM ── slot 2 sandbox   │
 │           └── …                              │
 └──────────────────────────────────────────────┘
        loopback socket, newline-delimited JSON
```

A worker is **not** a daemon: no preview index, no extension registry, no
watch state, no JSON-RPC. `RobolectricHost.submit` resolves a `previewId`
into a full spec payload *before* dispatch, so the only things crossing the
process boundary are a payload in and a `RenderResult` out
([`SandboxWorkerProtocol.kt`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/pool/SandboxWorkerProtocol.kt)):

| message | direction | meaning |
|---|---|---|
| `ready` / `bootFailed` | worker → pool | sent once, after the worker's sandbox boots |
| `render` → `result` / `failed` | pool ↔ worker | one render, one reply |
| `swap` → `ok` | pool → worker | `fileChanged{kind:"source"}` — drop the child classloader |
| `shutdown` → `ok` | pool → worker | drain and exit |

**The result survives the trip intact.** `RobolectricHost` already reduced a
sandbox-side `RenderResult` to plain data before handing it to callers:
`copyPreviewContextAcrossClassloaders` copies the device context and the
Material3 theme payload and deliberately drops the live `slotTables` /
`rootForTest` handles (Compose objects the host classloader can't use). Every
field that survives that copy is a `String`, number or `Map`, so the wire DTO
is faithful — a worker-served render produces the same host-side
`RenderResult`, and the host-side data products (`ExtensionRegistry.onRender`)
see the same input either way. **If a field ever starts surviving the
classloader copy, add it to `PreviewContextDto` too.**

Workers inherit the daemon's JVM flags (heap, GC, `--add-opens`) minus
anything that must not be duplicated (agents, JDWP), plus every `composeai.*`
/ `robolectric.*` / `android.*` / `roborazzi.*` system property — which is
what makes a worker's render configured identically to the daemon's own,
including `composeai.daemon.userClassDirs` for hot reload.

## Interactive sessions run on slot 0

`INTERACTIVE_SLOT_INDEX` moved from 1 to **0**. A held session hands callers
an `AndroidInteractiveSession` backed by live bridge queues, frame latches and
a `ComposeTestRule` — object handles, not a serializable request/response
pair, so they can't be proxied over the worker socket. Normal renders are the
ones that serialize cleanly, so they are what moves out of process: while a
session holds slot 0, `chooseSlotIndex` routes every render to slots 1..N-1.

`supportsInteractive` therefore still reads `sandboxCount >= 2` — one sandbox
to pin, at least one left to render. `acquireInteractiveSession` additionally
refuses (cleanly, with the `Unsupported` the caller already handles via its v1
fallback) while fewer than two slots are ready, so a session can never pin the
only dispatchable sandbox during a background boot.

## Slot dispatch / affinity

Unchanged: `Math.floorMod(hash(previewId ?: requestId), readySlots)`, so the
same preview lands on the same sandbox and its per-sandbox caches pay off.
With `sandboxCount = 1` dispatch collapses to slot 0, bit-identical with the
pre-pool path.

## Background pool boot (cold-start fast path)

`composeai.daemon.backgroundSandboxBoot` (launch descriptors default it **on**,
the raw sysprop defaults **off** — see [CONFIG.md](CONFIG.md)) makes `start()`
block only until slot 0 is up; the workers boot one at a time on a background
daemon thread. Dispatch routes across the ready prefix meanwhile, so a render
never queues on a still-booting worker.

- **A permanent worker boot failure caps the pool** at the slots already
  booted (loudly logged) instead of aborting the daemon. The eager path keeps
  the strict contract: a failed worker throws out of `start()`.
- **Boot-time warm render.** Each worker renders the daemon-shipped
  `DaemonWarmupPreview` once before it is published for dispatch (disable with
  `-Dcomposeai.daemon.warmRenderOnBoot=false`), so a live render never queues
  behind a cold warm-up. Slot 0 is left to serve's own `prewarm()`.
- **The warm render overlaps the next worker's boot.** A worker's sandbox boot
  and its warm render cost about the same (~6 s and ~6-8 s on an idle 4-core
  box — [STARTUP.md](STARTUP.md) has the profile), so worker `i+1` is launched
  the moment worker `i` has booted and boots underneath `i`'s warm render. The
  pool still boots one worker at a time; only the warm render is overlapped,
  and a slot is still published only after its own warm render.
- **A worker that dies mid-request is dropped from the pool**, logged with its
  pid; the remaining slots keep serving.

## Spare workers (adopt, don't boot)

The 2-3 s target in [BOOT-ROADMAP.md](BOOT-ROADMAP.md) § A1. A sandbox does
not depend on the catalog it renders: the catalog's classes ride the disposable
child classloader, and the boot-time warm render touches none of them. So a
worker booted and warm-rendered against *no* catalog can be handed to whichever
daemon needs a slot next, and pays only that catalog's first real render.

```
 serve JVM (daemon-client)                          daemon JVM
   SandboxSparePool                                   RobolectricHost.start()
     ├── spare A  (signature S, warm, listening :p1)  ──adopt──▶ slot 1  ┐ serving in ms
     ├── spare B  (signature S, warm, listening :p2)  ──adopt──▶ slot 2  ┘
     └── spare C  (signature T, booting)               slot 0 boots BEHIND them (background)
```

- **A spare is `SandboxWorkerMain` in spare mode**
  (`-Dcomposeai.daemon.sandboxWorker.spare=true`): it boots, warm-renders,
  opens a loopback `ServerSocket` and prints one line on stdout —
  `composeai-spare-worker: listening pid=… port=…`. It is a child of the
  spare pool's JVM and halts with it.
- **`SandboxSparePool`** (`:daemon-client`) launches spares from a
  `DaemonLaunchDescriptor` and keys them by **overlay signature**: the daemon
  classpath in order, the JVM flags (minus the per-JVM CDS archive path) and
  the boot-time system properties (`robolectric.*`, `android.*`,
  `roborazzi.*`, `userClassPackages`, …). A spare is launched with *only*
  those properties — no class dirs, no previews manifest, no output dir —
  because the sandbox is what they shape. It is demand-driven: every launch
  tops the signature it just used back up to `perSignature`, inside a total
  `maxSpares` budget with least-recently-reserved eviction across signatures.
  The first launch of a signature is therefore cold; every later one adopts.
- **Hand-off** is a launch-descriptor property. `SubprocessDaemonClientFactory`
  reserves the warm spares of the launch's signature and passes their ports as
  `composeai.daemon.sandboxWorker.spares`. `SandboxProcessPool.adoptSpare`
  connects, sends `configure` — every `composeai.*` / `robolectric.*` /
  `android.*` / `roborazzi.*` property the pool would have put on a cold
  worker's command line, plus the daemon's pid — and reads `configured`. The
  worker applies the properties, rebuilds its child classloader from the new
  `userClassDirs` (`RobolectricHost.resetUserClassLoaderHolders`), and watches
  the adopting daemon so it halts with it. Any failure falls through to a cold
  boot of that slot, so a stale port list costs one connection attempt.
- **`start()` adopts first.** Under background boot, a host handed spares
  fills its worker slots from them *before* anything boots, marks them ready
  and returns: `daemonReady` still means "a sandbox can render", just on a
  worker. Slot 0 — the in-process sandbox, the one no spare can replace — then
  boots on the background thread, followed by any slot the spares did not
  cover. Readiness is per slot (no longer a contiguous prefix); the
  interactive and `@PreviewParameter` paths, which need slot 0, wait for it
  the way they already waited for a background-booted worker. `RenderEngine`
  resolves `composeai.render.outputDir` per render, not at construction, which
  is what lets a spare's engine — built by its warm render before any catalog
  is known — write into the adopting catalog's tree.
- **Slot 0 can be deferred.** `composeai.daemon.lazyInProcessSandbox=true` makes an
  adopt-first start skip the background boot of the in-process sandbox
  altogether: it boots the first time a path only it can serve asks —
  `acquireInteractiveSession`, `previewParameterRows`, or a render with no
  worker left to route to (`RobolectricHost.ensureInProcessSandbox`, one boot
  ever, bounded by the sandbox boot budget). A serve catalog daemon holds no
  interactive session and enumerates no rows in the common case, so on the
  deployed box this is a boot and a sandbox's worth of resident memory per
  daemon that is never paid, at the cost of one slot of render capacity
  until something needs it. Off by default: the Gradle/VS Code path wants
  slot 0 for the panel's live toggle.
- **What the signature does not cover.** Android resource carriage
  (`test_config.properties` for the app's own `R` table) is on the parent
  classpath, so a catalog carrying resources has a signature of its own; it
  still benefits when its replica is reopened. A held interactive session and
  row enumeration live on slot 0 and gain nothing from spares.

## Memory

The honest trade: this is a **process** pool, so per-slot cost is a whole JVM
again — roughly the pre-Layer-3 profile (~1–2 GB per sandbox), not the ~75 MB
per extra classloader the in-JVM pool claimed. That claim was never realisable:
the second in-JVM sandbox cannot boot at all. `SandboxPoolMemoryBench` now
reports the daemon JVM plus each worker's RSS from `/proc/<pid>/status`; rerun
it and update this section on Robolectric / JDK / framework upgrades.

Sizing knobs are unchanged — `replicasPerDaemon` on the supervisor,
`composeai.daemon.sandboxCount` on the daemon — so a memory-constrained host
turns the pool down the same way it always did (`replicasPerDaemon = 0`).

## What this does NOT solve

- **Cross-module sharing.** Different Compose / Kotlin / AGP versions still
  need separate daemons.
- **Concurrent held sessions.** Still one at a time per host — the pinned slot
  is the in-process one.
- **Desktop replicas.** AWT EDT and Skiko's GPU context are process-global;
  out of scope.

## Cross-references

- BOOT-ROADMAP.md § A1 — the plan the spare workers implement, and what comes
  after (CRaC, a Robolectric fork).

- DESIGN.md § 9 — sandbox bootstrap and recycle policy.
- CLASSLOADER.md — parent/child classloader split per slot.
- ROBOLECTRIC-PRIMER.md § "Native library loading" — the load-once-per-JVM
  constraint this design is built around.
- INTERACTIVE-ANDROID.md § 2 — held-session capacity.
