package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode

/**
 * Minimal pure-JVM animated-GIF encoder. Used by [DesktopRecordingSession.encode] /
 * [AndroidRecordingSession.encode] to stitch the per-frame `frame-NNNNN.png` files the playback
 * loop writes into a single looping GIF.
 *
 * **Why first-class GIF.** APNG is the canonical recording artifact (pure-JVM [ApngEncoder]), but
 * GIF is what plays inline everywhere a human or agent looks at the result — chat clients and
 * GitHub's web comment renderer autoplay GIF but not APNG. MP4 / WEBM ([FfmpegEncoder]) give
 * smaller files but need a native `ffmpeg`. GIF shares APNG's "always available" property because
 * the JDK bundles a `javax.imageio` GIF writer plugin, so [DesktopHost.supportedRecordingFormats] /
 * [RobolectricHost.supportedRecordingFormats] advertise it unconditionally.
 *
 * **Frame source.** [encodeFromPngFrames] takes a list of PNG files (one per frame, all sharing the
 * same dimensions — guaranteed by the fixed-size raster surface the recording sessions write) and
 * writes them as a single looping GIF at `1000 / fps` ms per frame, with the centisecond rounding
 * distributed across frames (see [centisecondDelays]).
 *
 * Extracted from the original `TouchOverlayTestSupport.encodeFramesAsGif` test helper so the
 * recording surface ships GIF as a real encoder rather than a test-only artifact.
 *
 * **`ImageIO` boundary.** GIF read/write goes through `javax.imageio`, one of the sanctioned
 * `java.io.File` boundaries (see docs/AGENT_GUIDE.md "File/IO goes through Okio … except a hard
 * third-party boundary"). The encoder keeps `File` local to those calls.
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

    javax.imageio.stream.FileImageOutputStream(out).use { stream ->
      writer.output = stream
      val firstFrame =
        requireNotNull(ImageIO.read(frames[0])) {
          "GifEncoder: ImageIO.read returned null for ${frames[0].absolutePath}"
        }
      val imageType = ImageTypeSpecifier.createFromRenderedImage(firstFrame)
      val param = writer.defaultWriteParam
      val meta = writer.getDefaultImageMetadata(imageType, param)
      configureGifFrameMetadata(meta, delaysCs[0], loopForever = true)
      writer.prepareWriteSequence(null)
      writer.writeToSequence(IIOImage(firstFrame, null, meta), param)
      for (i in 1 until frames.size) {
        val frame =
          requireNotNull(ImageIO.read(frames[i])) {
            "GifEncoder: ImageIO.read returned null for ${frames[i].absolutePath}"
          }
        val frameMeta = writer.getDefaultImageMetadata(imageType, param)
        configureGifFrameMetadata(frameMeta, delaysCs[i], loopForever = false)
        writer.writeToSequence(IIOImage(frame, null, frameMeta), param)
      }
      writer.endWriteSequence()
    }
    writer.dispose()
  }

  /**
   * The per-frame GIF `delayTime`s (centiseconds) for [frameCount] frames at [fps], distributed so
   * the cumulative playback time tracks `n / fps` instead of each frame being rounded on its own.
   *
   * GIF delays are whole centiseconds. Truncating `1000 / fps` per frame — what this encoder used
   * to do — wrote 30 fps (33.3 ms) as 30 ms and 24 fps (41.7 ms) as 40 ms, so recordings played 11%
   * / 4% fast. Frame `i` instead gets `round((i + 1) × 100 / fps) - round(i × 100 / fps)`, computed
   * exactly in integers: 30 fps comes out `3, 4, 3, …`, which is exactly 1 s per 30 frames.
   *
   * **Minimum delay: 2 cs (20 ms).** Browsers play a `delayTime` of 0 or 1 cs at ~100 ms, so above
   * 50 fps — where the average would drop below 2 cs and the distribution would emit 1 cs frames —
   * every frame is written at 2 cs and the GIF plays at 50 fps. APNG keeps the exact rate. Same
   * policy as `ScrollGifEncoder.centisecondDelays` in `:data-scroll-core`, which daemon/core does
   * not depend on.
   */
  internal fun centisecondDelays(fps: Int, frameCount: Int): IntArray {
    if (fps > MAX_REPRESENTABLE_FPS) return IntArray(frameCount) { MIN_DELAY_CS }
    // round(n × 100 / fps), half up, in integers.
    fun cumulativeCs(n: Int): Long = (2L * n * 100 + fps) / (2L * fps)
    return IntArray(frameCount) { i -> (cumulativeCs(i + 1) - cumulativeCs(i)).toInt() }
  }

  private const val MIN_DELAY_CS = 2
  private const val MAX_REPRESENTABLE_FPS = 100 / MIN_DELAY_CS

  private fun configureGifFrameMetadata(meta: IIOMetadata, delayCs: Int, loopForever: Boolean) {
    val formatName = meta.nativeMetadataFormatName
    val root = meta.getAsTree(formatName) as IIOMetadataNode
    val gce = root.getOrCreateChild("GraphicControlExtension")
    gce.setAttribute("disposalMethod", "none")
    gce.setAttribute("userInputFlag", "FALSE")
    gce.setAttribute("transparentColorFlag", "FALSE")
    gce.setAttribute("delayTime", delayCs.toString())
    gce.setAttribute("transparentColorIndex", "0")
    if (loopForever) {
      // NETSCAPE2.0 application extension with loop count 0 (= infinite). Emitted once, on the
      // first
      // frame, per the de-facto looping-GIF convention.
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
