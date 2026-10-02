package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [GifEncoder] — the pure-JVM `javax.imageio` animated-GIF encoder promoted from the
 * touch-overlay test helper. Pins the always-available property the recording surface relies on:
 * given a directory of contiguous `frame-NNNNN.png` files it produces a single readable,
 * multi-frame GIF with no native dependency.
 */
class GifEncoderTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun writeFrame(dir: File, index: Int, color: Color, w: Int = 8, h: Int = 8): File {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, w, h)
    g.dispose()
    val file = File(dir, "frame-${"%05d".format(index)}.png")
    ImageIO.write(img, "png", file)
    return file
  }

  @Test
  fun encodes_contiguous_frames_into_a_readable_multi_frame_gif() {
    val framesDir = tmp.newFolder("frames")
    val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    val frames = colors.mapIndexed { i, c -> writeFrame(framesDir, i, c) }
    val out = File(tmp.newFolder("out"), "demo.gif")

    GifEncoder.encodeFromPngFrames(frames = frames, fps = 30, out = out)

    assertTrue("GIF file should exist", out.isFile)
    assertTrue("GIF should be non-empty", out.length() > 0)

    // Re-read the GIF and count the frames the imageio reader sees back out.
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(out).use { iis ->
      reader.input = iis
      assertEquals("GIF should round-trip every frame", colors.size, reader.getNumImages(true))
    }
    reader.dispose()
  }

  @Test
  fun single_frame_is_valid() {
    val framesDir = tmp.newFolder("frames")
    val frames = listOf(writeFrame(framesDir, 0, Color.MAGENTA))
    val out = File(tmp.newFolder("out"), "single.gif")

    GifEncoder.encodeFromPngFrames(frames = frames, fps = 24, out = out)

    assertTrue(out.isFile)
    assertTrue(out.length() > 0)
  }

  @Test
  fun empty_frame_list_is_rejected() {
    val out = File(tmp.newFolder("out"), "empty.gif")
    assertThrows(IllegalArgumentException::class.java) {
      GifEncoder.encodeFromPngFrames(frames = emptyList(), fps = 30, out = out)
    }
  }

  @Test
  fun out_of_range_fps_is_rejected() {
    val framesDir = tmp.newFolder("frames")
    val frames = listOf(writeFrame(framesDir, 0, Color.CYAN))
    val out = File(tmp.newFolder("out"), "badfps.gif")
    assertThrows(IllegalArgumentException::class.java) {
      GifEncoder.encodeFromPngFrames(frames = frames, fps = 0, out = out)
    }
  }

  @Test
  fun recording_delays_track_the_frame_rate_within_one_centisecond() {
    for (fps in listOf(30, 24, 20, 15, 10, 12, 25, 50, 1)) {
      for (frameCount in 1..120) {
        val delays = GifEncoder.centisecondDelays(fps, frameCount)
        val totalMs = delays.sum() * 10.0
        val expectedMs = frameCount * 1000.0 / fps
        assertTrue(
          "$fps fps × $frameCount frames: GIF total $totalMs ms vs captured $expectedMs ms",
          kotlin.math.abs(totalMs - expectedMs) <= 10.0,
        )
        assertTrue("$fps fps: ${delays.toList()}", delays.all { it >= 2 })
      }
    }
  }

  @Test
  fun thirty_fps_alternates_three_and_four_centiseconds() {
    // Truncating 1000 / 30 = 33 ms wrote 3 cs every frame: 30 frames played in 900 ms.
    val delays = GifEncoder.centisecondDelays(fps = 30, frameCount = 30)
    assertEquals(listOf(3, 4, 3), delays.take(3))
    assertEquals(100, delays.sum())
  }

  @Test
  fun above_fifty_fps_every_frame_is_floored_at_two_centiseconds() {
    // 0 and 1 cs delays play at ~100 ms in browsers; GIF cannot go faster than 50 fps reliably.
    for (fps in listOf(51, 60, 120)) {
      assertEquals(List(10) { 2 }, GifEncoder.centisecondDelays(fps, 10).toList())
    }
  }

  @Test
  fun encoded_file_carries_the_distributed_delays() {
    val framesDir = tmp.newFolder("frames")
    val frames = (0 until 6).map { writeFrame(framesDir, it, Color(it * 40, 0, 0)) }
    val out = File(tmp.newFolder("out"), "timed.gif")
    GifEncoder.encodeFromPngFrames(frames = frames, fps = 30, out = out)

    val bytes = out.readBytes()
    val delays =
      bytes.indices
        .filter { i ->
          i + 5 < bytes.size &&
            bytes[i] == 0x21.toByte() &&
            bytes[i + 1] == 0xF9.toByte() &&
            bytes[i + 2] == 0x04.toByte()
        }
        .map { i -> (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) }
    assertEquals(listOf(3, 4, 3, 3, 4, 3), delays)
  }
}
