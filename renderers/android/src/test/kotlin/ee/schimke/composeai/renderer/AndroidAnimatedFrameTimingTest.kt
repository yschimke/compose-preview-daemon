package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins that an Android `@AnimatedPreview` / `@InteractionPreview` capture advances the animation by
 * the frame delay its GIF / APNG declares — on average, and without compounding drift.
 *
 * The fixture is a ruler: a white bar sweeping 1 px per ms of animation time across a black strip
 * at density 1, so the bar's displacement between two captured frames *is* the animation time
 * between them. Measured through the real capture paths (20 frames):
 *
 * |interval|`advanceTimeBy(i)` (before)                                        |`advanceTimeBy(i, ignoreFrameDuration = true)` (now)|
 * |--------|-------------------------------------------------------------------|----------------------------------------------------|
 * |33 ms   |48 ms every frame — 960 ms of motion for 660 ms of GIF (1.45× fast)|32 ms, one 48 — 656 ms (avg 32.8)                   |
 * |50 ms   |64 ms every frame — 1280 ms for 1000 ms (1.28× fast)               |48 ms, two 64 — 992 ms (avg 49.6)                   |
 *
 * Animation time only ever advances in whole 16 ms ticks on the test clock, so the honest promise
 * is the one the desktop renderer makes: frame `n` is within one tick of `n × interval`, never
 * further. The rounded-up step breaks that by the third frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1100dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidAnimatedFrameTimingTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("android-animated-frame-timing").toFile()
    System.setProperty("roborazzi.test.record", "true")
    rule.mainClock.autoAdvance = false
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `33ms animated frames advance the animation by 33ms`() {
    assertTracksTimeline(33, animatedPositions(frameIntervalMs = 33, durationMs = 660))
  }

  @Test
  fun `50ms animated frames advance the animation by 50ms`() {
    assertTracksTimeline(50, animatedPositions(frameIntervalMs = 50, durationMs = 1000))
  }

  @Test
  fun `33ms interaction frames advance the animation by 33ms`() {
    assertTracksTimeline(33, interactionPositions(frameIntervalMs = 33))
  }

  private fun animatedPositions(frameIntervalMs: Int, durationMs: Int): List<Int> {
    setRuler()
    val out = File(rootDir, "ruler-$frameIntervalMs.gif")
    val handled =
      handleAnimatedCapture(
        rule = rule,
        animation =
          AnimationCapture(
            durationMs = durationMs,
            frameIntervalMs = frameIntervalMs,
            showCurves = false,
          ),
        previewId = "Test.ruler$frameIntervalMs",
        isRound = false,
        outputFile = out,
        curveCapture = null,
      )
    assertTrue("the capture must claim the slot", handled)
    return gifFrames(out).map(::barLeftEdge)
  }

  private fun interactionPositions(frameIntervalMs: Int): List<Int> {
    setRuler()
    val out = File(rootDir, "ruler-interaction-$frameIntervalMs.gif")
    val handled =
      handleInteractionCapture(
        rule = rule,
        interaction =
          InteractionCapture(
            targets = listOf(0),
            // leadIn + tap (90) + gap: a 660 ms script, 20 frames at 33 ms.
            leadInMs = 70,
            gapMs = 500,
            frameIntervalMs = frameIntervalMs,
            format = MotionFormat.GIF,
          ),
        previewId = "Test.rulerInteraction$frameIntervalMs",
        isRound = false,
        outputFile = out,
        wrapWidth = false,
        wrapHeight = false,
        padArgb = 0xFF0000FF.toInt(),
        measuredContent = { null },
      )
    assertTrue("the capture must claim the slot", handled)
    return gifFrames(out).map(::barLeftEdge)
  }

  /**
   * Every consecutive pair of frames is within one tick of [interval] apart, and — the part a
   * rounded-up step and an exact-but-lossy step both fail — frame `n` is within one tick of `n ×
   * interval` from frame 0, so the error cannot accumulate across the capture.
   */
  private fun assertTracksTimeline(interval: Int, positions: List<Int>) {
    assertTrue("expected a multi-frame capture, got ${positions.size}", positions.size >= 12)
    val deltas = positions.zipWithNext { a, b -> Math.floorMod(b - a, LINEAR_TRAVEL_PX) }
    System.err.println("ruler $interval ms: deltas=$deltas mean=${deltas.average()}")
    deltas.forEachIndexed { i, d ->
      assertTrue(
        "frame ${i + 1} moved $d ms for a $interval ms frame: $deltas",
        abs(d - interval) < TICK_MS,
      )
    }
    var travelled = 0
    deltas.forEachIndexed { i, d ->
      travelled += d
      val expected = (i + 1) * interval
      assertTrue(
        "frame ${i + 1} is $travelled ms into the animation, expected $expected ± one tick: $deltas",
        abs(travelled - expected) < TICK_MS,
      )
    }
  }

  private fun setRuler() {
    rule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) { LinearRuler() }
    }
    rule.mainClock.advanceTimeByFrame()
  }

  /** At density 1 the bar's left edge sits at `x = t mod 1000` px: a pixel is a millisecond. */
  @Composable
  private fun LinearRuler() {
    val transition = rememberInfiniteTransition(label = "ruler")
    val fraction by
      transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
          infiniteRepeatable(
            tween(durationMillis = LINEAR_TRAVEL_PX, easing = LinearEasing),
            RepeatMode.Restart,
          ),
        label = "fraction",
      )
    Box(Modifier.size((LINEAR_TRAVEL_PX + BAR_PX).dp, 4.dp).background(Color.Black).clickable {}) {
      Box(
        Modifier.offset { IntOffset((LINEAR_TRAVEL_PX * fraction).roundToInt(), 0) }
          .size(BAR_PX.dp, 4.dp)
          .background(Color.White)
      )
    }
  }

  private fun barLeftEdge(frame: BufferedImage): Int =
    (0 until minOf(frame.width, LINEAR_TRAVEL_PX + BAR_PX)).first { x ->
      (frame.getRGB(x, 1) shr 16 and 0xFF) > 128
    }

  private fun gifFrames(gif: File): List<BufferedImage> {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    return ImageIO.createImageInputStream(gif).use { input ->
      reader.input = input
      (0 until reader.getNumImages(true)).map { reader.read(it) }.also { reader.dispose() }
    }
  }

  private companion object {
    const val LINEAR_TRAVEL_PX = 1000
    const val BAR_PX = 8
    const val TICK_MS = 16
  }
}
