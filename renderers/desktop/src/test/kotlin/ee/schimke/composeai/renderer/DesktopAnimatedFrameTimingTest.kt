package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins that a desktop `@AnimatedPreview` GIF advances the animation by exactly the frame delay it
 * declares.
 *
 * `MainTestClock.advanceTimeBy(ms)` rounds `ms` **up** to whole 16 ms frames unless it is told
 * `ignoreFrameDuration = true`. The renderer used to step its paused clock with the rounding
 * overload, so a 33 ms capture actually moved the animation 48 ms per frame and a 50 ms one moved
 * it 64 ms — while the GIF still said 33 / 50 ms, so every desktop animated GIF played ≈1.45× /
 * ≈1.28× too fast and a loop-length capture no longer closed on itself.
 *
 * [LinearClockRuler] turns the animation clock into pixels (1 px per ms at `density = 1`), so the
 * bar's displacement between consecutive frames is the animation time between them.
 *
 * The harness samples animations on its 16 ms render loop, so a 33 ms frame can only ever show 32
 * or 48 ms of motion; what these pin is that the error never exceeds that one tick and never
 * compounds — see [advanceMotionFrame].
 */
class DesktopAnimatedFrameTimingTest {

  private companion object {
    /** The Skiko test harness's render-loop period: the grid animations are sampled on. */
    const val FRAME_TICK_MS = 16
  }

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `33ms frames advance the animation by 33ms`() {
    val positions = capture(frameIntervalMs = 33, durationMs = 660)
    assertEquals(20, positions.size)
    assertTracksDeclaredDelay(33, positions)
  }

  @Test
  fun `50ms frames advance the animation by 50ms`() {
    val positions = capture(frameIntervalMs = 50, durationMs = 1000)
    assertEquals(20, positions.size)
    assertTracksDeclaredDelay(50, positions)
  }

  @Test
  fun `a loop-length capture spanning whole frames closes exactly on its first frame`() {
    // The shaders' shape: a 2000 ms loop at 50 ms is 40 frames, and the frame after the last one
    // is t0 + 2000 ms. 2000 is a whole number of 16 ms ticks, so that frame must be frame 0's phase
    // exactly — the GIF loops seamlessly. Capture one extra frame to observe it.
    val interval = 50
    val loopFrames = 2 * LINEAR_CLOCK_PERIOD_MS / interval
    val positions = capture(frameIntervalMs = interval, durationMs = (loopFrames + 1) * interval)
    assertEquals(loopFrames + 1, positions.size)
    assertTrue(
      "frame $loopFrames (one step past the loop) should be frame 0's phase " +
        "(${positions.first()} px), was ${positions[loopFrames]} px — positions: $positions",
      circularDistance(positions[loopFrames], positions.first()) <= 1,
    )
  }

  @Test
  fun `any loop-length capture closes on its first frame to within one tick`() {
    // 1000 ms is not a whole number of 16 ms ticks, so the frame one step past the loop can only
    // land on the last tick before t0 + 1000 ms — but it must not overshoot by a loop's worth of
    // rounding, the way 20 × 64 ms = 1280 ms did.
    val interval = 50
    val loopFrames = LINEAR_CLOCK_PERIOD_MS / interval
    val positions = capture(frameIntervalMs = interval, durationMs = (loopFrames + 1) * interval)
    assertTrue(
      "frame $loopFrames should be within one 16 ms tick of frame 0's phase " +
        "(${positions.first()} px), was ${positions[loopFrames]} px — positions: $positions",
      circularDistance(positions[loopFrames], positions.first()) < FRAME_TICK_MS,
    )
  }

  @Test
  fun `interaction recordings advance the animation by their frame interval`() {
    // `@InteractionPreview` steps the same paused clock between frames, and its script events are
    // scheduled against `n × frameIntervalMs` — so a rounded advance made it both too fast and out
    // of step with its own presses.
    val outputFile = File(tempFolder.newFolder("renders"), "ruler-interaction.gif")
    renderInteractionPreview(
      className = "ee.schimke.composeai.renderer.AnimatedRenderTestFixturesKt",
      functionName = "ClickableLinearClockRuler",
      widthPx = LINEAR_CLOCK_TRAVEL_PX + 8,
      heightPx = 4,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      spec =
        InteractionSpec(
          gesture = InteractionGestureKind.TAP,
          targets = listOf(0),
          holdMs = 100,
          gapMs = 200,
          leadInMs = 100,
          frameIntervalMs = 33,
          format = MotionFormatKind.GIF,
        ),
    )
    val positions = readGifFrames(outputFile).map(::barLeftEdge)
    assertTrue("expected a multi-frame recording, got ${positions.size}", positions.size > 5)
    assertTracksDeclaredDelay(33, positions)
  }

  /**
   * Frame `n` must show the animation at `t0 + n × expectedMs`, to within one 16 ms harness tick
   * (the harness only samples animations on its own frame grid — see [advanceMotionFrame]) and
   * without that error compounding; the average step must be the declared delay.
   */
  private fun assertTracksDeclaredDelay(expectedMs: Int, positions: List<Int>) {
    val steps = positions.zipWithNext { a, b -> Math.floorMod(b - a, LINEAR_CLOCK_TRAVEL_PX) }
    val elapsed = steps.runningFold(0) { acc, step -> acc + step }
    elapsed.forEachIndexed { n, actual ->
      val ideal = n * expectedMs
      assertTrue(
        "frame $n shows the animation ${actual}ms after frame 0, but at ${expectedMs}ms per " +
          "frame the GIF says ${ideal}ms (must be within one ${FRAME_TICK_MS}ms tick) — " +
          "positions: $positions",
        abs(actual - ideal) < FRAME_TICK_MS,
      )
    }
    val mean = elapsed.last().toDouble() / steps.size
    assertEquals(
      "mean animation time per frame — positions: $positions",
      expectedMs.toDouble(),
      mean,
      1.0,
    )
  }

  private fun circularDistance(a: Int, b: Int): Int {
    val d = Math.floorMod(a - b, LINEAR_CLOCK_TRAVEL_PX)
    return minOf(d, LINEAR_CLOCK_TRAVEL_PX - d)
  }

  /** Renders [LinearClockRuler] and returns the bar's left edge, in px, in every frame. */
  private fun capture(frameIntervalMs: Int, durationMs: Int): List<Int> {
    val outputFile = File(tempFolder.newFolder("renders"), "ruler-$frameIntervalMs.gif")
    renderAnimatedPreview(
      className = "ee.schimke.composeai.renderer.AnimatedRenderTestFixturesKt",
      functionName = "LinearClockRuler",
      widthPx = LINEAR_CLOCK_TRAVEL_PX + 8,
      heightPx = 4,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      durationMs = durationMs,
      frameIntervalMs = frameIntervalMs,
      showCurves = false,
    )
    return readGifFrames(outputFile).map(::barLeftEdge)
  }

  private fun barLeftEdge(frame: BufferedImage): Int {
    val y = frame.height / 2
    for (x in 0 until frame.width) {
      val rgb = frame.getRGB(x, y)
      val luma = ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3
      if (luma > 127) return x
    }
    error("no bar found in a ${frame.width}×${frame.height} frame")
  }

  private fun readGifFrames(file: File): List<BufferedImage> {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(file.readBytes())).use { stream ->
      reader.input = stream
      return (0 until reader.getNumImages(true)).map { reader.read(it) }
    }
  }
}
