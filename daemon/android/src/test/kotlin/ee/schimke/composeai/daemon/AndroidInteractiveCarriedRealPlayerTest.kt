package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A **real** Remote Compose document carried on a held session's overrides, played by the **real**
 * connector (`RemoteComposeIrReplay` → `RemoteComposePlayers`) in each player a caller can name —
 * where [AndroidInteractiveCarriedDocumentTest] proves the routing with a fake replay composable.
 *
 * The document is the remote-m3 catalog's `Stepper` (`stepper__ideal__default__compact`, copied
 * from rc-players' `rc-player/compose` jvmTest fixtures): a level rail between an increase and a
 * decrease button, its value a `rememberMutableRemoteFloat` that each button changes with a
 * `ValueFloatExpressionChange`. So a tap that reaches the player moves the rail, and the change has
 * to outlive the document's next per-frame re-declaration of the value to still show a frame later
 * — the regression rc-players fixed for the CMP player (compose-preview-server#1221).
 *
 * For each of `androidx-view`, `androidx-embedded` and `cmp-android`, on a player-only preview id
 * whose class exists nowhere:
 * - the carried document is drawn (the frame is not a blank background);
 * - a tap on the increase button changes the frame;
 * - for the two players that step this document ([STEPPING_PLAYERS]), the changed state is still
 *   there on a later frame.
 *
 * One host for all three players, because booting its sandboxes is the expensive part.
 */
class AndroidInteractiveCarriedRealPlayerTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun resetStore() {
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun realPlayersPlayACarriedDocumentAndTakeATap() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("carried-real-player").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolve)
    host.start()
    try {
      for (playerId in listOf("androidx-view") + STEPPING_PLAYERS) {
        val session =
          host.acquireInteractiveSession(PLAYER_PREVIEW_ID, LOADER, overrides = carried(playerId))
        try {
          val atRest = frame(session)
          assertDrawn("$playerId draws the carried document", atRest)
          session.dispatch(
            InteractiveInputParams(
              frameStreamId = "irrelevant-on-host-side",
              kind = InteractiveInputKind.CLICK,
              pixelX = SIZE_PX / 2,
              // The increase button: the top eighth of the stepper, where rc-players' own click
              // test
              // presses it.
              pixelY = (SIZE_PX * 0.12f).toInt(),
            )
          )
          val tapped = frame(session)
          assertDiffers("$playerId: a tap reaches the carried document", atRest, tapped)
          if (playerId in STEPPING_PLAYERS) {
            // The value is re-declared every frame; a change that did not stick is gone by now.
            val later = frame(session)
            assertDiffers("$playerId: the tapped state survives later frames", atRest, later)
          }
        } finally {
          session.close()
        }
      }
    } finally {
      host.shutdown()
    }
  }

  private fun frame(session: InteractiveSession): BufferedImage {
    // Two renders, keeping the second: give a press, its ripple and any value animation a frame to
    // land before comparing.
    session.render(requestId = RenderHost.nextRequestId())
    return decode(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)
  }

  private fun carried(playerId: String): PreviewOverrides =
    PreviewOverrides(
      widthPx = SIZE_PX,
      heightPx = SIZE_PX,
      density = 1.0f,
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.playerId = playerId
            it.documentBase64 = Base64.getEncoder().encodeToString(STEPPER)
          }
          .build(),
    )

  private fun resolve(previewId: String): RenderSpec? =
    when (previewId) {
      PLAYER_PREVIEW_ID ->
        RenderSpec(
          className = "ee.schimke.composeai.serve.RcPlayerDaemonKt",
          functionName = "Player",
          widthPx = SIZE_PX,
          heightPx = SIZE_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "carried-real-player",
        )
      else -> null
    }

  private companion object {
    const val PLAYER_PREVIEW_ID: String = "ee.schimke.composeai.serve.RcPlayerDaemonKt.Player"

    const val SIZE_PX: Int = 400

    /**
     * The players whose tap moves the stepper's value and keeps it moved. `androidx-view`
     * (`remote-player-view`'s `RemoteComposePlayer`, alpha19) is not one of them under Robolectric:
     * the tap lands — the button's press indication is drawn on the next frame, which is what the
     * shared "a tap reaches the carried document" assertion sees — but the rail does not move, and
     * the frame after is the resting one again. It also draws the stepper without its filled
     * buttons and rail. Both are that player's own behaviour on this document, observed with the
     * same held session that steps the other two, so they are recorded here rather than asserted.
     */
    val STEPPING_PLAYERS: List<String> = listOf("androidx-embedded", "cmp-android")

    val LOADER: ClassLoader = AndroidInteractiveCarriedRealPlayerTest::class.java.classLoader!!

    val STEPPER: ByteArray =
      checkNotNull(
          AndroidInteractiveCarriedRealPlayerTest::class
            .java
            .getResourceAsStream("/rc-fixtures/Stepper-ideal-default-compact.rc")
        ) {
          "missing Stepper fixture"
        }
        .use { it.readBytes() }

    fun decode(path: String): BufferedImage =
      ImageIO.read(File(path)) ?: error("could not decode $path")

    /** At least 2% of the frame differs from its top-left (background) pixel. */
    fun assertDrawn(message: String, img: BufferedImage) {
      val background = img.getRGB(0, 0)
      var ink = 0
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (img.getRGB(x, y) != background) ink++
      }
      val pct = ink.toDouble() / (img.width * img.height)
      assertTrue("$message: only ${"%.2f".format(pct * 100)}% of the frame is ink", pct >= 0.02)
    }

    /** At least 0.2% of the pixels differ between [a] and [b]. */
    fun assertDiffers(message: String, a: BufferedImage, b: BufferedImage) {
      assertTrue("$message: frames differ in size", a.width == b.width && a.height == b.height)
      var changed = 0
      for (y in 0 until a.height) for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
      }
      val pct = changed.toDouble() / (a.width * a.height)
      assertTrue("$message: only ${"%.3f".format(pct * 100)}% of pixels changed", pct >= 0.002)
    }
  }
}
