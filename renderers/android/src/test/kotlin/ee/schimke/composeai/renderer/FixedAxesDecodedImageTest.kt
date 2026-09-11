package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixedAxesDecodedImageTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun image(): File =
    temporary.newFile().also { file ->
      val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
      image.setRGB(0, 0, 0xffff0000.toInt())
      image.setRGB(1, 0, 0xff00ff00.toInt())
      image.setRGB(0, 1, 0xff0000ff.toInt())
      image.setRGB(1, 1, 0xffffffff.toInt())
      ImageIO.write(image, "png", file)
    }

  @Test
  fun `supplied image preserves exact correction pixels without rereading the file`() {
    val file = image()
    val decoded = ImageIO.read(file)
    file.writeBytes(byteArrayOf())
    resizeFixedAxesPng(file, 4, 3, decodedImage = decoded)
    val corrected = ImageIO.read(file)
    assertEquals(4, corrected.width)
    assertEquals(3, corrected.height)
    assertEquals(0xffff0000.toInt(), corrected.getRGB(0, 0))
    assertEquals(0xff00ff00.toInt(), corrected.getRGB(3, 0))
    assertEquals(0xff0000ff.toInt(), corrected.getRGB(0, 2))
    assertEquals(0xffffffff.toInt(), corrected.getRGB(3, 2))
  }

  @Test
  fun `supplied and decoded paths produce identical PNGs for grow crop and one-axis correction`() {
    for ((width, height) in listOf(4 to 3, 1 to 1, 1 to null, null to 3)) {
      val baseline = image()
      val candidate = image()
      resizeFixedAxesPng(baseline, width, height)
      resizeFixedAxesPng(candidate, width, height, ImageIO.read(candidate))
      assertArrayEquals(baseline.readBytes(), candidate.readBytes())
    }
  }

  @Test
  fun `already correct images retain their encoded bytes`() {
    val file = image()
    val bytes = file.readBytes()
    resizeFixedAxesPng(file, 2, 2, ImageIO.read(file))
    assertArrayEquals(bytes, file.readBytes())
  }

  @Test
  fun `without a supplied image corrupt input still fails decoding`() {
    val file = image()
    file.writeBytes(file.readBytes().copyOf(20))
    assertThrows(javax.imageio.IIOException::class.java) { resizeFixedAxesPng(file, 2, 2) }
  }
}
