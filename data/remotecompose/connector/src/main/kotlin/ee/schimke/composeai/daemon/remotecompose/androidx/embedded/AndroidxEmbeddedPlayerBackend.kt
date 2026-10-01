@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx.embedded

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayerBackend
import ee.schimke.composeai.daemon.remotecompose.androidx.toAndroidxDocument
import ee.schimke.composeai.data.remotecompose.NamedValueSeedTarget
import ee.schimke.composeai.data.remotecompose.reseed
import ee.schimke.composeai.rcembedded.player.RcPlayer
import ee.schimke.composeai.rcembedded.player.RcPlayerState

/**
 * The vendored AndroidX embedded player (`third-party-rc-embedded-player`, upstream's
 * `player-compose-embedded`), which interprets the document into Compose layout and draw nodes and
 * labels itself from the document's own root content description.
 *
 * The consumer supplies the player; this calls [RcPlayer] directly, so one that is missing or
 * reshaped is refused by the linkage check rather than drawn with something else.
 */
internal class AndroidxEmbeddedPlayerBackend : RemoteComposePlayerBackend {
  override val id: String = "androidx-embedded"

  override val aliases: Set<String> = setOf("cmp", "cmp-android", "embedded")

  // What every published `.remotecompose.json` already says, and what readers key on.
  override val capturePlayerName: String = "cmp-android"

  override val linkedPackages: List<String> =
    listOf(
      "ee.schimke.composeai.daemon.remotecompose.androidx",
      "ee.schimke.composeai.daemon.remotecompose.androidx.embedded",
    )

  @Composable
  override fun Play(
    document: RemoteComposeDocumentSource,
    namedValues: Map<String, RemoteNamedValue>,
    modifier: Modifier,
  ) {
    val state = remember(document) { RcPlayerState(document.toAndroidxDocument().document) }
    SeededRcPlayer(state = state, namedValues = namedValues, modifier = modifier)
  }
}

/**
 * Draws [state], seeding [namedValues] first.
 *
 * One [RcPlayerState] per document — the player installs its runtime state onto the document, so a
 * new override set re-seeds the existing state rather than building a second one. A value dropped
 * from the seed set is restored to its authored default.
 */
@Composable
private fun SeededRcPlayer(
  state: RcPlayerState,
  namedValues: Map<String, RemoteNamedValue>,
  modifier: Modifier,
) {
  val target = remember(state) { RcPlayerStateSeedTarget(state) }
  val seeded = remember(state) { mutableSetOf<String>() }
  // Seeded during composition, not in a SideEffect: the player reads these values on its first
  // frame, and the typed-state writes are snapshot-backed. Returns the seeded names so the
  // `remember` is not a Unit-returning mutation.
  remember(target, namedValues) {
    val applied = target.reseed(previous = seeded, overrides = namedValues)
    seeded.apply {
      clear()
      addAll(applied)
    }
  }
  RcPlayer(state = state, modifier = modifier)
}

/** The embedded player's typed named-value states, as a [NamedValueSeedTarget]. */
internal class RcPlayerStateSeedTarget(private val state: RcPlayerState) : NamedValueSeedTarget {
  override fun setString(name: String, value: String) {
    state.stringState(name).value = value
  }

  override fun setFloat(name: String, value: Float) {
    state.floatState(name).value = value
  }

  override fun setInt(name: String, value: Int) {
    state.intState(name).value = value
  }

  override fun setBoolean(name: String, value: Boolean) {
    state.booleanState(name).value = value
  }

  override fun setColor(name: String, argb: Int) {
    state.colorState(name).value = Color(argb)
  }

  override fun clear(name: String) = state.clearOverride(name)
}
