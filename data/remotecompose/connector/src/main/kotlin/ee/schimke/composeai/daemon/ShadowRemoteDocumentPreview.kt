@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import ee.schimke.composeai.daemon.remotecompose.androidx.view.applyConnectorOverrides
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for upstream `androidx.compose.remote.tooling.preview.RemoteDocumentPreview`,
 * so a preview that plays an already-built document receives
 * `renderNow.overrides.remoteCompose.namedValues` (#401).
 *
 * Upstream builds its own `RemoteDocumentPlayer` with no `init` hook, so before this a seed sent to
 * such a preview was dropped and the PNG came back byte-identical. This replacement draws the same
 * way — the same View-backed player, sized to `LocalWindowInfo.containerSize` like upstream — and
 * only adds the seeding [RemoteOverridablePreview]'s view lane does, through the shared
 * [applyConnectorOverrides] mapping. It does not switch players: unlike
 * [ShadowRemoteContentPreview] there is no content to re-capture, and the caller chose this
 * function to see the document as upstream plays it.
 *
 * Only the `RemoteDocument` overload is shadowed. Upstream's `ByteArray` overload wraps the bytes
 * and calls this one, and an instrumented method without an `@Implementation` runs its original
 * body, so both entry points land here.
 *
 * Registered and instrumented by name by the daemon's `SandboxHoldingRunner`, only when
 * [TARGET_CLASS] is on the consumer's classpath — the same arrangement as
 * [ShadowRemoteContentPreview]. `RemoteDocumentPreviewShadowTest` pins the descriptor.
 */
@Implements(className = ShadowRemoteDocumentPreview.TARGET_CLASS, isInAndroidSdk = false)
class ShadowRemoteDocumentPreview {
  companion object {
    /** The upstream file class whose `RemoteDocumentPreview` this shadow replaces. */
    const val TARGET_CLASS: String =
      "androidx.compose.remote.tooling.preview.RemoteDocumentPreviewKt"

    /** Plays [remoteDocument] as upstream does, seeding the connector's named values first. */
    @JvmStatic
    @Implementation
    @Composable
    fun RemoteDocumentPreview(remoteDocument: RemoteDocument, modifier: Modifier = Modifier) {
      val containerSize = LocalWindowInfo.current.containerSize
      val namedValues = RemoteComposeController.namedValues.value
      SideEffect { RemoteComposeController.markNamedValuesConsumed() }
      RemoteDocumentPlayer(
        document = remoteDocument.document,
        documentWidth = containerSize.width,
        documentHeight = containerSize.height,
        modifier = modifier,
        init = { player -> applyConnectorOverrides(player.stateUpdater, namedValues) },
      )
    }
  }
}
