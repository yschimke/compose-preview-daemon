package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * Which Remote Compose player a render draws with when nothing more specific says.
 *
 * A Remote Compose preview is **played, not composed**: the capture produces a `RemoteDocument` and
 * a player replays it. The two players are genuinely different renderers of the same document, and
 * the choice is visible in two ways a preview author cares about:
 * * **pixels**, because they rasterise independently; and
 * * **accessibility**, because [RemoteComposePlayerKind.VIEW] plays into a single Android `View`
 *   that the a11y lane can only see as one unlabelled item, while
 *   [RemoteComposePlayerKind.EMBEDDED] — the vendored `RcPlayer` — interprets the document into
 *   Compose layout/draw nodes and labels itself from the document's own root content description.
 *
 * The second is why [DEFAULT] is `EMBEDDED` (issue #5259): under the view player every Remote
 * Compose preview reports the same `SpeakableTextPresentCheck` error against `RemoteComposePlayer`,
 * identical across previews whose content differs completely, and unfixable from the design — a
 * `contentDescription` an author writes lives *inside* the played document while the check looks at
 * the `View` hosting it.
 *
 * ## What outranks what
 *
 * This is the **build-wide default**, the weakest of the three ways a player gets chosen, so a
 * preview or a request that names one keeps it:
 * 1. a per-preview pin — `@PreviewWrapper(RemoteViewPreviewWrapper::class)` /
 *    `RemoteEmbeddedPreviewWrapper`, or an explicit `player =` argument to
 *    [RemoteOverridablePreview]. Named in the source, so it wins outright;
 * 2. a per-render request — `renderNow.overrides.remoteCompose.playerId` (any player
 *    [ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayers] resolves, registered ones
 *    included), then `.player` (the two built-ins), which `serve`'s `?rcPlayer=` chips ride, read
 *    through [RemoteComposeController.playerId] / [RemoteComposeController.player]. Honoured on the
 *    replay lane, where the request is what the caller is asking to see;
 * 3. this: [PROPERTY] on the render / daemon JVM, wired by the Gradle plugin from
 *    `-PcomposePreview.rcPlayer=view`, else [DEFAULT].
 *
 * Read once per JVM ([configured]) rather than per composition, so an unusable value is reported
 * once.
 */
object RemoteComposePlayerSelection {
  /**
   * System property naming the build-wide player.
   *
   * The Gradle plugin resolves `-PcomposePreview.rcPlayer` / `-Dcomposeai.render.rcPlayer` onto
   * every render and daemon JVM (see `composeAiRcPlayer`), because the property is read *here*, in
   * the JVM that composes, not on the Gradle one.
   *
   * `:wear-preview-runtime` reads the same property under its own copy of this name — it cannot
   * depend on this module — so the two spellings are pinned by a test on each side. Change one and
   * the other's test fails.
   */
  const val PROPERTY: String = "composeai.render.rcPlayer"

  /**
   * The player a render draws with when nothing selects one: the embedded Compose player, which is
   * also what a capture bakes through, what an unqualified bundle replay uses, and what `serve`'s
   * viewer opens on.
   */
  val DEFAULT: RemoteComposePlayerKind = RemoteComposePlayerKind.EMBEDDED

  /**
   * The player [raw] names, or null when it names none — blank, unset, or a value neither player
   * answers to.
   *
   * ## The names
   *
   * Every player is named after the implementation that draws, and `cmp-` means the CMP player
   * (`rc-player-compose`) and nothing else. The full table, with the lanes that render outside this
   * JVM, is in the rc-players README; the two this property can select are:
   * * `androidx-embedded` — the vendored AndroidX embedded player
   *   (`third-party-rc-embedded-player`, upstream's `player-compose-embedded`). Also `embedded`,
   *   this module's own spelling.
   * * `androidx-view` — the `AndroidView`-hosted `RemoteComposePlayer` from `remote-player-view`.
   *   Also `java` and `view`, which are on the wire in published links and stay accepted.
   *
   * `cmp-android` is **not** one of them any more. It used to name the embedded player, which is
   * not the CMP player, and it now names the CMP player on Android — a replay-only backend
   * ([ee.schimke.composeai.daemon.remotecompose.rcplayer.CmpAndroidPlayerBackend]) reached through
   * `RemoteComposeOverride.playerId`. A capture always draws through one of the two AndroidX
   * players, so neither `cmp-android` nor the bare `cmp` selects anything here, and a build asking
   * for one hears so rather than silently getting the default. The same goes for the lanes that
   * never render in this JVM: `cmp-jvm`, `cmp-wasm` and `camaelon-js`. Case- and whitespace-
   * insensitive throughout.
   */
  fun fromWire(raw: String?): RemoteComposePlayerKind? =
    when (raw?.trim()?.lowercase()) {
      // Canonical first, historical after — the order is documentation, not behaviour.
      "androidx-embedded",
      "embedded" -> RemoteComposePlayerKind.EMBEDDED
      "androidx-view",
      "java",
      "view" -> RemoteComposePlayerKind.VIEW
      else -> null
    }

  /**
   * The player to draw with given [raw] — [DEFAULT] when it names none.
   *
   * An unrecognised value is reported on stderr rather than silently ignored: it is nearly always a
   * typo in a `-PcomposePreview.rcPlayer=` invocation, or one of the viewer-only lanes above, and a
   * silent fallback would draw the default while the author believes they pinned the other player.
   * It is not fatal — a preview render should not die over a player selection.
   */
  fun resolve(raw: String?): RemoteComposePlayerKind {
    val selected = fromWire(raw)
    if (selected == null && !raw.isNullOrBlank()) {
      System.err.println(
        "compose-preview: -D$PROPERTY=$raw names no render-time Remote Compose player; drawing " +
          "with the default (androidx-embedded). Valid values: androidx-embedded (aka embedded), " +
          "androidx-view (aka java, view). cmp-android, the CMP player, is replay-only."
      )
    }
    return selected ?: DEFAULT
  }

  /** [PROPERTY] as resolved in this JVM. */
  val configured: RemoteComposePlayerKind by lazy { resolve(System.getProperty(PROPERTY)) }
}
