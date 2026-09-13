# Use cases for the render daemon

Research, not a commitment. It asks what else the daemon is good for once you stop thinking of it
as "the thing behind the VS Code panel", and it is deliberately honest about how much of the
obvious answer is already shipped somewhere in the four repositories. The interesting items are the
ones where the primitive exists and nobody has pointed it at the workload.

## 1. What the daemon actually is

Three primitives, none of which is "render a preview":

1. **A warm, hermetic Compose execution host.** An Android (Robolectric) or Desktop (Skiko)
   sandbox held open across renders, with a disposable user classloader
   ([CLASSLOADER.md](../daemon/CLASSLOADER.md)), a pool ([SANDBOX-POOL.md](../daemon/SANDBOX-POOL.md))
   and a classpath fingerprint that decides what went stale. The cost that dominates every
   JVM-side screenshot tool — fork a JVM, boot a sandbox, throw it away — is paid once here.
2. **Structured observation, not just pixels.** `compose/semantics`, `layout/inspector`,
   `a11y/atf` + `a11y/overlay`, `compose/recomposition`, `compose/theme`, `resources/*`,
   `text/strings`, `fonts/used`, `render/trace` ([DATA-PRODUCTS.md](../daemon/DATA-PRODUCTS.md)).
   A render answers questions in JSON that a PNG can only answer by being looked at.
3. **Input and state.** `interactive/*` holds a composition alive across pointer, key, rotary,
   UiAutomator-selector, navigation, lifecycle and state-restore events
   ([INTERACTIVE-ANDROID.md](../daemon/INTERACTIVE-ANDROID.md)), `stream/*` pushes frames
   ([STREAMING.md](../daemon/STREAMING.md)), `recording/*` scripts an interaction and can emit the
   equivalent Compose UI test.

Everything below is a consequence of one of those three being pointed somewhere new. Where a use
case needs something we do not have, it says so.

## 2. Robolectric-dependent workloads that could be better

Robolectric's cost model in the ecosystem is: one forked JVM per Gradle test worker, one sandbox
boot per fork, discarded at the end. Warm reuse is exactly what we built and nobody else has.

### 2a. JVM screenshot-test suites (the biggest one)

[Roborazzi][roborazzi] runs on Robolectric; [Paparazzi][paparazzi] runs on layoutlib and inherits
Studio's rendering limitations; Google's [Compose Preview Screenshot Testing][cpst] needs AGP 9 for
full IDE integration; [ComposablePreviewScanner][scanner] is the glue all three use to turn
`@Preview`s into test cases. Every one of them discovers previews and renders them — which is our
entire pipeline, minus the warm host.

The use case is not "another screenshot library". It is **a render executor those libraries could
delegate to**: `verify`/`record` against a pool of warm sandboxes instead of forked test JVMs, with
the same golden files and the same Gradle task names. The seams already exist —
[EMBEDDING.md](EMBEDDING.md) argues where the daemon's boundary is, and the `harness` module drives
real daemons over JSON-RPC. What is missing is a compatibility shim and, more importantly,
somebody else's suite to measure against. The honest version of this pitch needs a number from a
real multi-module app, not from our render workloads;
[ROBOLECTRIC-UPSTREAM-FEEDBACK.md § Applicability](../daemon/ROBOLECTRIC-UPSTREAM-FEEDBACK.md#applicability-beyond-the-daemon)
is explicit that our measurements do not transfer unexamined.

### 2b. Previews that do not need Robolectric at all

A large fraction of design-system previews touch no Android framework API. They pay Robolectric's
boot anyway because the module is an Android module. We can answer "does this preview actually need
the Android sandbox?" cheaply and *mechanically*: render it on both hosts and diff the
`compose/semantics` trees (`diff_semantics` already does the comparison). A per-preview
**routing advice** product — desktop-safe / Android-required / diverges — turns into real CI time
for anyone who acts on it, and it is a data product, not a new subsystem.

### 2c. Robolectric embedded outside a test runner

R03/R04/R05/R07 in the upstream-feedback doc are all the same complaint: Robolectric assumes JUnit
owns the lifecycle. Anything that is not a test — a design-system linter, a docs generator, a CI
report builder, an agent tool — fights that assumption. We already fought it. The reusable product
is the **host contract** (`RenderHost`, sandbox lifecycle, activity-free capture) offered as a
library for other non-test embedders, which is also the strongest form the upstream feedback can
take: an existing consumer instead of a feature request.

### 2d. Accessibility checks that are currently test-shaped

Espresso's `AccessibilityChecks` and ATF-in-Robolectric require someone to write and maintain the
test. `a11y/atf` plus the overlay PNG gives the same findings for every discovered preview with
zero test authoring, and `a11y/hierarchy` gives the tree to explain them. For a design system this
is a per-release report rather than a test suite; for an app it is a PR comment.

[roborazzi]: https://github.com/takahirom/roborazzi
[paparazzi]: https://github.com/cashapp/paparazzi
[cpst]: https://developer.android.com/studio/preview/compose-screenshot-testing
[scanner]: https://github.com/sergio-sastre/ComposablePreviewScanner

## 3. Emulator / device workloads the daemon can take

Ordered by how cleanly the work actually leaves the device.

| Workload | Today | With the daemon | Catch |
|---|---|---|---|
| Store listing + marketing screenshots | fastlane screengrab on a device farm, per locale | `render_matrix` over device × locale × uiMode × fontScale, device frames from compose-ai-tools' `DEVICE_FRAMES.md` | Needs the screen reachable as a preview with seeded state |
| Localization / RTL / font-scale QA sweeps | manual or device grid | same matrix, plus `text/strings` and `fonts/used` to catch missing resources before pixels | Pseudolocale fidelity is Robolectric's, not the platform's |
| Compose UI tests that only assert semantics | instrumented, on device | `record_preview` events + `diff_semantics`; `emitTest: true` already generates the test back | Anything touching real system UI, IPC or permissions dialogs stays on device |
| Accessibility audits | Accessibility Scanner on device | `a11y/atf`, per preview, in CI | ATF is a subset of a human audit either way |
| Wear tiles, Glance widgets | emulator + a widget host | already rendered headlessly (`PreviewKind.TILE`, the Glance composer path in [RENDERER_COMPATIBILITY.md](../RENDERER_COMPATIBILITY.md)) | Host chrome is reproduced, not real |
| Design review of an in-progress screen | build, install, screenshot, paste | live session, or a recording with real input | State seeding is the whole problem (§ 6) |

**Where it does not go.** Performance and jank (macrobenchmark), real GPU behaviour and AGSL, media
and camera, WebView, sensors, install/upgrade flows, anything whose bug *is* the device. Claiming
otherwise is how a tool like this loses trust; every row above is a workload whose content is a
function of state and theme, not of hardware.

## 4. What other ecosystems do that we do not

### Emerge Tools' Snapshots — the model worth copying

[Emerge][emerge] generates snapshots from the `#Preview`s and `PreviewProvider`s a team already
writes, with **zero snapshot-test code**, treats the PR's base commit as the golden set, and
promotes the merge to golden automatically. The Android half of that product exists here in
pieces — discovery, history, `history_diff`, the visual-diff bot, the image-upload lane on the
public server. What is missing is the *baseline-by-base-commit* rule and the hosted receive
endpoint, which is exactly the gap compose-ai-tools' `HOSTED_SERVICE_PLAN.md` already identifies
(Argos economics: the render is paid for by the consumer's CI; the service stores and diffs).
That plan and this use case are the same product; nothing in the daemon blocks it.

### Xcode 26's `#Playground` macro

Apple generalised previews from "a view" to "any expression": run a snippet against the app's real
code and see the value in the canvas. Our analogue is unusually close to hand — the daemon already
has in-process Kotlin compilation (the Build Tools API path in `:daemon:core`'s `bta/`), and the
server already has a jailed playground. A `@Playground`-shaped entry point, evaluated in a warm
sandbox with the module's real classpath, is a **scratchpad with the Android framework attached** —
useful for the formatter, the parser, the repository layer, not just the UI. This one is genuinely
novel on Android and it reuses two things that already exist.

### The SwiftUI instrument in Instruments 26

Its trick is causality: not "this view updated 40 times" but "this data change caused those
updates". `compose/recomposition` gives the counts. Attributing them to the state write that caused
them is the next honest step, and it is the difference between a heat map people admire and a heat
map people act on.

### Chromatic's TurboSnap

Chromatic renders only the stories the changed files can reach, by walking the bundler's dependency
graph. [DESIGN.md](../daemon/DESIGN.md) calls that "Tier-3 dependency-graph reachability" and lists
it as a v1 non-goal; v1 ships the conservative "module changed = everything stale". For an editor
loop the conservative rule is fine. For CI screenshot runs on a large app it is the single biggest
lever there is, and the framing ("this is TurboSnap, for Compose") is worth more than the
implementation note it is currently filed as.

### Storybook's play functions, args and controls

Interaction tests that live beside the story, and knobs the reader can turn. We have both halves
(knobs, `recording/*`), and the serve host already exposes a Storybook-compatible crawl surface so
Percy/Chromatic/Applitools can consume it. The missing piece is the *authored* interaction — a
recording checked in next to the preview, replayed in CI and playable in the catalog.

### On-device preview browsers (Playbook-iOS, Showkase)

A debug drawer in the real app that browses the same preview inventory. Worth noting mainly because
it is the one direction where the device wins, and because the catalog format already exists.

[emerge]: https://docs.emergetools.com/docs/snapshot-testing

## 5. Wear widgets on a desktop host — yes, and it is the general case

The question ("could we surface Wear widgets on a desktop by sending to a host there? but that
could just be a remote compose host") answers itself correctly, and the second half is the
better design. There are two transports and they are not competitors:

- **Pixels.** Render in the daemon, stream frames to whatever is looking (`stream/*`). Works for
  every surface, needs no Compose on the client, and is what the live seats meter today. The cost
  is a frame per interaction and a server-side seat per viewer.
- **A document.** Emit a Remote Compose document and let a player draw it. The payload is tiny, the
  animation and interaction run at the client's frame rate, one server render serves every viewer,
  and for Wear widgets it is *the shipping format*, not an approximation. The server already ships
  an `rc-player`, and the UI builder already has a `remote-m3` catalog whose scaffolds are the two
  stable Wear widget host sizes ([UI_BUILDER_REMOTE_COMPOSE.md][rc]).

So the use case is not "Wear widgets on desktop". It is **one document, many hosts** — JVM,
Android, Wasm, iOS all play the same bytes, and "Wear widget rendered on a laptop" is the first
instance. What the daemon owes that design is a **capability verdict per preview**: does this
composable lower to Remote Compose vocabulary, and if not, which node forced the fallback? Without
that, a producer discovers the subset by trial. With it, the document lane can be offered
automatically and fall back to pixels per node, which is the only way a mixed catalog works.

[rc]: https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_REMOTE_COMPOSE.md

## 6. Static Android UI on the web that could be live

The pattern to look for: a web page showing a **picture of a composable**, where the picture went
stale the moment someone changed the code.

1. **Our own catalogs.** `compose-preview browse` builds a CMP/Wasm app where it can, and falls
   back to baked PNGs plus source for JVM/Android-only components. That fallback is the single
   clearest instance of the problem, in our own product: the Android-only half of a design system
   is the static half. A daemon-backed live session closes it (and the Remote Compose lane in § 5
   closes it without a per-viewer seat).
2. **Design-system and component documentation.** Every M3-style component page, every Zeroheight
   or Backstage design-system site, every internal "here are our components" page is a grid of
   screenshots. `m3-catalog` already publishes the inventory, the images, the vectors and the
   wireframes; the same catalog with a live tier is a documentation site that cannot go stale.
3. **API docs.** Dokka pages for a UI library carry either no image or a hand-pasted one. A
   `@Preview` referenced from KDoc and rendered at docs-build time is mechanical; making it live is
   the same embed as (2).
4. **Tutorials and blog posts.** Kotlin Playground made Kotlin snippets runnable inline; there is
   no equivalent for an Android Compose snippet. The server's jailed playground plus a daemon is
   that, and it is the most visible possible demo of the whole stack.
5. **Issue trackers and PR bodies.** A bug report that carries a reproducible preview id plus its
   `compose/semantics` tree is worth several screenshots. GitHub will not run scripts, so the
   ceiling there is the recorded APNG we already produce — but a link out to a live session is
   fine, and that link is a first-class artefact.
6. **Release notes and changelogs.** "What changed visually in this release" is a `history_diff`
   over two tags, and nobody publishes it because producing it by hand is tedious.

The unifying deliverable across all six is small: **an embeddable preview element** — one
`<script>` plus `<compose-preview src="…">` — with three fidelity tiers behind one attribute
(baked PNG → Remote Compose document → live daemon session), degrading automatically when the
budget or the vocabulary runs out. Every tier already exists on the serve host; what does not exist
is the one-line embed that a third-party page can paste.

## 7. Shortlist

Ranked by (leverage × how much already exists) ÷ effort:

1. **The embeddable preview element** (§ 6). All three tiers ship; the embed does not.
2. **Remote Compose capability verdict per preview** (§ 5). Unblocks the document lane generally,
   not just for Wear. A data product, not a subsystem.
3. **Tier-3 reachability, framed as TurboSnap** (§ 4). Biggest CI lever for large apps; already
   scoped as a v1 non-goal, so the design work is half done.
4. **Baseline-by-base-commit + a receive endpoint** (§ 4). Emerge's model, on top of the hosted
   plan that already exists.
5. **Screenshot-suite executor shim** (§ 2a). Highest raw payoff, but gated on measuring somebody
   else's real suite first.
6. **Desktop-safe routing advice** (§ 2b) and **recomposition causality** (§ 4). Cheap, and each
   makes an existing data product act on something.

The pattern across the top four: the daemon's scarce capability is not rendering. It is *rendering
warm, repeatedly, with an answer attached*. The use cases that pay are the ones where something
downstream was going to go stale, and now cannot.
