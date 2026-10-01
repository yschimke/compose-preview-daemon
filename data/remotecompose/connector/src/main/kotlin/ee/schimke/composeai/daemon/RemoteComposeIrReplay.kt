@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider
import ee.schimke.composeai.rcembedded.player.RcPlayer
import ee.schimke.composeai.rcembedded.player.RcPlayerState

/**
 * Replays a Remote Compose preview from a bundle's captured IR (schema v5): the serialized
 * `RemoteDocument` bytes ([IrSidecarChannel.FORMAT_REMOTECOMPOSE], `ir/<id>.rc`) that
 * [RemoteOverridablePreview]'s capture path emitted. Reconstructs the document with
 * `RemoteDocument(bytes)` and hands it to the same `RemoteDocumentPlayer` the live path uses — so a
 * bundle renders with **no** reference to the `@RemoteComposable` body that produced it (its class
 * was dropped at pack time).
 *
 * The daemon reaches this reflectively (it can't compile against the alpha player), so [Replay] is
 * an instance method with a fixed `(ByteArray)` signature and the class has a no-arg constructor —
 * matching what `RenderEngine` resolves via `getDeclaredComposableMethod`, the same shape as
 * `PreviewWrapperProvider.Wrap`. Seeded `renderNow.overrides.remoteCompose` named values are
 * applied through the player's `StateUpdater` via [applyConnectorOverrides], so replay honours
 * overrides exactly like the live path (a no-op when none are seeded).
 */
class RemoteComposeIrReplay {
  @Composable
  fun Replay(bytes: ByteArray) {
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val remoteDocument = remember(bytes) { RemoteDocument(bytes) }
    val seededOverrides = RemoteComposeController.namedValues.value

    // Which player draws is read from the controller rather than passed in: the daemon resolves
    // this
    // composable reflectively against a fixed `(ByteArray)` signature (see the class doc), so there
    // is no parameter to thread a choice through. A per-render request (`?rcPlayer=java` →
    // RemoteComposePlayerKind.VIEW) is what the caller is asking to see, so it wins; null — nothing
    // asked — falls back to the build-wide RemoteComposePlayerSelection, whose own default is the
    // embedded player, matching what a capture bakes through and what the viewer opens on.
    val requested = RemoteComposeController.player.value ?: RemoteComposePlayerSelection.configured
    val embedded = requested != RemoteComposePlayerKind.VIEW

    if (embedded) {
      EmbeddedRemoteDocumentPlayer(document = remoteDocument, seededOverrides = seededOverrides)
    } else {
      RemoteDocumentPlayer(
        document = remoteDocument.document,
        documentWidth = displayMetrics.widthPixels,
        documentHeight = displayMetrics.heightPixels,
        init = { player ->
          applyConnectorOverrides(player.stateUpdater, seededOverrides)
          installGoogleFontTypefaceResolver(player)
        },
      )
    }
  }
}

/**
 * Draws [document] through the vendored embedded player, seeding [seededOverrides] first. Both
 * embedded call sites in this module go through here, and call [RcPlayer] directly: a consumer
 * whose embedded player is missing or reshaped fails loudly at render time rather than falling
 * back.
 *
 * Every named-value type is seeded, through [RcPlayerState]'s typed states, with the same mapping
 * [applyConnectorOverrides] gives the view player's `StateUpdater` — see [reseed] — so a render
 * that carries knobs means the same thing on either player.
 *
 * One [RcPlayerState] per document — the player installs its runtime state onto the document, so a
 * new override set re-seeds the existing state rather than building a second one. A value dropped
 * from the seed set is restored to its authored default.
 */
@Composable
internal fun EmbeddedRemoteDocumentPlayer(
  document: RemoteDocument,
  seededOverrides: Map<String, RemoteNamedValue>,
  modifier: Modifier = Modifier,
) {
  val state = remember(document) { RcPlayerState(document.document) }
  val target = remember(state) { RcPlayerStateSeedTarget(state) }
  val seeded = remember(state) { mutableSetOf<String>() }
  // Seeded during composition, not in a SideEffect: the player reads these values on its first
  // frame, and the typed-state writes are snapshot-backed. Returns the seeded names so the
  // `remember` is not a Unit-returning mutation.
  remember(target, seededOverrides) {
    val applied = target.reseed(previous = seeded, overrides = seededOverrides)
    seeded.apply {
      clear()
      addAll(applied)
    }
  }
  RcPlayer(state = state, modifier = modifier)
}

/** Where [reseed] writes: the typed setters of an embedded player's named-value state. */
internal interface NamedValueSeedTarget {
  fun setString(name: String, value: String)

  fun setFloat(name: String, value: Float)

  fun setInt(name: String, value: Int)

  fun setBoolean(name: String, value: Boolean)

  fun setColor(name: String, argb: Int)

  /** Restores [name] to its authored default. */
  fun clear(name: String)
}

/**
 * Seeds [overrides] into this target and returns the names it seeded, clearing any name in
 * [previous] that is no longer seeded.
 *
 * The type mapping mirrors [applyConnectorOverrides] one for one — a dp is a float, and a colour
 * goes through the shared [rcColorToArgb] so a six-digit value is opaque — so the two players
 * cannot disagree about what a seed means. An unparseable colour is skipped rather than thrown, and
 * counts as unseeded: a name that held a valid colour last time is restored rather than left stale.
 */
internal fun NamedValueSeedTarget.reseed(
  previous: Set<String>,
  overrides: Map<String, RemoteNamedValue>,
): Set<String> {
  val applied = LinkedHashSet<String>()
  for ((name, value) in overrides) {
    when (value) {
      is RemoteNamedValue.StringValue -> setString(name, value.value)
      is RemoteNamedValue.FloatValue -> setFloat(name, value.value)
      is RemoteNamedValue.IntValue -> setInt(name, value.value)
      is RemoteNamedValue.DpValue -> setFloat(name, value.value)
      is RemoteNamedValue.BooleanValue -> setBoolean(name, value.value)
      is RemoteNamedValue.ColorValue -> {
        val argb = rcColorToArgb(value.argb) ?: continue
        setColor(name, argb)
      }
    }
    applied += name
  }
  (previous - applied).forEach { clear(it) }
  return applied
}

private class RcPlayerStateSeedTarget(private val state: RcPlayerState) : NamedValueSeedTarget {
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

/** Registers [RemoteComposeIrReplay] as the replay composable for `remotecompose` IR. */
class RemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = IrSidecarChannel.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = RemoteComposeIrReplay::class.java
}
