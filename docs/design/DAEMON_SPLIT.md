# The daemon split

**Status: normative for the boundary; historical for the move.** This repository was extracted from
[yschimke/compose-ai-tools](https://github.com/yschimke/compose-ai-tools) in September 2026, after
[compose-preview-server#626](https://github.com/yschimke/compose-preview-server/issues/626) profiled
the sandbox boot and the road to a 2-3 s boot ([`../daemon/BOOT-ROADMAP.md`](../daemon/BOOT-ROADMAP.md))
turned out to run through Robolectric itself: a fork with static shadow binding, a non-JUnit boot, our
own SDK artifact. That work wants a repository whose whole job is executing a composable, not the
CLI's.

## The layer it occupies

```
0  compose-preview-contracts   wire shapes two repositories must agree on as bytes
1a compose-preview-daemon      renderers, data extractors, the hosts, the daemon process
1b compose-ai-tools            discovery, the Gradle plugin, the CLI, bundles, history, render-host
2  compose-preview-server      HTTP, the web surfaces, the UI builder
```

The rule is the one `docs/design/REPOSITORY_LAYERS.md` in compose-ai-tools already states: a module
depends on a strictly lower layer and on nothing else. Two consequences decide what lives here:

- **Wire shapes stay in contracts.** The daemon's JSON-RPC methods, `DaemonLaunchDescriptor`,
  `PreviewOverrides`, `RenderTier` are already `compose-preview-contracts` (`daemon-protocol`); this
  repository depends on them and adds none of its own. A shape the daemon and the server must agree
  on across a process boundary goes to contracts, never here.
- **The in-process API is here.** "Invoke this composable on a desktop or Robolectric host and give
  me pixels and data products" is a Kotlin call, not bytes on a wire, so it is a library this
  repository publishes and tools and server consume downward (`daemon-api`, in progress).

## What moved, and why exactly this

The seam was computed, not chosen: the transitive project-dependency closure of the two hosts, the
three renderers and the daemon client. It is 70 modules, and nothing in it depends on anything left
behind — that is the property that made a single move possible.

| group | modules | why they are in the closure |
| --- | --- | --- |
| `daemon/` | `core`, `android`, `desktop`, `client`, `harness` | the thing itself |
| `renderers/` | `renderer-android`, `renderer-desktop`, `renderer-xr-client` | both hosts link them; `renderer-android` alone links 20 data modules |
| `data/` | 58 `*-core`, `*-connector`, `*-runtime` modules | the extractors the renderers run; a `core` cannot sit above the renderer that depends on it |
| `api/` | `preview-annotations`, `preview-data-api` | leaves both renderers and the data line depend on |
| `runtimes/` | `slot`, `lottie`, `svg` preview runtimes | leaves the desktop renderer and the hosts depend on |
| `distribution/` | new | the sidecar archives, previously staged by `cli/build.gradle.kts` |

Module paths are the ones the modules had in compose-ai-tools, so `git log --follow` works across
the move and the published coordinates are unchanged: every module publishes under
`ee.schimke.composeai` with the same `artifactId`.

**What stayed** (49 modules): the CLI, the Gradle plugin, `bundle-*`, `render-host`,
`render-session-*`, `render-matrix`, `build-host-protocol`, `gradle-preview-driver`, the samples and
their committed renders, and the consumer-facing runtimes that nothing here depends on
(`appwidget`, `color`, `glance`, `notification`, `splash`, `typography`, `wear`,
`data-shared-element-core`, `screen-model`). `daemon/bta-host` stayed too: it is a parity soak for the
in-process compile, which is a tools concern.

## The reverse edges, and what each consumer does now

Every edge from a remaining module into the closure becomes a published-coordinate dependency,
which is what the server has done since its own extraction:

| consumer in compose-ai-tools | depended on | now |
| --- | --- | --- |
| `:cli` | `daemon-client`, `daemon-core`, `daemon-android`, `daemon-desktop`, `renderer-desktop`, three data cores, `preview-data-api` | coordinates; the sidecar staging moved to `:distribution` here and the CLI fetches the archives |
| `:render-host`, `:render-matrix`, `:render-session-*` | `daemon-core`, `daemon-client`, `daemon-desktop` | coordinates |
| `:bundle-format`, `:build-host-protocol`, `:gradle-preview-driver` | `preview-data-api` | coordinates |
| the samples | `preview-annotations`, the runtimes, a few connectors | released coordinates, the swap `composeaiUseReleasedRuntimes` already performs for design-artifacts, now unconditional |
| the Gradle plugin's `composePreviewRender` | `renderer-android` as a test dependency | unchanged: it already resolved the published coordinate |

Three paths run the renderers and extractors **without a daemon** — the Gradle plugin's render task,
`bundle render`, and the CLI's offline commands. They keep working the same way: the code they call
is a library either side of the boundary.

## Fixtures that crossed the boundary with the tests

Three test oracles lived outside the closure and came along as copies, so the tools repository
still holds the originals until its switch-over deletes them:

- `schema/spatial-scene.schema.json`, `schema/xr-render-service.schema.json` and the fixtures
  under `schema/fixtures/` — the source of truth for the generated Kotlin in `:preview-data-api`
  and `:renderer-xr-client` (`scripts/codegen/*.mjs`, checked in CI). The rest of tools' `schema/`
  (the data-product report schemas) describes what the extractors here emit and belongs at the
  contracts layer; moving it is a follow-up, not this PR.
- `renderers/android/fixtures/pages/serve-wear-scroll-long-capsule.html` — the drift guard
  `WearScrollSvgGrowthTest` regenerates and compares. compose-preview-server vendors the same page
  for its screenshot lane; this copy is now the one the renderer test owns.

## What it costs

- **Two release trains for one change.** A new data product lands here, then a `composeai-tools`
  bump. For local iteration compose-ai-tools accepts an opt-in `includeBuild` of a sibling checkout
  (a Gradle property, off by default); the server repository forbids composites and is unaffected.
- **One version line here.** compose-ai-tools split `data/*` onto a second Maven line to avoid
  re-uploading 58 unchanged artifacts per release. Here the data modules are most of the build and
  change with the renderers, so every module releases at the tag; measure before reintroducing the
  split.
- **The name is narrower than the contents.** "Daemon" is one shape of what this repository holds;
  `AGENTS.md` says the honest thing: everything that executes a composable.

## The move, step by step

1. **This repository** ([PR #1](https://github.com/yschimke/compose-preview-daemon/pull/1)):
   history-filtered import of the 70 modules and `docs/daemon`, the build conventions
   (`build-logic`, the version catalog, ktfmt, the attribution gate, release-please on a single
   line), the sidecar packaging, CI.
2. **First release** from here at the next minor after the last compose-ai-tools release that
   published these coordinates, so consumers see one continuous version line.
3. **compose-ai-tools** switches every reverse edge above to the released coordinates, deletes
   the 70 modules, points its release job at the sidecar archives published here, and drops the
   `data` publish train. Tracked in compose-ai-tools.
4. **compose-preview-server** changes nothing: it already consumes `render-host` and the sidecar
   archives by coordinate and by release asset; only the asset's source repository moves, which the
   image's Dockerfile carries as a URL.
