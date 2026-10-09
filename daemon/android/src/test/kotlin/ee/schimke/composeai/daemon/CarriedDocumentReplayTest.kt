package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Remote Compose document carried on the request (`overrides.remoteCompose.documentBase64`)
 * renders through the IR replay connector with **no** bundle IR and no preview class — the shape
 * the preview server's shared-document lanes send, where the bytes came from an upload and the
 * preview id only picks a sandbox and a frame.
 *
 * The spec names a class that exists on no classloader and the store has no `ir/` entry for it, so
 * the only way to a frame is the carried document reaching [FakeRemoteComposeIrReplay] (registered
 * through the same SPI as in [AndroidInteractiveIrReplayTest]), which paints solid green.
 */
class CarriedDocumentReplayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun carriedDocumentReplaysWithoutBundleIrOrPreviewClass() {
    val outputDir = tempFolder.newFolder("carried-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val remoteCompose =
      RemoteComposeOverride.Builder()
        .also {
          it.playerId = "androidx-view"
          it.documentBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        }
        .build()
    val spec =
      RenderSpec(
        className = "com.example.absent.UploadedDocumentKt",
        functionName = "Uploaded",
        widthPx = 96,
        heightPx = 96,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "carried-document",
        overrides = PreviewOverrides(remoteCompose = remoteCompose),
      )

    val host = RobolectricHost()
    host.start()
    try {
      val result =
        host.submit(RenderRequest.Render(target = RenderTarget.Spec(spec)), timeoutMs = 120_000)
      val png = result.artifact.pathOrNull()
      assertNotNull("carried replay must produce a PNG", png)
      val greenPct = greenPct(File(png!!))
      assertTrue(
        "expected the frame to be ≥95% the replay composable's green " +
          "(got ${"%.2f".format(greenPct * 100)}%)",
        greenPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  private fun greenPct(png: File): Double {
    val img = ImageIO.read(png) ?: error("could not decode $png")
    var hits = 0
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        if (
          kotlin.math.abs(((rgb shr 16) and 0xFF) - 0x2E) <= 8 &&
            kotlin.math.abs(((rgb shr 8) and 0xFF) - 0x7D) <= 8 &&
            kotlin.math.abs((rgb and 0xFF) - 0x32) <= 8
        ) {
          hits++
        }
      }
    }
    return hits.toDouble() / (img.width * img.height)
  }
}
