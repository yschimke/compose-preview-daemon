@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx.view

import android.graphics.Picture
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
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
    var player by remember(document) { mutableStateOf<RemoteComposePlayer?>(null) }
    var contentSize by remember(document) { mutableStateOf<IntSize?>(null) }
    // The view sizes itself to the document's declared size — the capture canvas — however small
    // its content, so a wrap-content preview rendered the whole sandbox (#342). The core only lays
    // the document out on the view's first draw, so its content bounds are read on the frame after.
    LaunchedEffect(player) {
      val playing = player ?: return@LaunchedEffect
      repeat(CONTENT_SIZE_FRAMES) {
        withFrameNanos {}
        val core = playing.document?.document ?: return@repeat
        if (playing.width > 0 && playing.height > 0 && !isLaidOut(core)) layOut(playing)
        contentSize = layoutContentSize(core)
        if (contentSize != null) return@LaunchedEffect
      }
    }
    RemoteDocumentPlayer(
      document = remoteDocument.document,
      // `RemoteDocumentPlayer` applies these as dp (`Modifier.size(width.dp, height.dp)`).
      documentWidth = (displayMetrics.widthPixels / displayMetrics.density).toInt(),
      documentHeight = (displayMetrics.heightPixels / displayMetrics.density).toInt(),
      modifier = modifier.wrapToContent { contentSize },
      init = { created ->
        player = created
        applyConnectorOverrides(created.stateUpdater, namedValues)
        installGoogleFontTypefaceResolver(created)
      },
    )
  }
}

/** Frames to wait for the core's first layout before keeping the view's own size. */
private const val CONTENT_SIZE_FRAMES = 8

/**
 * Measures the player at [contentSize] on each axis the caller leaves loose — a wrap-content
 * preview's (`spec.wrapWidth` / `wrapHeight`), whose box measures with a zero minimum — so the
 * render's crop keeps the content rather than the canvas. A tight axis, a sized preview's, is
 * untouched, as is every axis until the content has been laid out once.
 */
private fun Modifier.wrapToContent(contentSize: () -> IntSize?): Modifier =
  layout { measurable, constraints ->
    val content = contentSize()
    val child =
      if (content == null) constraints
      else {
        val width =
          if (constraints.hasFixedWidth) constraints.maxWidth
          else content.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
          if (constraints.hasFixedHeight) constraints.maxHeight
          else content.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        Constraints.fixed(width, height)
      }
    val placeable = measurable.measure(child)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }

private fun isLaidOut(document: CoreDocument): Boolean =
  document.rootLayoutComponent.let { it != null && it.width > 0f && it.height > 0f }

/**
 * Draws [player] once into a discarded [Picture]: the core measures and lays a document out only
 * from the view's `onDraw`, and the content bounds are needed before the render's own draw.
 */
private fun layOut(player: RemoteComposePlayer) {
  val picture = Picture()
  player.draw(picture.beginRecording(player.width, player.height))
  picture.endRecording()
}

/**
 * The bounds the root's children occupy in [document]'s first layout, in pixels: what the embedded
 * player, which lays each component out as a Compose node, measures for a wrap-content preview.
 * Null until the core has laid the document out (on the view's first draw), and for a document with
 * no layout tree, which keeps the size the view gives it.
 *
 * Measured from the leftmost and topmost child rather than the origin, so content the root aligns
 * away from its corner keeps its own size once the root shrinks around it.
 */
private fun layoutContentSize(document: CoreDocument): IntSize? {
  val root = document.rootLayoutComponent ?: return null
  if (root.width <= 0f || root.height <= 0f) return null
  val children = root.list.filterIsInstance<Component>()
  if (children.isEmpty()) return null
  val left = children.minOf { it.x }
  val top = children.minOf { it.y }
  val right = children.maxOf { it.x + it.width }
  val bottom = children.maxOf { it.y + it.height }
  return IntSize(
    kotlin.math.ceil(right - left).toInt().coerceAtLeast(1),
    kotlin.math.ceil(bottom - top).toInt().coerceAtLeast(1),
  )
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
