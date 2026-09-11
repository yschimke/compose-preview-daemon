package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SettlingPixelBufferTest {
  @Test
  fun `reused mutable capture image still detects every alternating frame`() {
    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
    var captures = 0
    var advances = 0
    var finalPixels: IntArray? = null
    val outcome =
      sampleVisuallySettledFrames(
        advanceFrame = { advances++ },
        onFinalDecodedFrame = { _, pixels -> finalPixels = pixels },
      ) {
        val color = if (captures++ % 2 == 0) 0x00123456 else 0xffabcdef.toInt()
        image.setRGB(0, 0, 3, 2, IntArray(6) { color }, 0, 3)
        image
      }

    assertEquals(VisualSettleOutcome.STILL_CHANGING, outcome)
    assertEquals(VISUAL_SETTLE_MAX_SAMPLES, captures)
    assertEquals(captures - 1, advances)
    val expected = image.getRGB(0, 0, 3, 2, null, 0, 3)
    assertArrayEquals(expected, finalPixels)
    image.setRGB(0, 0, 0)
    assertArrayEquals(expected, finalPixels)

    // A subsequent settling operation cannot overwrite the already delivered snapshot.
    sampleVisuallySettledFrames({}, { _, _ -> }) { image }
    assertArrayEquals(expected, finalPixels)
  }

  @Test
  fun `dimension changes reset stability even when pixel counts match`() {
    val dimensions = listOf(1 to 1, 3 to 2, 2 to 3, 2 to 3, 2 to 3)
    var captures = 0
    var finalImage: BufferedImage? = null
    var finalPixels: IntArray? = null
    val outcome =
      sampleVisuallySettledFrames(
        advanceFrame = {},
        onFinalDecodedFrame = { image, pixels ->
          finalImage = image
          finalPixels = pixels
        },
      ) {
        val (width, height) = dimensions[captures++]
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      }

    assertEquals(VisualSettleOutcome.SETTLED, outcome)
    assertEquals(5, captures)
    assertEquals(2, finalImage!!.width)
    assertEquals(3, finalImage!!.height)
    assertArrayEquals(IntArray(6), finalPixels)
  }
}
