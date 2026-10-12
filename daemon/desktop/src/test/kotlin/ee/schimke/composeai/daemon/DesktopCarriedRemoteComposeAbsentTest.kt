package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop daemon **without** the CMP Remote Compose player on its classpath — the default
 * `test` task's runtime, and any host that does not supply `rc-player-compose-jvm`.
 *
 * It must neither advertise the carried-document lanes nor draw anything else in their place: a
 * carried document is refused by name (so `stream/start` refuses rather than streaming the bare
 * preview id), while a render without one is exactly what it was. The player-present half is
 * [DesktopCarriedRemoteComposeTest], in `carriedRemoteComposeTest`.
 */
class DesktopCarriedRemoteComposeAbsentTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun withoutThePlayerNothingIsAdvertisedAndACarriedDocumentIsRefused() {
    assertFalse("the player is not on this classpath", CarriedRemoteCompose.isAvailable())
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = tempFolder.newFolder("renders")),
        previewSpecResolver = { previewId ->
          if (previewId == RED_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = 32,
              heightPx = 32,
              density = 1.0f,
              outputBaseName = "carried-absent-red",
            )
          } else null
        },
      )
    assertFalse("remoteCompose" in host.supportedOverrides)
    assertFalse(CarriedRemoteCompose.LIVE_CAPABILITY in host.supportedOverrides)
    host.start()
    try {
      val carried =
        PreviewOverrides(
          remoteCompose =
            RemoteComposeOverride.Builder()
              .also { it.documentBase64 = Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2)) }
              .build()
        )
      for (previewId in listOf(RED_PREVIEW_ID, "com.example.NotInTheManifestKt.Nothing")) {
        try {
          host.submit(
            RenderRequest.Render(
              target = RenderTarget.Preview(previewId = previewId, overrides = carried)
            ),
            timeoutMs = 60_000,
          )
          fail("a carried document must not render without the player ($previewId)")
        } catch (e: IllegalStateException) {
          assertTrue(
            "the refusal names the missing player: ${e.message}",
            e.message.orEmpty().contains("rc-player-compose-jvm"),
          )
        }
      }
      // A held session is refused the same way, so `stream/start` errors instead of falling back.
      try {
        host
          .acquireInteractiveSession(
            RED_PREVIEW_ID,
            DesktopCarriedRemoteComposeAbsentTest::class.java.classLoader!!,
            overrides = carried,
          )
          .close()
        fail("a held session must not play a carried document without the player")
      } catch (e: IllegalStateException) {
        assertTrue(e.message.orEmpty().contains("rc-player-compose-jvm"))
      }
      // Without a document, nothing changed.
      val plain =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Preview(previewId = RED_PREVIEW_ID)),
          timeoutMs = 60_000,
        )
      val png = plain.artifact.pathOrNull()
      assertNotNull("a plain render still writes its PNG", png)
      val image = ImageIO.read(File(png!!))
      assertTrue("RedSquare still renders", (image.getRGB(16, 16) and 0xFFFFFF) == 0xEF5350)
    } finally {
      host.shutdown()
    }
  }

  private companion object {
    const val RED_PREVIEW_ID: String = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.Red"
  }
}
