package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeChange
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.awt.image.BufferedImage
import java.io.File
import java.net.URLClassLoader
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidDwfSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun tearDown() {
    System.clearProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP)
    System.clearProperty(BundleIrReplayStore.IR_DIR_PROP)
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun `isCmpRemoteComposePlayerAvailable detects rc-player-compose without androidx remote creation`() {
    val emptyLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
    assertFalse(isCmpRemoteComposePlayerAvailable(emptyLoader))
    assertTrue(
      "test classpath includes rc-player-compose",
      isCmpRemoteComposePlayerAvailable(LOADER),
    )
    assertEquals(
      isCmpRemoteComposePlayerAvailable(ClassLoader.getSystemClassLoader()),
      isCmpRemoteComposePlayerAvailable(null),
    )
  }

  @Test
  fun `renderSpecFromInfo seeds DWF rc document and default clock on Android`() {
    val dir = tempFolder.newFolder("android-dwf-info")
    val rcFile = dir.resolve("dwf-renders/dwf__sample.rc").apply { parentFile.mkdirs() }
    rcFile.writeBytes(DWF_SAMPLE)

    val spec =
      renderSpecFromInfo(
        info =
          PreviewInfoDto(
            id = "dwf__sample",
            className = "",
            methodName = "sample_dwf_watchface",
            params =
              PreviewParamsDto(
                device = "id:wearos_large_round",
                widthDp = 450,
                heightDp = 450,
                density = 1.0f,
                dwfAssetPath = "raw/sample_dwf_watchface.xml",
              ),
            captures = listOf(PreviewCaptureDto(renderOutput = "dwf-renders/dwf__sample.png")),
          ),
        manifestDir = dir,
      )

    assertNotNull(spec.overrides)
    assertEquals(DwfDocumentSeed.DEFAULT_CLOCK_EPOCH_MILLIS, spec.overrides?.clockEpochMillis)
    assertEquals("cmp-android", spec.overrides?.remoteCompose?.playerId)
    assertArrayEquals(
      DWF_SAMPLE,
      Base64.getDecoder().decode(spec.overrides?.remoteCompose?.documentBase64),
    )
  }

  @Test
  fun `Android PreviewManifestRouter runs one-shot and held interactive DWF sessions`() {
    System.setProperty(
      RenderEngine.OUTPUT_DIR_PROP,
      tempFolder.newFolder("android-dwf-out").absolutePath,
    )
    System.setProperty("roborazzi.test.record", "true")
    BundleIrReplayStore.resetForTest()

    val manifestDir = tempFolder.newFolder("android-dwf-manifest")
    manifestDir
      .resolve("dwf-renders/dwf__sample.rc")
      .apply { parentFile.mkdirs() }
      .writeBytes(DWF_SAMPLE)
    val manifestFile = manifestDir.resolve("previews.json")
    manifestFile.writeText(
      """
      {
        "previews": [
          {
            "id": "dwf__sample",
            "functionName": "sample_dwf_watchface",
            "className": "",
            "params": {
              "device": "id:wearos_large_round",
              "widthDp": 450,
              "heightDp": 450,
              "density": 1.0,
              "showBackground": false,
              "dwfAssetPath": "raw/sample_dwf_watchface.xml"
            },
            "captures": [
              { "renderOutput": "dwf-renders/dwf__sample.png" }
            ]
          }
        ]
      }
      """
        .trimIndent(),
    )

    val host =
      PreviewManifestRouter(
        manifest = PreviewManifestRouter.loadManifest(manifestFile),
        sandboxCount = 2,
        manifestDir = manifestDir,
      )
    host.start()
    try {
      val defaultClock =
        decode(
          host
            .submit(RenderRequest.Render(target = RenderTarget.Preview("dwf__sample")))
            .artifact
            .pathOrNull()!!,
        )
      assertEquals(450, defaultClock.width)
      assertDrawn("Android DWF one-shot renders at default 10:10:30 UTC", defaultClock)

      val shiftedClock =
        decode(
          host
            .submit(
              RenderRequest.Render(
                target =
                  RenderTarget.Preview(
                    previewId = "dwf__sample",
                    overrides = PreviewOverrides(clockEpochMillis = 1_763_437_500_000L),
                  ),
              ),
            )
            .artifact
            .pathOrNull()!!,
        )
      assertDrawn("Android DWF one-shot renders at overridden clock", shiftedClock)
      assertDiffers("clockEpochMillis moves the Android DWF watch face", defaultClock, shiftedClock)

      val ambientOneShot =
        decode(
          host
            .submit(
              RenderRequest.Render(
                target =
                  RenderTarget.Preview(
                    previewId = "dwf__sample",
                    overrides =
                      PreviewOverrides(
                        remoteCompose =
                          RemoteComposeOverride.Builder()
                            .also {
                              it.namedValues =
                                mapOf("dwf.is_ambient" to RemoteNamedValue.BooleanValue(true))
                            }
                            .build(),
                      ),
                  ),
              ),
            )
            .artifact
            .pathOrNull()!!,
        )
      assertDrawn("Android DWF one-shot renders in ambient namedValue mode", ambientOneShot)
      assertDiffers(
        "dwf.is_ambient changes the Android DWF watch face",
        defaultClock,
        ambientOneShot,
      )

      val session = host.acquireInteractiveSession(previewId = "dwf__sample", classLoader = LOADER)
      try {
        val heldInitial = frame(session, advanceMs = 32L)
        assertDrawn("held Android DWF session renders without a preview class", heldInitial)

        // Advancing the held clock by 15 seconds moves the watch face's second hand!
        val heldAdvanced = frame(session, advanceMs = 15_000L)
        assertDiffers(
          "advancing the held session clock moves the DWF watch face",
          heldInitial,
          heldAdvanced,
        )

        // Live `interactive/setRemoteCompose` crosses DaemonHostBridge into the sandbox's
        // RemoteComposeController and updates the held DWF watch face in place.
        assertTrue(
          session.dispatchRemoteComposeChange(
            RemoteComposeChange.NamedValue("dwf.is_ambient", RemoteNamedValue.BooleanValue(true)),
          ),
        )
        val heldAmbient = frame(session, advanceMs = 32L)
        assertDiffers(
          "live dispatchRemoteComposeChange updates the held Android DWF watch face",
          heldAdvanced,
          heldAmbient,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun frame(session: InteractiveSession, advanceMs: Long): BufferedImage {
    session.render(requestId = RenderHost.nextRequestId(), advanceTimeMs = advanceMs)
    return decode(
      session
        .render(requestId = RenderHost.nextRequestId(), advanceTimeMs = 32L)
        .artifact
        .pathOrNull()!!,
    )
  }

  private companion object {
    val LOADER: ClassLoader = AndroidDwfSessionTest::class.java.classLoader!!

    val DWF_SAMPLE: ByteArray =
      checkNotNull(
          AndroidDwfSessionTest::class
            .java
            .getResourceAsStream("/rc-fixtures/dwf-sample-watchface.rc"),
        ) {
          "missing DWF sample fixture"
        }
        .use { it.readBytes() }

    fun decode(path: String): BufferedImage =
      ImageIO.read(File(path)) ?: error("could not decode $path")

    fun assertDrawn(message: String, img: BufferedImage) {
      val background = img.getRGB(0, 0)
      var ink = 0
      for (y in 0 until img.height) for (x in 0 until img.width) {
        if (img.getRGB(x, y) != background) ink++
      }
      val pct = ink.toDouble() / (img.width * img.height)
      assertTrue("$message: only ${"%.2f".format(pct * 100)}% of the frame is ink", pct >= 0.02)
    }

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
