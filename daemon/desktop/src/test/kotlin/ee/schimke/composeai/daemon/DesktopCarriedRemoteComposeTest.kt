package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Remote Compose document carried on `overrides.remoteCompose.documentBase64`, played on the
 * **desktop** daemon by the real CMP player (`cmp-jvm`, rc-players' `rc-player-compose`) — on the
 * one-shot render, a held interactive (`stream/start`) session and a recording.
 *
 * Runs only in `:daemon:desktop:carriedRemoteComposeTest`, whose classpath has the player (and the
 * Compose Multiplatform line it needs); the default `test` task covers the player-absent half in
 * [DesktopCarriedRemoteComposeAbsentTest].
 *
 * The document is the remote-m3 catalog's `Stepper` (`stepper__ideal__default__compact`, from
 * rc-players' own jvmTest fixtures): a level rail between an increase and a decrease button, its
 * value a mutable remote float each button changes. A tap that reaches the player moves the rail,
 * and the move has to outlive the document's per-frame re-declaration of the value.
 */
class DesktopCarriedRemoteComposeTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private lateinit var host: DesktopHost

  @Before
  fun startHost() {
    System.setProperty(
      DesktopHost.RECORDINGS_DIR_PROP,
      tempFolder.newFolder("recordings").absolutePath,
    )
    host =
      DesktopHost(
        engine = RenderEngine(outputDir = tempFolder.newFolder("renders")),
        previewSpecResolver = { previewId ->
          if (previewId == CATALOG_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              widthPx = SIZE_PX,
              heightPx = SIZE_PX,
              density = 1.0f,
              outputBaseName = "carried-catalog",
            )
          } else null
        },
      )
    host.start()
  }

  @After
  fun stopHost() {
    host.shutdown()
    System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
  }

  @Test
  fun advertisesTheCarriedDocumentLanesWhenThePlayerIsPresent() {
    assertTrue(CarriedRemoteCompose.isAvailable())
    assertTrue("remoteCompose" in host.supportedOverrides)
    assertTrue(CarriedRemoteCompose.LIVE_CAPABILITY in host.supportedOverrides)
  }

  @Test
  fun oneShotRenderPlaysTheCarriedDocumentForAnUnknownId() {
    val image = oneShot("com.example.NotInTheManifestKt.Nothing", carried())
    assertEquals("framed by the override width", SIZE_PX, image.width)
    assertEquals("framed by the override height", SIZE_PX, image.height)
    assertDrawn("the carried document is drawn", image)
    // The same bytes on the same frame are the same pixels: the document's clock is frame time,
    // not the host's wall clock, so a one-shot render is deterministic.
    assertSame(
      "a one-shot carried render is deterministic",
      image,
      oneShot("com.example.NotInTheManifestKt.Nothing", carried()),
    )
  }

  @Test
  fun uiModeReachesThePlayersTheme() {
    // The player resolves its default `RcPlayerTheme.System` through `isSystemInDarkTheme()`, which
    // on desktop reads the `LocalSystemTheme` the engine sets from `uiMode`; the stepper's colours
    // come from the document's light / dark theme sections.
    val light = oneShot("com.example.Themed", carried().copy(uiMode = UiMode.LIGHT))
    val dark = oneShot("com.example.Themed", carried().copy(uiMode = UiMode.DARK))
    assertDrawn("dark", dark)
    assertDiffers("uiMode changes the carried document's theme", light, dark)
  }

  @Test
  fun aBorrowedCatalogPreviewPlaysTheCarriedDocumentNotItself() {
    // Control first: the id alone draws the red fixture, so the assertion below is not a render
    // that would have been non-red regardless.
    val control = oneShot(CATALOG_PREVIEW_ID, PreviewOverrides(widthPx = SIZE_PX))
    assertTrue("the catalog preview draws itself", redShare(control) >= 0.95)
    val carried = oneShot(CATALOG_PREVIEW_ID, carried())
    assertTrue(
      "the carried document replaces the catalog preview (red share ${redShare(carried)})",
      redShare(carried) < 0.05,
    )
    assertDrawn("the carried document is drawn", carried)
  }

  @Test
  fun heldSessionPlaysTheCarriedDocumentAndTakesATap() {
    val session =
      host.acquireInteractiveSession(
        previewId = "com.example.NotInTheManifestKt.Live",
        classLoader = LOADER,
        overrides = carried(),
      )
    try {
      val atRest = frame(session)
      assertEquals(SIZE_PX, atRest.width)
      assertDrawn("the held session draws the carried document", atRest)
      session.dispatch(
        InteractiveInputParams(
          frameStreamId = "irrelevant-on-host-side",
          kind = InteractiveInputKind.CLICK,
          pixelX = SIZE_PX / 2,
          // The increase button, where rc-players' own stepper click test presses it.
          pixelY = (SIZE_PX * 0.12f).toInt(),
        )
      )
      // Let the press settle and the value animate before comparing.
      Thread.sleep(400)
      val tapped = frame(session)
      assertDiffers("a tap reaches the carried document", atRest, tapped)
      Thread.sleep(400)
      assertDiffers("the tapped value survives later frames", atRest, frame(session))
    } finally {
      session.close()
    }
  }

  @Test
  fun recordingPlaysTheCarriedDocument() {
    val recording =
      host.acquireRecordingSession(
        previewId = "com.example.NotInTheManifestKt.Recorded",
        recordingId = "carried-recording",
        classLoader = LOADER,
        fps = 10,
        scale = 1.0f,
        overrides = carried(),
      )
    try {
      recording.postScript(
        listOf(
          RecordingScriptEvent(
            tMs = 100L,
            kind = "input.click",
            pixelX = SIZE_PX / 2,
            pixelY = (SIZE_PX * 0.12f).toInt(),
          ),
          RecordingScriptEvent(tMs = 900L, kind = "recording.probe"),
        )
      )
      val result = recording.stop()
      assertEquals(SIZE_PX, result.frameWidthPx)
      assertEquals(SIZE_PX, result.frameHeightPx)
      val first = decode(File(result.framesDir, "frame-00000.png"))
      val last = decode(File(result.framesDir, "frame-%05d.png".format(result.frameCount - 1)))
      assertDrawn("a recording plays the carried document", first)
      assertDiffers("the scripted tap reaches the recorded document", first, last)
    } finally {
      recording.close()
    }
  }

  @Test
  fun refusesWhatItCannotDrawAndKeepsRendering() {
    val viewPlayer = carried { it.playerId = "androidx-view" }
    expectRefusal<IllegalArgumentException>("an AndroidX player id") {
      oneShot("com.example.Unknown", viewPlayer)
    }
    expectRefusal<IllegalArgumentException>("the AndroidX player enum") {
      oneShot("com.example.Unknown", carried { it.player = RemoteComposePlayerKind.VIEW })
    }
    expectRefusal<IllegalArgumentException>("bytes that are not base64") {
      oneShot("com.example.Unknown", carried { it.documentBase64 = "not base64 !!" })
    }
    expectRefusal<IllegalArgumentException>("a held session for a player it does not have") {
      host.acquireInteractiveSession("com.example.Unknown", LOADER, overrides = viewPlayer).close()
    }
    // Both spellings of the one player it has are accepted.
    assertDrawn(
      "rcplayer-jvm",
      oneShot("com.example.Unknown", carried { it.playerId = "rcplayer-jvm" }),
    )
    // And nothing above wedged the host or a later plain render.
    assertTrue(redShare(oneShot(CATALOG_PREVIEW_ID, PreviewOverrides(widthPx = SIZE_PX))) >= 0.95)
  }

  private fun oneShot(previewId: String, overrides: PreviewOverrides): BufferedImage {
    val result =
      host.submit(
        RenderRequest.Render(
          target = RenderTarget.Preview(previewId = previewId, overrides = overrides)
        ),
        timeoutMs = 60_000,
      )
    return decode(File(checkNotNull(result.artifact.pathOrNull()) { "no PNG for $previewId" }))
  }

  private fun frame(session: InteractiveSession): BufferedImage =
    decode(File(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!))

  private fun carried(configure: (RemoteComposeOverride.Builder) -> Unit = {}): PreviewOverrides =
    PreviewOverrides(
      widthPx = SIZE_PX,
      heightPx = SIZE_PX,
      density = 1.0f,
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.documentBase64 = Base64.getEncoder().encodeToString(STEPPER)
            configure(it)
          }
          .build(),
    )

  private inline fun <reified T : Throwable> expectRefusal(what: String, block: () -> Unit) {
    try {
      block()
      fail("$what must be refused")
    } catch (t: Throwable) {
      val cause = generateSequence(t) { it.cause }.firstOrNull { it is T }
      assertTrue("$what: expected ${T::class.simpleName}, got $t", cause != null)
    }
  }

  private companion object {
    const val CATALOG_PREVIEW_ID: String = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.Red"

    const val SIZE_PX: Int = 400

    val LOADER: ClassLoader =
      DesktopCarriedRemoteComposeTest::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

    val STEPPER: ByteArray =
      checkNotNull(
          DesktopCarriedRemoteComposeTest::class
            .java
            .getResourceAsStream("/rc-fixtures/Stepper-ideal-default-compact.rc")
        ) {
          "missing Stepper fixture"
        }
        .use { it.readBytes() }

    fun decode(file: File): BufferedImage = ImageIO.read(file) ?: error("could not decode $file")

    /** Share of pixels within 8 per channel of `RedSquare`'s fill. */
    fun redShare(img: BufferedImage): Double {
      var hits = 0
      for (y in 0 until img.height) for (x in 0 until img.width) {
        val p = img.getRGB(x, y)
        if (
          abs(((p shr 16) and 0xFF) - 0xEF) <= 8 &&
            abs(((p shr 8) and 0xFF) - 0x53) <= 8 &&
            abs((p and 0xFF) - 0x50) <= 8
        ) {
          hits++
        }
      }
      return hits.toDouble() / (img.width * img.height)
    }

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

    fun changedShare(a: BufferedImage, b: BufferedImage): Double {
      assertTrue("frames differ in size", a.width == b.width && a.height == b.height)
      var changed = 0
      for (y in 0 until a.height) for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
      }
      return changed.toDouble() / (a.width * a.height)
    }

    /** At least 0.2% of the pixels differ between [a] and [b]. */
    fun assertDiffers(message: String, a: BufferedImage, b: BufferedImage) {
      val pct = changedShare(a, b)
      assertTrue("$message: only ${"%.3f".format(pct * 100)}% of pixels changed", pct >= 0.002)
    }

    fun assertSame(message: String, a: BufferedImage, b: BufferedImage) {
      assertEquals(message, 0.0, changedShare(a, b), 0.0)
    }
  }
}
