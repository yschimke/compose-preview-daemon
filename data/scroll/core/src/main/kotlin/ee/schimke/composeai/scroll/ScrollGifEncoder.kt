package ee.schimke.composeai.scroll

import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Encodes same-sized frames as an infinitely looping animated GIF with the JDK's `ImageIO` writer.
 * Returns the written file, or `null` when [frames] is empty or no GIF writer is registered.
 */
object ScrollGifEncoder {
  const val DEFAULT_FRAME_DELAY_MS: Int = 80
  /**
   * The shortest per-frame delay written, in ms. 2 cs is the smallest GIF delay browsers honour (0
   * and 1 cs play at ~100 ms); see [centisecondDelays].
   */
  const val MIN_FRAME_DELAY_MS: Int = 20

  fun encode(
    frames: List<BufferedImage>,
    outputFile: File,
    frameDelayMs: Int = DEFAULT_FRAME_DELAY_MS,
  ): File? = encode(frames, outputFile, IntArray(frames.size) { frameDelayMs })

  /** Variable cadence: one entry of [frameDelaysMs] per frame (e.g. longer hold frames). */
  fun encode(frames: List<BufferedImage>, outputFile: File, frameDelaysMs: IntArray): File? {
    if (frames.isEmpty()) return null
    require(frameDelaysMs.size == frames.size) {
      "frameDelaysMs size ${frameDelaysMs.size} != frames size ${frames.size}"
    }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull() ?: return null

    val disposal = disposalMethodFor(frames)

    outputFile.parentFile?.mkdirs()
    // `FileImageOutputStream` does not truncate, so a shorter re-encode would keep the old tail.
    // Truncate rather than delete: it needs only the permission the write itself needs.
    RandomAccessFile(outputFile, "rw").use { it.setLength(0L) }
    try {
      FileImageOutputStream(outputFile).use { stream ->
        writer.output = stream
        val param: ImageWriteParam = writer.defaultWriteParam
        val first = frames.first()
        val imageType = ImageTypeSpecifier.createFromRenderedImage(first)
        val meta = writer.getDefaultImageMetadata(imageType, param)
        val delaysCs = centisecondDelays(frameDelaysMs)
        configureFrameMetadata(meta, delaysCs[0], disposal, loopForever = true)

        writer.prepareWriteSequence(null)
        writer.writeToSequence(IIOImage(first, null, meta), param)

        for (i in 1 until frames.size) {
          val frameMeta = writer.getDefaultImageMetadata(imageType, param)
          configureFrameMetadata(
            frameMeta,
            delaysCs[i],
            disposal,
            loopForever = false,
          )
          writer.writeToSequence(IIOImage(frames[i], null, frameMeta), param)
        }
        writer.endWriteSequence()
      }
    } finally {
      writer.dispose()
    }
    return outputFile
  }

  /**
   * Per-frame GIF delays in centiseconds, rounded on the cumulative time so playback stays within 5
   * ms of the captured timeline (33 ms frames become a 3/4 cs mix rather than all 3 cs).
   *
   * Inputs are first raised to [MIN_FRAME_DELAY_MS], because browsers play 0-1 cs delays at ~100
   * ms; faster cadences need APNG.
   */
  internal fun centisecondDelays(frameDelaysMs: IntArray): IntArray {
    var cumulativeMs = 0L
    var emittedCs = 0L
    return IntArray(frameDelaysMs.size) { i ->
      cumulativeMs += frameDelaysMs[i].coerceAtLeast(MIN_FRAME_DELAY_MS)
      // Round half up, in integers: (ms + 5) / 10 is round(ms / 10) for non-negative ms.
      val targetCs = (cumulativeMs + 5) / 10
      val delayCs = (targetCs - emittedCs).toInt()
      emittedCs = targetCs
      delayCs
    }
  }

  /**
   * `none` (cheap, flicker-free) only for opaque frames. With alpha, each frame would composite
   * over the previous one and smear the animation, so frames are cleared instead. (`ImageIO` sets
   * `transparentColorFlag` for alpha rasters regardless of what is requested.)
   */
  private fun disposalMethodFor(frames: List<BufferedImage>): String =
    if (frames.any { it.colorModel.hasAlpha() }) "restoreToBackgroundColor" else "none"

  /**
   * Writes the frame's `GraphicControlExtension` and, on the first frame, the `NETSCAPE2.0`
   * extension without which most viewers play once and stop.
   */
  private fun configureFrameMetadata(
    meta: IIOMetadata,
    delayCentiseconds: Int,
    disposalMethod: String,
    loopForever: Boolean,
  ) {
    val format = meta.nativeMetadataFormatName
    val root = meta.getAsTree(format) as IIOMetadataNode

    val gce = getOrCreateChild(root, "GraphicControlExtension")
    gce.setAttribute("disposalMethod", disposalMethod)
    gce.setAttribute("userInputFlag", "FALSE")
    gce.setAttribute("transparentColorFlag", "FALSE")
    gce.setAttribute("delayTime", delayCentiseconds.toString())
    gce.setAttribute("transparentColorIndex", "0")

    if (loopForever) {
      val appExts = getOrCreateChild(root, "ApplicationExtensions")
      val appExt = IIOMetadataNode("ApplicationExtension")
      appExt.setAttribute("applicationID", "NETSCAPE")
      appExt.setAttribute("authenticationCode", "2.0")
      // 3-byte sub-block: { 0x01, loopCount_lo, loopCount_hi } — 0 = forever.
      appExt.userObject = byteArrayOf(0x1, 0x0, 0x0)
      appExts.appendChild(appExt)
    }

    meta.setFromTree(format, root)
  }

  private fun getOrCreateChild(parent: IIOMetadataNode, name: String): IIOMetadataNode {
    var child = parent.firstChild
    while (child != null) {
      if (child.nodeName.equals(name, ignoreCase = true)) {
        return child as IIOMetadataNode
      }
      child = child.nextSibling
    }
    val created = IIOMetadataNode(name)
    parent.appendChild(created)
    return created
  }
}
