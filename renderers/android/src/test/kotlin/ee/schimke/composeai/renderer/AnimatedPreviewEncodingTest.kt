package ee.schimke.composeai.renderer

import ee.schimke.composeai.motion.ApngDecoder
import ee.schimke.composeai.motion.ApngFrameDelay
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The container step of an Android `@AnimatedPreview` capture, without Robolectric: what
 * `previews.json` asks for, and what [encodeAnimatedFrames] writes for each format.
 * `AndroidAnimatedFrameTimingTest` drives the whole capture path for both containers.
 */
class AnimatedPreviewEncodingTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `a manifest without a format keeps the GIF it always rendered`() {
    val legacy =
      json.decodeFromString<AnimationCapture>("""{"durationMs":600,"frameIntervalMs":33}""")
    assertEquals(MotionFormat.GIF, legacy.format)

    val apng =
      json.decodeFromString<AnimationCapture>(
        """{"durationMs":600,"frameIntervalMs":33,"format":"APNG","caption":"x"}"""
      )
    assertEquals(MotionFormat.APNG, apng.format)
  }

  @Test
  fun `the GIF path is byte-for-byte the historical encode`() {
    val frames = frames(count = 6)
    val viaHelper = File(tmp.root, "helper.gif")
    val historical = File(tmp.root, "historical.gif")

    encodeAnimatedFrames(frames, viaHelper, MotionFormat.GIF, frameIntervalMs = 33)
    // Exactly what `handleAnimatedCapture` called before the format existed.
    ScrollGifEncoder.encode(
      frames = frames,
      outputFile = historical,
      frameDelaysMs = intArrayOf(500, 33, 33, 33, 33, 1000),
    )

    assertArrayEquals(historical.readBytes(), viaHelper.readBytes())
  }

  @Test
  fun `the APNG path keeps every frame, its pixels and its exact delay`() {
    val frames = frames(count = 6).toMutableList()
    frames[3] = frames[2] // a held frame must still be a frame
    val out = File(tmp.root, "anim.apng")

    encodeAnimatedFrames(frames, out, MotionFormat.APNG, frameIntervalMs = 16)

    val decoded = ApngDecoder.decode(out)
    assertEquals(frames.size, decoded.frames.size)
    assertEquals(
      listOf(ApngFrameDelay(1, 2)) + List(4) { ApngFrameDelay(1, 60) } + ApngFrameDelay(1, 1),
      decoded.frames.map { it.delay },
    )
    for (i in frames.indices) {
      assertArrayEquals("frame $i", pixels(frames[i]), pixels(decoded.frames[i].image))
    }
  }

  @Test
  fun `a single-frame capture is one held image in either container`() {
    assertArrayEquals(intArrayOf(500), animatedFrameDelaysMs(1, 33))
    val out = File(tmp.root, "one.apng")
    encodeAnimatedFrames(frames(count = 1), out, MotionFormat.APNG, frameIntervalMs = 33)
    assertEquals(listOf(ApngFrameDelay(1, 2)), ApngDecoder.decode(out).frames.map { it.delay })
  }

  private fun frames(count: Int): List<BufferedImage> {
    val random = Random(42)
    return List(count) { i ->
      BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) {
          for (x in 0 until width) {
            setRGB(x, y, if (x == i * 3) random.nextInt() else 0xFF336699.toInt())
          }
        }
      }
    }
  }

  private fun pixels(image: BufferedImage): IntArray =
    image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
}
