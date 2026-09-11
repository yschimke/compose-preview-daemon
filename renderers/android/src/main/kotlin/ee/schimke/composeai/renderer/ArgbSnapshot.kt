package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.awt.image.ColorModel

/** Copies a frame without converting pixels that already use the default ARGB model. */
internal fun snapshotArgb(image: BufferedImage): IntArray = snapshotArgb(image, null)

/** Reuses a matching caller-owned buffer for standard BufferedImage implementations. */
internal fun snapshotArgb(image: BufferedImage, reusablePixels: IntArray?): IntArray {
  val size = image.width * image.height
  // Preserve fresh output arrays for arbitrary getRGB overrides, as in the original path.
  val pixels =
    reusablePixels?.takeIf { it.size == size && image.javaClass == BufferedImage::class.java }
      ?: IntArray(size)
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
