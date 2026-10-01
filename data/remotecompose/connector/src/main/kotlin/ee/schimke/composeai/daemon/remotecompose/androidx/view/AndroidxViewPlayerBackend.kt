@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx.view

import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayerBackend
import ee.schimke.composeai.daemon.remotecompose.androidx.toAndroidxDocument
import ee.schimke.composeai.data.remotecompose.NamedValueSeedTarget
import ee.schimke.composeai.data.remotecompose.reseed

/**
 * The AndroidX View-backed player: `RemoteComposePlayer` from `remote-player-view`, hosted in an
 * `AndroidView` by `remote-player-compose`'s `RemoteDocumentPlayer`. It draws through the framework
 * `Canvas`, which is why a preview whose fidelity depends on it (glyph hinting is the usual one)
 * pins this lane. The a11y lane sees it as one unlabelled `View`.
 *
 * Seeds go through the player's [StateUpdater] in its `init` callback, and `google:` families
 * resolve through [installGoogleFontTypefaceResolver].
 */
internal class AndroidxViewPlayerBackend : RemoteComposePlayerBackend {
  override val id: String = "androidx-view"

  override val aliases: Set<String> = setOf("java", "view")

  override val capturePlayerName: String = "java"

  override val linkedPackages: List<String> =
    listOf(
      "ee.schimke.composeai.daemon.remotecompose.androidx",
      "ee.schimke.composeai.daemon.remotecompose.androidx.view",
    )

  @Composable
  override fun Play(
    document: RemoteComposeDocumentSource,
    namedValues: Map<String, RemoteNamedValue>,
    modifier: Modifier,
  ) {
    val displayMetrics = LocalContext.current.resources.displayMetrics
    val remoteDocument = remember(document) { document.toAndroidxDocument() }
    RemoteDocumentPlayer(
      document = remoteDocument.document,
      documentWidth = displayMetrics.widthPixels,
      documentHeight = displayMetrics.heightPixels,
      modifier = modifier,
      init = { player ->
        applyConnectorOverrides(player.stateUpdater, namedValues)
        installGoogleFontTypefaceResolver(player)
      },
    )
  }
}

/**
 * Pushes every entry of [overrides] through [updater]'s `setUserLocal*` setters, with the shared
 * [reseed] mapping. `StateUpdater` has no boolean setter, so a boolean is the int `1` / `0` a
 * `rememberNamedRemoteInt` bound to the same name reads; it has no remove either, so nothing is
 * cleared — the player is re-created with each new seed set.
 *
 * **A string seed does not currently reach a replayed document.** Colour, float, dp and int seeds
 * all move pixels on the published `remote-m3` catalog (`rc.shaderColor`, `rc.progress`); a string
 * seed (`rc.label`, `rc.text`) comes back byte-identical to the un-overridden render. The
 * divergence is not in this function or its callers — every branch is covered by
 * `ApplyConnectorOverridesTest`, and in the alpha player (1.0.0-alpha16) `setUserLocalString` →
 * `RemoteContext.setNamedStringOverride` → `overrideText` → `RemoteComposeState.overrideData` is
 * structurally identical to the float path that works, down to the same bounds guard and the same
 * `updateListeners` call. Whatever swallows it sits below that, in how the player re-resolves text
 * it has already laid out.
 *
 * Until it lands, the serve layer reports a string seed as un-applied rather than answering `200`
 * with unchanged pixels — see `CatalogLiveRouting.irReplayDroppedOverrideNames`, whose
 * `IrReplayDroppedOverridesTest` is what will fail (deliberately) on the day the player honours it.
 */
internal fun applyConnectorOverrides(
  updater: StateUpdater,
  overrides: Map<String, RemoteNamedValue>,
) {
  StateUpdaterSeedTarget(updater).reseed(overrides = overrides)
}

private class StateUpdaterSeedTarget(private val updater: StateUpdater) : NamedValueSeedTarget {
  override fun setString(name: String, value: String) {
    updater.setUserLocalString(name, value)
  }

  override fun setFloat(name: String, value: Float) {
    updater.setUserLocalFloat(name, value)
  }

  override fun setInt(name: String, value: Int) {
    updater.setUserLocalInt(name, value)
  }

  override fun setBoolean(name: String, value: Boolean) {
    updater.setUserLocalInt(name, if (value) 1 else 0)
  }

  override fun setColor(name: String, argb: Int) {
    updater.setUserLocalColor(name, argb)
  }
}
