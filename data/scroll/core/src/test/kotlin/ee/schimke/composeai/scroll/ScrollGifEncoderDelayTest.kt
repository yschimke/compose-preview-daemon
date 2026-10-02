package ee.schimke.composeai.scroll

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Holds [ScrollGifEncoder]'s frame delays to the captured timeline.
 *
 * GIF delays are whole centiseconds. Rounding every frame on its own wrote a 33 ms frame as 30 ms,
 * so a default-interval `@AnimatedPreview` GIF played ~10% fast. The encoder now distributes the
 * rounding — `3, 4, 3` cs for 33 ms — so the running total never drifts more than half a
 * centisecond from `n × interval`, and never writes a 0 or 1 cs frame (browsers play those at ~100
 * ms).
 */
class ScrollGifEncoderDelayTest {
  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `total delay over N frames matches N times the interval to within one centisecond`() {
    for (intervalMs in listOf(33, 50, 100, 25, 40, 41, 67)) {
      for (frameCount in 1..120) {
        val delays = ScrollGifEncoder.centisecondDelays(IntArray(frameCount) { intervalMs })
        val totalMs = delays.sum() * 10
        val expectedMs = frameCount * intervalMs
        assertTrue(
          "$intervalMs ms × $frameCount frames: GIF total $totalMs ms vs captured $expectedMs ms",
          abs(totalMs - expectedMs) <= 10,
        )
        assertNoUnplayableDelays("$intervalMs ms × $frameCount", delays)
      }
    }
  }

  @Test
  fun `the running total never drifts more than half a centisecond`() {
    for (intervalMs in listOf(33, 50, 100, 25, 41, 67)) {
      val delays = ScrollGifEncoder.centisecondDelays(IntArray(300) { intervalMs })
      var cumulativeCs = 0
      delays.forEachIndexed { i, cs ->
        cumulativeCs += cs
        val driftMs = abs(cumulativeCs * 10 - (i + 1) * intervalMs)
        assertTrue("$intervalMs ms: frame $i drifted $driftMs ms", driftMs <= 5)
      }
    }
  }

  @Test
  fun `33 ms frames mix 3 and 4 centiseconds rather than all truncating to 3`() {
    assertArrayEquals(
      intArrayOf(3, 4, 3, 3, 4, 3, 3, 3, 4),
      ScrollGifEncoder.centisecondDelays(IntArray(9) { 33 }),
    )
  }

  @Test
  fun `whole-centisecond intervals encode exactly as before`() {
    assertArrayEquals(IntArray(10) { 5 }, ScrollGifEncoder.centisecondDelays(IntArray(10) { 50 }))
    assertArrayEquals(IntArray(10) { 8 }, ScrollGifEncoder.centisecondDelays(IntArray(10) { 80 }))
    assertArrayEquals(
      IntArray(10) { 10 },
      ScrollGifEncoder.centisecondDelays(IntArray(10) { 100 }),
    )
  }

  /**
   * A 16 ms (60 fps) cadence averages 1.6 cs, which GIF can only express with 1 cs frames — and
   * browsers play 1 cs frames at ~100 ms. The documented floor is
   * [ScrollGifEncoder.MIN_FRAME_DELAY_MS] per frame: the GIF plays a uniform 50 fps, and an
   * exact-rate capture needs APNG.
   */
  @Test
  fun `sub-20ms intervals are floored at 2 cs per frame, never 0 or 1`() {
    for (intervalMs in listOf(0, 1, 10, 16, 19)) {
      val delays = ScrollGifEncoder.centisecondDelays(IntArray(60) { intervalMs })
      assertArrayEquals("$intervalMs ms", IntArray(60) { 2 }, delays)
    }
  }

  @Test
  fun `variable dwell sequences keep their total`() {
    val dwell = intArrayOf(1000) + IntArray(12) { 33 } + intArrayOf(1000)
    val delays = ScrollGifEncoder.centisecondDelays(dwell)
    assertEquals(100, delays.first())
    assertTrue(abs(delays.sum() * 10 - dwell.sum()) <= 5)
    assertNoUnplayableDelays("dwell", delays)
  }

  /** The delays must reach the file, not just the helper: read them back out of the GCE bytes. */
  @Test
  fun `the encoded file carries the distributed delays`() {
    val out = tmp.newFile("timed.gif")
    ScrollGifEncoder.encode(frames = frames(6), outputFile = out, frameDelayMs = 33)
    assertEquals(listOf(3, 4, 3, 3, 4, 3), delayTimes(out))
  }

  private fun assertNoUnplayableDelays(label: String, delays: IntArray) {
    assertTrue(
      "$label: a 0 or 1 cs delay plays at ~100 ms: ${delays.toList()}",
      delays.all { it >= 2 },
    )
  }

  private fun frames(count: Int): List<BufferedImage> =
    (0 until count).map { i ->
      BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).also { img ->
        val g = img.createGraphics()
        g.color = Color(i * 40, 0, 0)
        g.fillRect(0, 0, 8, 8)
        g.dispose()
      }
    }

  /**
   * Every frame's `delayTime` from its Graphic Control Extension (`0x21 0xF9 0x04`, packed byte,
   * then the delay as a little-endian u16). These fixtures are tiny and synthetic, so scanning for
   * the introducer cannot hit colour-table data.
   */
  private fun delayTimes(gif: File): List<Int> {
    val bytes = gif.readBytes()
    return bytes.indices
      .filter { i ->
        i + 5 < bytes.size &&
          bytes[i] == 0x21.toByte() &&
          bytes[i + 1] == 0xF9.toByte() &&
          bytes[i + 2] == 0x04.toByte()
      }
      .map { i -> (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) }
  }
}
