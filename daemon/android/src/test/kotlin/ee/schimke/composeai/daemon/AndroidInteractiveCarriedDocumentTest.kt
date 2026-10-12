package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.UiMode
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Remote Compose document **carried** on a live session's overrides (`stream/start`'s
 * `overrides.remoteCompose.documentBase64`) plays in the held composition in place of the preview —
 * the live twin of [CarriedDocumentReplayTest]'s one-shot render.
 *
 * Before this, the held-rule loop chose its content purely from the preview id (the bundle's IR,
 * else the preview class) and ignored the carried bytes, so the preview server could only fake a
 * live lane for a document-only preview with repeated one-shot renders, which could not take input.
 * Covered here, on one host because booting its two sandboxes is the expensive part:
 * - the **player-only daemon** shape — the preview id names a class that exists nowhere;
 * - the **borrowed catalog daemon** shape — the preview id names a real preview ([RedSquare]) that
 *   must not be drawn, with a no-document control proving the id alone does draw it;
 * - **input** — a tap dispatched into the session reaches the document and changes its state;
 * - **theme** — `uiMode` reaches the document's host configuration;
 * - an id the manifest does not know at all, framed by the overrides' own size;
 * - a document that will not decode, refused without wedging the slot.
 *
 * The replay composable is [FakeRemoteComposeIrReplay], registered through the same SPI the real
 * connector uses (see [AndroidInteractiveIrReplayTest]); it reads the carried bytes to decide what
 * to paint, so a frame proves *which* document reached the session.
 */
class AndroidInteractiveCarriedDocumentTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun resetStore() {
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun carriedDocumentPlaysInTheLiveSession() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("carried-live").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolve)
    assertTrue(
      "a host that can hold sessions advertises the carried-document live lane",
      RobolectricHost.CARRIED_REMOTE_COMPOSE_LIVE_CAPABILITY in host.supportedOverrides,
    )
    host.start()
    try {
      // Player-only daemon: the preview's class is absent, so only the carried document can draw.
      host.held(PLAYER_PREVIEW_ID, carried(GREEN_DOCUMENT)) { frame ->
        assertMostly("player-only daemon plays the carried document", frame, REPLAY_GREEN)
      }

      // Borrowed catalog daemon: the id names a real red preview. Control first, so the green
      // below cannot be a fallback that would have painted anything green regardless.
      host.held(CATALOG_PREVIEW_ID, overrides = null) { frame ->
        assertMostly("the catalog preview alone draws itself", frame, CATALOG_RED)
      }
      host.held(CATALOG_PREVIEW_ID, carried(GREEN_DOCUMENT)) { frame ->
        assertMostly("a borrowed catalog daemon plays the carried document", frame, REPLAY_GREEN)
      }

      // Input reaches the document: a tap flips its state, and the state holds across frames.
      host.held(PLAYER_PREVIEW_ID, carried(FakeReplayDocuments.TAP)) { before ->
        assertMostly("the tap document starts red", before, TAP_RED)
        dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = WIDTH_PX / 2,
            pixelY = HEIGHT_PX / 2,
          )
        )
        assertMostly("a tap reaches the carried document", frame(), TAP_BLUE)
        assertMostly("the tapped state survives the next frame", frame(), TAP_BLUE)
      }

      // Theme: the caller's uiMode is the document's host configuration, both ways.
      host.held(PLAYER_PREVIEW_ID, carried(FakeReplayDocuments.THEMED, uiMode = UiMode.DARK)) {
        assertMostly("dark uiMode reaches the carried document", it, THEMED_DARK)
      }
      host.held(PLAYER_PREVIEW_ID, carried(FakeReplayDocuments.THEMED, uiMode = UiMode.LIGHT)) {
        assertMostly("light uiMode reaches the carried document", it, THEMED_LIGHT)
      }

      // An id the manifest has never heard of still holds a session for carried bytes, framed by
      // the overrides' size.
      host.held(
        "com.example.unknown.NotInTheManifestKt.Nothing",
        carried(GREEN_DOCUMENT, widthPx = 64, heightPx = 48),
      ) { frame ->
        assertEquals("framed by the override width", 64, frame.width)
        assertEquals("framed by the override height", 48, frame.height)
        assertMostly("an unknown id plays the carried document", frame, REPLAY_GREEN)
      }

      // A document that will not decode is refused up front, and the slot stays usable.
      val malformed =
        PreviewOverrides(
          remoteCompose =
            RemoteComposeOverride.Builder().also { it.documentBase64 = "not base64 !!" }.build()
        )
      try {
        host.acquireInteractiveSession(PLAYER_PREVIEW_ID, LOADER, overrides = malformed).close()
        fail("a malformed carried document must not hold a session")
      } catch (e: IllegalArgumentException) {
        // Base64's own complaint — the caller sees why its bytes were refused.
      }
      host.held(PLAYER_PREVIEW_ID, carried(GREEN_DOCUMENT)) { frame ->
        assertMostly("the slot survives a refused document", frame, REPLAY_GREEN)
      }

      // Recording is a held lane too: an id the manifest does not know records the carried bytes.
      val recording =
        host.acquireRecordingSession(
          previewId = "com.example.unknown.NotInTheManifestKt.Recorded",
          recordingId = "carried-recording",
          classLoader = LOADER,
          fps = 1,
          scale = 1.0f,
          overrides = carried(GREEN_DOCUMENT, widthPx = 64, heightPx = 48),
          live = false,
        )
      try {
        val result = recording.stop()
        assertEquals("framed by the override width", 64, result.frameWidthPx)
        assertEquals("framed by the override height", 48, result.frameHeightPx)
        assertMostly(
          "a recording plays the carried document",
          decode(java.io.File(result.framesDir, "frame-00000.png").absolutePath),
          REPLAY_GREEN,
        )
      } finally {
        recording.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /** The handle a [held] block drives: the first frame is its argument, more on demand. */
  private class HeldSession(private val session: InteractiveSession) {
    fun frame(): BufferedImage =
      decode(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)

    fun dispatch(input: InteractiveInputParams) = session.dispatch(input)
  }

  private fun RobolectricHost.held(
    previewId: String,
    overrides: PreviewOverrides?,
    block: HeldSession.(BufferedImage) -> Unit,
  ) {
    val session = acquireInteractiveSession(previewId, LOADER, overrides = overrides)
    try {
      val held = HeldSession(session)
      held.block(held.frame())
    } finally {
      session.close()
    }
  }

  private fun carried(
    document: ByteArray,
    uiMode: UiMode? = null,
    widthPx: Int? = null,
    heightPx: Int? = null,
  ): PreviewOverrides =
    PreviewOverrides(
      widthPx = widthPx,
      heightPx = heightPx,
      uiMode = uiMode,
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.playerId = "androidx-view"
            it.documentBase64 = Base64.getEncoder().encodeToString(document)
          }
          .build(),
    )

  private fun resolve(previewId: String): RenderSpec? =
    when (previewId) {
      // The preview server's player-only daemon: a manifest entry whose class exists nowhere.
      PLAYER_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.serve.RcPlayerDaemonKt",
          functionName = "Player",
          widthPx = WIDTH_PX,
          heightPx = HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "carried-live-player",
        )
      CATALOG_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RedSquare",
          widthPx = WIDTH_PX,
          heightPx = HEIGHT_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "carried-live-catalog",
        )
      else -> null
    }

  private companion object {
    const val PLAYER_PREVIEW_ID: String = "ee.schimke.composeai.serve.RcPlayerDaemonKt.Player"

    const val CATALOG_PREVIEW_ID: String = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.Red"

    const val WIDTH_PX: Int = 96

    const val HEIGHT_PX: Int = 96

    val GREEN_DOCUMENT: ByteArray = byteArrayOf(1, 2, 3, 4)

    const val REPLAY_GREEN: Int = 0x2E7D32

    /** `RedFixturePreviews.RedSquare`'s fill. */
    const val CATALOG_RED: Int = 0xEF5350

    const val TAP_RED: Int = 0xC62828

    const val TAP_BLUE: Int = 0x1565C0

    const val THEMED_DARK: Int = 0x000080

    const val THEMED_LIGHT: Int = 0xFFFF00

    val LOADER: ClassLoader = AndroidInteractiveCarriedDocumentTest::class.java.classLoader!!

    fun decode(path: String): BufferedImage =
      ImageIO.read(File(path)) ?: error("could not decode $path")

    /** At least 95% of [img] within a small tolerance of [rgb]. */
    fun assertMostly(message: String, img: BufferedImage, rgb: Int) {
      var hits = 0
      for (y in 0 until img.height) {
        for (x in 0 until img.width) {
          val p = img.getRGB(x, y)
          if (
            abs(((p shr 16) and 0xFF) - ((rgb shr 16) and 0xFF)) <= 8 &&
              abs(((p shr 8) and 0xFF) - ((rgb shr 8) and 0xFF)) <= 8 &&
              abs((p and 0xFF) - (rgb and 0xFF)) <= 8
          ) {
            hits++
          }
        }
      }
      val pct = hits.toDouble() / (img.width * img.height)
      assertTrue(
        "$message: expected ≥95% #${"%06X".format(rgb)}, got ${"%.2f".format(pct * 100)}%",
        pct >= 0.95,
      )
    }
  }
}
