package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode

/**
 * Pure-JVM animated-GIF encoder for recording frames. Always available (the JDK ships the writer)
 * and, unlike APNG, autoplays inline in chat clients and GitHub comments. `java.io.File` is used
 * only at the `ImageIO` boundary.
 */
public object GifEncoder {

  /**
   * Encode [frames] (PNG files, contiguous, sharing one size) into a looping GIF at [fps] frames
   * per second, writing to [out]. Throws when [frames] is empty or a frame can't be read.
   */
  public fun encodeFromPngFrames(frames: List<File>, fps: Int, out: File) {
    require(frames.isNotEmpty()) { "GifEncoder: at least one frame required" }
    require(fps in 1..120) { "GifEncoder: fps=$fps out of range [1, 120]" }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("GifEncoder: no GIF writer plugin registered in this JVM")

    out.parentFile?.mkdirs()
    if (out.exists()) out.delete()

    val delaysCs = centisecondDelays(fps, frames.size)

    try {
      javax.imageio.stream.FileImageOutputStream(out).use { stream ->
        writer.output = stream
        val firstFrame =
          requireNotNull(ImageIO.read(frames[0])) {
            "GifEncoder: ImageIO.read returned null for ${frames[0].absolutePath}"
          }
        val imageType = ImageTypeSpecifier.createFromRenderedImage(firstFrame)
        val param = writer.defaultWriteParam
        val meta = writer.getDefaultImageMetadata(imageType, param)
        // Recording frames share one raster type, so the first frame decides for all of them.
        val disposal = disposalMethodFor(firstFrame.colorModel.hasAlpha())
        configureGifFrameMetadata(meta, delaysCs[0], disposal, loopForever = true)
        writer.prepareWriteSequence(null)
        writer.writeToSequence(IIOImage(firstFrame, null, meta), param)
        for (i in 1 until frames.size) {
          val frame =
            requireNotNull(ImageIO.read(frames[i])) {
              "GifEncoder: ImageIO.read returned null for ${frames[i].absolutePath}"
            }
          val frameMeta = writer.getDefaultImageMetadata(imageType, param)
          configureGifFrameMetadata(frameMeta, delaysCs[i], disposal, loopForever = false)
          writer.writeToSequence(IIOImage(frame, null, frameMeta), param)
        }
        writer.endWriteSequence()
      }
    } finally {
      writer.dispose()
    }
  }

  /**
   * Per-frame delays in centiseconds, rounded on the cumulative time `n / fps`, so 30 fps
   * alternates 3 and 4 cs. Above 50 fps every frame is 2 cs, because browsers play 0-1 cs at ~100
   * ms. Same policy as `ScrollGifEncoder.centisecondDelays`, which this module cannot depend on.
   */
  internal fun centisecondDelays(fps: Int, frameCount: Int): IntArray {
    if (fps > MAX_REPRESENTABLE_FPS) return IntArray(frameCount) { MIN_DELAY_CS }
    // round(n × 100 / fps), half up, in integers.
    fun cumulativeCs(n: Int): Long = (2L * n * 100 + fps) / (2L * fps)
    return IntArray(frameCount) { i -> (cumulativeCs(i + 1) - cumulativeCs(i)).toInt() }
  }

  private const val MIN_DELAY_CS = 2
  private const val MAX_REPRESENTABLE_FPS = 100 / MIN_DELAY_CS

  /**
   * `none` only for opaque frames: with alpha (a `showBackground = false` preview), each frame
   * would composite over the previous one and smear. Same rule as `ScrollGifEncoder`.
   */
  private fun disposalMethodFor(hasAlpha: Boolean): String =
    if (hasAlpha) "restoreToBackgroundColor" else "none"

  private fun configureGifFrameMetadata(
    meta: IIOMetadata,
    delayCs: Int,
    disposalMethod: String,
    loopForever: Boolean,
  ) {
    val formatName = meta.nativeMetadataFormatName
    val root = meta.getAsTree(formatName) as IIOMetadataNode
    val gce = root.getOrCreateChild("GraphicControlExtension")
    gce.setAttribute("disposalMethod", disposalMethod)
    gce.setAttribute("userInputFlag", "FALSE")
    gce.setAttribute("transparentColorFlag", "FALSE")
    gce.setAttribute("delayTime", delayCs.toString())
    gce.setAttribute("transparentColorIndex", "0")
    if (loopForever) {
      // NETSCAPE2.0 extension, loop count 0 (forever), on the first frame only.
      val appExts = root.getOrCreateChild("ApplicationExtensions")
      val appExt = IIOMetadataNode("ApplicationExtension")
      appExt.setAttribute("applicationID", "NETSCAPE")
      appExt.setAttribute("authenticationCode", "2.0")
      appExt.userObject = byteArrayOf(0x1, 0x0, 0x0)
      appExts.appendChild(appExt)
    }
    meta.setFromTree(formatName, root)
  }

  private fun IIOMetadataNode.getOrCreateChild(name: String): IIOMetadataNode {
    var child = firstChild
    while (child != null) {
      if (child.nodeName.equals(name, ignoreCase = true)) return child as IIOMetadataNode
      child = child.nextSibling
    }
    val created = IIOMetadataNode(name)
    appendChild(created)
    return created
  }
}
