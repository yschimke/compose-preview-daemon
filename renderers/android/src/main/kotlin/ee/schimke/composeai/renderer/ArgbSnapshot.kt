package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.awt.image.ColorModel

/** Copies a frame without converting pixels that already use the default ARGB model. */
internal fun snapshotArgb(image: BufferedImage): IntArray {
  val pixels = IntArray(image.width * image.height)
  if (
    image.javaClass == BufferedImage::class.java &&
      image.type == BufferedImage.TYPE_INT_ARGB &&
      image.colorModel === ColorModel.getRGBdefault() &&
      !image.isAlphaPremultiplied
  ) {
    // The rectangular raster API handles subimage offsets and scanline stride. Do not expose
    // its backing array: settling must retain an independent snapshot across later captures.
    image.raster.getDataElements(0, 0, image.width, image.height, pixels)
  } else {
    // Preserve conversion and overridden getRGB behavior for all other image implementations.
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
  }
  return pixels
}
