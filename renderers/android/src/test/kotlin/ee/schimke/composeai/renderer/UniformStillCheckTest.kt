package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniformStillCheckTest {

  @Test
  fun `a flat frame reports its colour`() {
    val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 3) for (x in 0 until 4) image.setRGB(x, y, 0xFFFAFAFA.toInt())
    assertEquals(0xFFFAFAFA.toInt(), UniformStillCheck.uniformColour(image))
  }

  @Test
  fun `one differing pixel is not uniform`() {
    val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 3) for (x in 0 until 4) image.setRGB(x, y, 0xFFFAFAFA.toInt())
    image.setRGB(3, 2, 0xFFFF0000.toInt())
    assertNull(UniformStillCheck.uniformColour(image))
  }
}
