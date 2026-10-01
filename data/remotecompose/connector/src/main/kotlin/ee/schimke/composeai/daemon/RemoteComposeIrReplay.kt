package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayers
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider

/**
 * Replays a Remote Compose preview from a bundle's captured IR (schema v5): the serialized
 * `RemoteDocument` bytes ([IrSidecarChannel.FORMAT_REMOTECOMPOSE], `ir/<id>.rc`) that
 * [RemoteOverridablePreview]'s capture path emitted, handed to the same player backend the live
 * path draws with — so a bundle renders with **no** reference to the `@RemoteComposable` body that
 * produced it (its class was dropped at pack time).
 *
 * The daemon reaches this reflectively (it can't compile against the alpha player), so [Replay] is
 * an instance method with a fixed `(ByteArray)` signature and the class has a no-arg constructor —
 * matching what `RenderEngine` resolves via `getDeclaredComposableMethod`, the same shape as
 * `PreviewWrapperProvider.Wrap`. Seeded `renderNow.overrides.remoteCompose` named values are
 * applied by the backend, so replay honours overrides exactly like the live path (a no-op when none
 * are seeded).
 *
 * This file references no player library: which one draws, and whether it can run on the consumer's
 * classpath, is [RemoteComposePlayers]' to answer.
 */
class RemoteComposeIrReplay {
  @Composable
  fun Replay(bytes: ByteArray) {
    val document = remember(bytes) { RemoteComposeDocumentSource(bytes) }
    // Which player draws is read from the controller rather than passed in: the daemon resolves
    // this composable reflectively against a fixed `(ByteArray)` signature (see the class doc), so
    // there is no parameter to thread a choice through. A per-render request is what the caller is
    // asking to see, so it wins — by id (`playerId`, any registered player) before the built-in
    // enum (`?rcPlayer=java` → RemoteComposePlayerKind.VIEW). Nothing asked falls back to the
    // build-wide RemoteComposePlayerSelection, whose own default is the embedded player, matching
    // what a capture bakes through and what the viewer opens on.
    RemoteComposePlayers.resolve(
        playerId = RemoteComposeController.playerId.value,
        kind = RemoteComposeController.player.value,
        default = RemoteComposePlayerSelection.configured,
      )
      .Play(
        document = document,
        namedValues = RemoteComposeController.namedValues.value,
        modifier = Modifier,
      )
  }
}

/** Registers [RemoteComposeIrReplay] as the replay composable for `remotecompose` IR. */
class RemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = IrSidecarChannel.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = RemoteComposeIrReplay::class.java
}
