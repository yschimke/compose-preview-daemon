package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FinalDecodedCaptureTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `all successful outcomes deliver only the final decoded frame`() {
    val cases =
      listOf(
        listOf(1) to VisualSettleOutcome.NEVER_CHANGED,
        listOf(1, 2, 2, 2) to VisualSettleOutcome.SETTLED,
        listOf(1, 2) to VisualSettleOutcome.STILL_CHANGING,
      )
    for ((colours, expected) in cases) {
      val file = temporary.newFile()
      var captures = 0
      var advances = 0
      var callbacks = 0
      var delivered: BufferedImage? = null
      val result =
        captureVisuallySettledFrame(
          file,
          "handoff",
          advanceFrame = { advances++ },
          onFinalDecodedFrame = {
            callbacks++
            delivered = it
          },
        ) { output ->
          val image = BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB)
          image.setRGB(0, 0, 0xff000000.toInt() or colours[captures++ % colours.size])
          ImageIO.write(image, "png", output)
        }
      assertEquals(expected, result)
      assertEquals(
        if (expected == VisualSettleOutcome.SETTLED) 4 else VISUAL_SETTLE_MAX_SAMPLES,
        captures,
      )
      assertEquals(captures - 1, advances)
      assertEquals(1, callbacks)
      assertEquals(5, delivered!!.width)
      assertEquals(4, delivered!!.height)
      assertEquals(ImageIO.read(file).getRGB(0, 0), delivered!!.getRGB(0, 0))
    }
  }

  @Test
  fun `dimension changes remain mismatches and deliver the final size`() {
    var captures = 0
    var delivered: BufferedImage? = null
    val result =
      captureVisuallySettledFrame(
        temporary.newFile(),
        "resize",
        advanceFrame = {},
        onFinalDecodedFrame = { delivered = it },
      ) { output ->
        val image = BufferedImage(if (captures++ == 0) 5 else 6, 4, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "png", output)
      }
    assertEquals(VisualSettleOutcome.SETTLED, result)
    assertEquals(4, captures)
    assertEquals(6, delivered!!.width)
  }

  @Test
  fun `decode failure does not deliver a frame`() {
    var callbacks = 0
    assertThrows(IllegalStateException::class.java) {
      captureVisuallySettledFrame(
        temporary.newFile(),
        "bad",
        advanceFrame = {},
        onFinalDecodedFrame = { callbacks++ },
      ) {
        it.writeBytes(byteArrayOf())
      }
    }
    assertEquals(0, callbacks)
  }
}
