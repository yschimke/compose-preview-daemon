# Embedding the daemon

**Status: decided; being built.** It exists to settle one question — *what does this repository own
when somebody wants to run a daemon, and what does the caller own?* The three questions it opened
with are answered under "The decisions" below, and the status table there says which pieces exist.
Piece 3 (`DaemonSession`) has landed; the rest have not.

The framing assumption, given: **this daemon should be generally reusable outside
compose-ai-tools and compose-preview-server.** That raises the bar. An API that only has to satisfy
two callers who can read our source can be thin and undocumented; one that has to satisfy a caller
who cannot has to carry the knowledge with it.

## The question, stated precisely

Not "supervisor or protocol". That is a false binary, and picking either end of it is wrong:

- *Protocol only* is what we have, and it does not work — see below, the caller is currently forced
  to know things about this repository it cannot discover.
- *Supervisor* is too much. The server's LRU pool, its seat budget, the MCP multi-workspace
  registry, the "retain a daemon that backs a live stream past the cap" rule — those are
  **application policy**, and a library that owned them would be wrong for the next caller.

The useful question is where, between those, the seam falls. This proposal argues it falls in a
specific and identifiable place: **the daemon should own everything that is a fact about the daemon,
and nothing that is a decision about the application.**

That sounds obvious. The point of the evidence below is that we are currently well on the wrong side
of it, in ways that are invisible until you go looking.

## What is actually there today

Measured, not recalled. Line counts are `wc -l` at `compose-preview-daemon@fd83042`,
`compose-ai-tools@c8a54a7`, `compose-preview-server@HEAD`.

### The daemon already publishes a launcher — but only the dumb half

`:daemon-client` ships:

| type | lines | what it does |
| --- | --- | --- |
| `DaemonClient` | 730 | the JSON-RPC client: every method, typed |
| `DaemonClientFactory` / `DaemonSpawn` | 51 | the pluggable spawn seam |
| `SubprocessDaemonClientFactory` | 200 | forks a JVM from a `DaemonLaunchDescriptor` |
| `SandboxSparePool` | 352 | pre-booted spare workers |
| `WorkspaceId` | 43 | identity |

`SubprocessDaemonClientFactory` is a faithful executor: it takes a fully-formed
`DaemonLaunchDescriptor` — `classpath`, `jvmArgs`, `systemProperties`, `mainClass`,
`workingDirectory`, `javaLauncher`, `jailCommand` — and runs it. It decides almost nothing.

**Everything that decides what goes *in* a descriptor lives in the callers.** That is the gap.

### Gap 1 — the caller has to know how to run our Android backend

`compose-ai-tools`' `bundle/format/…/AndroidBundleLaunch.kt` (265 lines) owns:

- the exact `--add-opens` set Robolectric needs on JDK 17+ (`java.base/java.io`,
  `java.base/java.lang`, `java.base/java.lang.reflect`, `java.base/java.nio`,
  `java.base/jdk.internal.access`);
- the `robolectric.*` system properties — `graphicsMode=NATIVE`, `looperMode=PAUSED`,
  `conscryptMode=OFF`, `pixelCopyRenderMode=hardware`;
- the synthesized package-level `robolectric.properties` body, and the SDK-level clamp;
- `android.jar` discovery from `ANDROID_HOME` / `ANDROID_SDK_ROOT`.

Every one of those is a fact about **how this repository's Robolectric host runs**. None is a
decision the CLI is entitled to make. And the clinching detail: that file writes its synthesized
`robolectric.properties` into `<root>/ee/schimke/composeai/renderer/` — *the renderer's own package*,
which lives here. A module in repository 1b hardcodes a package path in repository 1a so that
Robolectric's property merging finds it.

Those two things now version independently. A change to the renderer's package, or to the flags its
host needs, is a silent break in a repository whose CI cannot see it. We have already been bitten by
exactly this shape of drift twice this month — #10 (six divergent copies of one merge) and #13's
rebase (a new `pngPath` read that no branch's CI could catch).

### Gap 2 — the sysprop registry stops at the repository boundary

`daemon/core/…/config/DaemonProperties.kt` (797 lines) is genuinely good work. Its own KDoc states
the goal: the names were "an *unversioned public API surface* nobody could enumerate", so they were
made enumerable (`DaemonProperties.ALL`, and `docs/daemon/TUNABLES.md` generated from it), typed, and
renameable — `DaemonPropertyRegistryTest` fails if a `"composeai.daemon.…"` literal survives anywhere
in `daemon/`.

Distinct `composeai.*` string literals outside that registry:

| repository | literals |
| --- | --- |
| compose-preview-daemon | 53 (inside the registry; the test enforces this) |
| **compose-ai-tools** | **62** |
| **compose-preview-server** | **7** |

So renaming a knob is a compile error in one repository and a silent behaviour change in the other
two. The registry solved the problem for the half of the namespace we read and left the half we are
*handed* untouched — and the producer side is the half a third-party embedder writes.

Note the registry already anticipates this: it declares "producer-only entries … written by the
Gradle plugin's daemon launch descriptor and read outside `daemon/`". It knows the producers exist.
It just has nothing to offer them.

### Gap 3 — `DaemonClient` is a class, so every consumer writes an adapter

`compose-ai-tools` defines its own `RenderSession` interface and implements it in
`DaemonClientRenderSession` (311 lines). Read it: **every method is a one-line forward** to
`DaemonClient`. It exists because the daemon publishes a concrete class where the consumer needed a
mockable interface. The next embedder writes the same file.

### What is *not* duplicated, and should stay put

Being fair to the current design, because it constrains the proposal:

| | lines | verdict |
| --- | --- | --- |
| `ServePerPreviewDaemonPool` (server) | 192 | **application policy.** LRU + seat budget + "never evict a daemon backing a live stream". Correct where it is. |
| `DaemonSupervisor` (server) | 738 | **mostly policy.** Multi-workspace registry, `register_project`, notification fan-out to MCP handlers. |
| `ServeBundleDaemon` (tools) | 1201 | **mixed.** Bundle extraction and coordinate resolution are tools' job. Sidecar location, backend flags and descriptor authoring are not. |
| `ServePerPreviewLiveHost`, `ServeLiveSession` | 383 + 323 | **application policy.** |

I should correct a claim I made earlier in this work: I described "~3,000 lines of glue duplicated in
shape across both consumers". That was a **size** measurement, and size is not duplication. Having
read them, most of those lines are legitimately per-application. The real finding is narrower and
worse: not that a lot of code is duplicated, but that a *specific, small, load-bearing* body of
knowledge about how to run this daemon lives in repositories that do not own it.

## Proposal

Four pieces, each independently useful and independently landable, ordered by value-to-risk. A
caller can adopt one and stop.

### 1. `DaemonLaunchPlan` — we say how to run us

A published builder that turns *what the caller has* into a `DaemonLaunchDescriptor`.

```kotlin
public sealed interface DaemonBackend {
  public data object Desktop : DaemonBackend
  public data class Android(
    val androidJar: Path,
    val sdkLevel: Int = DEFAULT_SDK_LEVEL,
  ) : DaemonBackend {
    public companion object {
      /** ANDROID_HOME / ANDROID_SDK_ROOT, highest `platforms/android-N/android.jar`. */
      public fun discover(): Android?
    }
  }
}

public class DaemonLaunchPlan(
  public val backend: DaemonBackend,
  /** The composables to render, and what they need. The caller's half. */
  public val applicationClasspath: List<Path>,
  /** Where this daemon's own jars live — see the note on distribution below. */
  public val runtime: DaemonRuntimeLocation,
  public val workingDirectory: Path,
  public val options: DaemonLaunchOptions = DaemonLaunchOptions(),
) {
  public fun describe(): DaemonLaunchDescriptor
}
```

The plan owns `mainClass`, the JVM args, the `robolectric.*` properties, the
`robolectric.properties` synthesis and its package path, heap defaults, and the sandbox-count wiring.
The caller owns the application classpath, the working directory and its own options.

**On distribution.** Locating `lib-daemon-desktop` / `lib-renderer` / `lib-daemon-android` inside a
CLI install *is* the CLI's business and stays there — but which artifacts a backend needs is ours.
`DaemonRuntimeLocation` should therefore be an interface with a documented required-artifact list, so
the daemon states the requirement and the caller satisfies it however its packaging works (a CLI
tarball, a Gradle configuration, a Maven resolution, an IDE plugin's bundled jars).

### 2. `DaemonLaunchOptions` — the writer side of the registry

Typed fields that render into the sysprop map, so no caller spells a name:

```kotlin
public data class DaemonLaunchOptions(
  val sandboxCount: Int? = null,
  val maxHeapMb: Int? = null,
  val idleTimeout: Duration? = null,
  val historyDir: Path? = null,
  // …
) {
  public fun toSystemProperties(): Map<String, String>
}
```

Cheap, mechanical, and it lets `DaemonPropertyRegistryTest`'s guarantee finally reach the callers:
a rename becomes a compile error in all three repositories instead of one. Any knob without a typed
field stays reachable through an escape hatch keyed on `DaemonProperties.Names`, so this need not be
exhaustive on day one.

### 3. `DaemonSession` — publish the interface

Extract the interface `DaemonClient` already satisfies and publish it. Deletes tools'
311-line adapter, and gives every embedder and every test a seam without one.

### 4. `ManagedDaemon` — the inner lifecycle loop, and only that

The state machine both consumers hand-roll: spawn → `initialize` → route notifications → observe
death → shut down, with the handshake timeout and the stderr forwarding.

Explicitly **not** in scope: registries, pools, eviction, seat budgets, workspace concepts,
restart-on-crash policy. Those differ per application and belong to the application. `ManagedDaemon`
is one daemon's life, exposed as a small state machine the caller drives.

## Testing

The brief said solid and well-tested is worth the work, so this is not an afterthought section.

**Golden descriptors.** Every backend's `describe()` output is asserted against a committed golden
file. The whole point is that the Robolectric flag set and the `robolectric.properties` body become
*reviewable in a diff* instead of being a fact you learn by reading another repository. This is the
single highest-value test here.

**A conformance suite (TCK).** `:daemon:harness` already has the machinery — a `FakeHost` and
real-daemon JSON-RPC scenarios. Publish a suite an embedder runs against their own wiring:
"does your `DaemonSession` implementation answer these thirty calls correctly?" This is what makes
"generally reusable" a testable claim rather than an aspiration.

**A real launch smoke test, here.** `compose-ai-tools` has `DaemonSmokeCheck` (213 lines) that spawns
a daemon and renders. That test belongs in the repository whose launch it verifies.

**Property-based on the options mapping.** `toSystemProperties()` round-trips against
`DaemonProperties`' parsers for every declared knob — so the writer and the reader cannot disagree
about a default or a format.

## Cost, and the honest risks

- **This is a real API commitment.** Everything above lands on the published surface under
  `abiValidation()`, and the whole premise is third parties depending on it. Getting
  `DaemonRuntimeLocation` wrong is expensive to undo. It is the piece I am least sure of and the one
  most worth arguing about before code.
- **Two release trains.** `DAEMON_SPLIT.md` already names this cost. Every piece here adds surface
  that a tools change must wait on.
- **Migration is not free.** `AndroidBundleLaunch` has real behaviour and real tests; moving it means
  moving those and proving the descriptor is byte-identical before and after. The golden-descriptor
  tests exist partly to make that provable.

## Timing — why now specifically

`DAEMON_SPLIT.md` records that compose-ai-tools "still holds the originals until its switch-over
deletes them", and that is the current state: tools builds `:daemon-client` (settings.gradle.kts:624)
from a byte-identical copy of this repository's source.

That switch-over is the moment to do this. Tools is about to stop compiling its own copies and start
consuming coordinates; that is exactly when the question "which side of the boundary does this
knowledge live on?" has to be answered for every file anyway. Doing it then costs one pass over the
call sites. Doing it later costs a second pass plus a deprecation cycle on whatever the switch-over
froze in place.

## The decisions, and what they turned on

The three questions this document opened with have been answered. Recorded here rather than in a
pull request comment, because a reader six months out needs the reasoning and not just the outcome.

### 1. The seam is facts-here, decisions-there — **and the negative rule is part of it**

The rule earns its place by being decidable at review time: *could a second reasonable caller want
this different?* The Robolectric `--add-opens` set — no, our host determines it, so it is ours. LRU
eviction — obviously yes, so it is theirs. Most cases fall out immediately.

It separates cleanly for **mechanisms** and badly for **defaults**. `sandboxCount = 4` is a
deployment decision (a memory budget); the sandbox-count *wiring* is a fact about us. Without that
distinction stated, the daemon accretes defaults that are really somebody's deployment policy, each
one locally justified.

So the negative rule is normative alongside the positive one:

> **The daemon never decides how many daemons exist, how long they live, or what happens when one
> dies.** It may state a default where a caller must supply *something*, but a default is a
> suggestion the caller overrides, never a policy the caller inherits.

### 2. `DaemonRuntimeLocation` is an interface **in core, with a resolver as a separate artifact**

The question was posed as a choice and should not have been. Each end fails on its own:

- *Interface only* keeps us out of dependency resolution — a swamp of transitive conflicts, offline
  mode, checksums, proxies and credentials — and works against a CLI tarball, a Gradle
  configuration, an IDE's bundled jars or an air-gapped mirror. But it leaves a wall at step one,
  and "solve a 200 MB artifact-distribution problem before your first render" is precisely the wall
  that makes a library not reusable.
- *Resolver in core* gives one-line adoption and puts an HTTP client, a cache directory, checksum
  policy and proxy configuration into the daemon — the surface most likely to be wrong in somebody
  else's environment, which is exactly the population it exists to serve. Implicitly downloading the
  Android sidecar is also hostile behaviour for a library.

So: the interface and its required-artifact list are the contract, in `daemon-client`. A separate,
optional artifact implements it against published coordinates. Core stays free of resolution,
convenience is opt-in, and an enterprise caller swaps the implementation while keeping the contract.
The cost is one more published module, which is cheap against either failure above.

### 3. `ManagedDaemon` is worth owning, but **event-emitting, never policy-taking**

The loop has subtlety that is invisible from outside — `DaemonSpawn.client()`'s own KDoc requires
notification handlers to be wired *before the first frame*, an ordering constraint a newcomer
violates once and then debugs as a dropped event. Add the handshake timeout, stderr forwarding, hard
TTL arming, and telling "died" apart from "idle". Both consumers solved these independently; a third
party will get at least one wrong.

The risk is that it becomes a supervisor under a smaller name. It is prevented from doing so by
construction: **no restart, no backoff, no retry, no in-flight-request policy.** It sequences and it
reports; `onDied(cause)` fires and the caller decides. The test of whether the seam held is whether
the server's seat budget and the MCP registry can be built *on* it without fighting it or reaching
around it.

It lands last, because pieces 1–3 are independently valuable and this one benefits from seeing them
settle.

## Status

| piece | state |
| --- | --- |
| 3. `DaemonSession` interface | **landed** |
| 2. `DaemonLaunchOptions` | not started |
| 1. `DaemonLaunchPlan` | not started |
| 4. `ManagedDaemon` | not started |
