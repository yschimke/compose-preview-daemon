package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ArgbSnapshotTest {
  private val colors = intArrayOf(0x00123456, 0x01335577, 0x7fabcdef, 0xff987654.toInt())

  @Test
  fun `canonical ARGB preserves alpha and hidden transparent colors`() {
    val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
    assertSame(ColorModel.getRGBdefault(), image.colorModel)
    image.setRGB(0, 0, 4, 2, colors + colors.reversedArray(), 0, 4)
    assertArrayEquals(colors + colors.reversedArray(), snapshotArgb(image))
  }

  @Test
  fun `cropped raster honors offset stride and independent ownership`() {
    val parent = BufferedImage(11, 9, BufferedImage.TYPE_INT_ARGB)
    parent.setRGB(3, 4, 4, 1, colors, 0, 4)
    val child = parent.getSubimage(3, 4, 4, 2)
    val snapshot = snapshotArgb(child)
    assertArrayEquals(expected(child), snapshot)
    parent.setRGB(3, 4, 0xff000000.toInt())
    assertEquals(colors[0], snapshot[0])
  }

  @Test
  fun `other standard formats retain getRGB conversion`() {
    for (type in 1..13) {
      val image = BufferedImage(4, 2, type)
      image.setRGB(0, 0, 4, 2, colors + colors.reversedArray(), 0, 4)
      assertArrayEquals("image type $type", expected(image), snapshotArgb(image))
    }
  }

  @Test
  fun `premultiplication after construction retains conversion`() {
    val image = BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 4, 1, colors, 0, 4)
    image.coerceData(true)
    assertArrayEquals(expected(image), snapshotArgb(image))
  }

  @Test
  fun `subclass getRGB override is honored`() {
    val image =
      object : BufferedImage(4, 1, TYPE_INT_ARGB) {
        override fun getRGB(
          x: Int,
          y: Int,
          w: Int,
          h: Int,
          array: IntArray?,
          offset: Int,
          stride: Int,
        ): IntArray {
          val target = array ?: IntArray(w * h)
          colors.copyInto(target, offset)
          return target
        }
      }
    assertArrayEquals(colors, snapshotArgb(image))
  }

  private fun expected(image: BufferedImage) =
    image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
}
