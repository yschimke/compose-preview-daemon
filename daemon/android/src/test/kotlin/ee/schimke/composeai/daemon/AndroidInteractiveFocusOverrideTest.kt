package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.tools.ToolProvider
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pixel-level regressions for authored and gaze-driven focus in Android Live mode. */
class AndroidInteractiveFocusOverrideTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun focus_override_reaches_the_held_composition() {
    val outputDir = tempFolder.newFolder("interactive-focus-override-renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = ::resolvePreview)
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = AndroidInteractiveFocusOverrideTest::class.java.classLoader!!,
          overrides = PreviewOverrides(focus = FocusOverride(tabIndex = 0)),
        )
      try {
        val frame = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("held render must produce a PNG path", frame.artifact.pathOrNull())
        val img = decode(File(frame.artifact.pathOrNull()!!))

        val focused = pixelMatchPct(img, expectedRgb = FOCUSED_FILL_RGB)
        val resting = pixelMatchPct(img, expectedRgb = RESTING_FILL_RGB)
        assertTrue(
          "the held composition must receive real focus — focused " +
            "${"%.1f".format(focused * 100)}%, resting ${"%.1f".format(resting * 100)}%",
          focused > 0.9 && resting < 0.01,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  /** Regression for the Glimmer Live-mode initial-frame mismatch introduced in #107. */
  @Test
  fun glimmer_session_without_focus_override_matches_the_resting_snapshot() {
    val outputDir = tempFolder.newFolder("interactive-glimmer-resting-renders")
    val glimmerMarkerDir = compileGlimmerMarker()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val host =
      RobolectricHost(
        sandboxCount = 2,
        userClassloaderHolderFactory = { sandboxClassLoader ->
          UserClassLoaderHolder(
            urls = listOf(glimmerMarkerDir.toURI().toURL()),
            parentSupplier = { sandboxClassLoader },
          )
        },
        previewSpecResolver = ::resolvePreview,
      )
    host.start()
    try {
      val snapshotResult =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Preview(previewId = GLIMMER_PREVIEW_ID)),
          timeoutMs = 120_000,
        )
      val snapshot = decode(File(snapshotResult.artifact.pathOrNull()!!))

      val session =
        host.acquireInteractiveSession(
          previewId = GLIMMER_PREVIEW_ID,
          classLoader = AndroidInteractiveFocusOverrideTest::class.java.classLoader!!,
        )
      try {
        val liveResult = session.render(requestId = RenderHost.nextRequestId())
        val live = decode(File(liveResult.artifact.pathOrNull()!!))

        assertEquals(snapshot.width, live.width)
        assertEquals(snapshot.height, live.height)
        assertEquals("touch-sized snapshot fixture", TOUCH_FRAME_PX, snapshot.width)
        assertEquals("touch-sized Live fixture", TOUCH_FRAME_PX, live.width)
        assertArrayEquals(
          "an unoverridden Glimmer Live session must match the resting snapshot pixel-for-pixel",
          snapshot.getRGB(0, 0, snapshot.width, snapshot.height, null, 0, snapshot.width),
          live.getRGB(0, 0, live.width, live.height, null, 0, live.width),
        )
        assertTrue(
          "an unoverridden Glimmer Live session must retain the snapshot's resting pixels",
          pixelMatchPct(live, expectedRgb = RESTING_FILL_RGB) > 0.9 &&
            pixelMatchPct(live, expectedRgb = FOCUSED_FILL_RGB) < 0.01,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = TOUCH_FRAME_PX / 2,
            pixelY = TOUCH_FRAME_PX / 2,
          )
        )
        val directActivation =
          decode(
            File(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)
          )
        assertEquals(
          "direct activation retains the touch-sized width",
          TOUCH_FRAME_PX,
          directActivation.width,
        )
        assertEquals(
          "direct activation retains the touch-sized height",
          TOUCH_FRAME_PX,
          directActivation.height,
        )
        assertTrue(
          "direct activation cannot leave touch-acquired focus latched",
          pixelMatchPct(directActivation, expectedRgb = RESTING_FILL_RGB) > 0.9 &&
            pixelMatchPct(directActivation, expectedRgb = FOCUSED_FILL_RGB) < 0.01,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.POINTER_MOVE,
            pixelX = TOUCH_FRAME_PX / 2,
            pixelY = TOUCH_FRAME_PX / 2,
          )
        )
        val gazeFocused =
          decode(
            File(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)
          )
        assertTrue(
          "pointer movement must still acquire Glimmer gaze focus after the resting first frame",
          pixelMatchPct(gazeFocused, expectedRgb = FOCUSED_FILL_RGB) > 0.9 &&
            pixelMatchPct(gazeFocused, expectedRgb = RESTING_FILL_RGB) < 0.01,
        )
        assertEquals(
          "gaze switches to the compact keyboard-mode width",
          GAZE_FRAME_PX,
          gazeFocused.width,
        )
        assertEquals(
          "gaze switches to the compact keyboard-mode height",
          GAZE_FRAME_PX,
          gazeFocused.height,
        )

        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "irrelevant-on-host-side",
            kind = InteractiveInputKind.CLICK,
            pixelX = GAZE_FRAME_PX / 2,
            pixelY = GAZE_FRAME_PX / 2,
          )
        )
        val activated =
          decode(
            File(session.render(requestId = RenderHost.nextRequestId()).artifact.pathOrNull()!!)
          )
        assertEquals("gaze activation retains keyboard-mode width", GAZE_FRAME_PX, activated.width)
        assertEquals(
          "gaze activation retains keyboard-mode height",
          GAZE_FRAME_PX,
          activated.height,
        )
        assertTrue(
          "activation retains focus while gaze remains on the target",
          pixelMatchPct(activated, expectedRgb = FOCUSED_FILL_RGB) > 0.9 &&
            pixelMatchPct(activated, expectedRgb = RESTING_FILL_RGB) < 0.01,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun compileGlimmerMarker(): File {
    val output = tempFolder.newFolder("glimmer-marker-classes")
    val sourceDir = output.resolve("src/androidx/xr/glimmer").apply { mkdirs() }
    val source =
      sourceDir.resolve("SurfaceKt.java").apply {
        writeText("package androidx.xr.glimmer; public final class SurfaceKt {}")
      }
    val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler required" }
    assertEquals(
      "failed to compile the isolated Glimmer classpath marker",
      0,
      compiler.run(null, null, null, "-d", output.absolutePath, source.absolutePath),
    )
    return output
  }

  private fun resolvePreview(previewId: String): RenderSpec? =
    when (previewId) {
      PREVIEW_ID ->
        RenderSpec(
          previewId = PREVIEW_ID,
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "InteractionStateSquare",
          widthPx = FRAME_PX,
          heightPx = FRAME_PX,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-focus-override",
        )
      GLIMMER_PREVIEW_ID ->
        RenderSpec(
          previewId = GLIMMER_PREVIEW_ID,
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "InputModeSizedInteractionStateSquare",
          widthPx = TOUCH_FRAME_PX,
          heightPx = TOUCH_FRAME_PX,
          wrapWidth = true,
          wrapHeight = true,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "interactive-glimmer-input-mode",
        )
      else -> null
    }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int = 16,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        if (
          abs(((rgb shr 16) and 0xFF) - expR) <= perChannelTolerance &&
            abs(((rgb shr 8) and 0xFF) - expG) <= perChannelTolerance &&
            abs((rgb and 0xFF) - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  private companion object {
    const val PREVIEW_ID = "android-focus-override-interactive"
    const val GLIMMER_PREVIEW_ID = "android-glimmer-input-mode-interactive"
    const val FRAME_PX = 64
    const val TOUCH_FRAME_PX = 48
    const val GAZE_FRAME_PX = 28
    const val RESTING_FILL_RGB = 0xEF5350
    const val FOCUSED_FILL_RGB = 0xFFA726
  }
}
