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
 * Encodes a sequence of same-sized `BufferedImage` frames as an animated GIF at [outputFile],
 * looping forever at [frameDelayMs] per frame. GIF delays are whole centiseconds, so the rounding
 * is distributed across frames to keep the total playback time on the captured timeline — see
 * [centisecondDelays].
 *
 * Built on `javax.imageio`'s standard GIF writer plugin — no extra deps. Two GIF-specific knobs are
 * driven through the metadata tree that `ImageWriter` exposes:
 *
 * - `GraphicControlExtension` per frame carries the `delayTime` (hundredths of a second) and a
 *   disposal method chosen from whether the frames carry alpha — see [disposalMethodFor].
 * - One `ApplicationExtensions / NETSCAPE2.0` record on the first frame signals infinite looping
 *   (`loopCount=0`). Without it most viewers play once and stop.
 *
 * GIF's palette is 256 colours per frame; for the UI-scroll case (flat colours, anti-aliased text)
 * the default `ImageIO` quantiser produces acceptable output. If we ever need higher fidelity,
 * NeuQuant / octree dithering sits behind the same metadata plumbing.
 *
 * [ScrollMode.GIF] captures call this with one BufferedImage per scroll step. Returns the written
 * file, or `null` if [frames] is empty or the GIF writer plugin isn't registered (never, on a
 * standard JRE).
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

  /**
   * Variable per-frame cadence: [frameDelaysMs] must have one entry per image in [frames]. Used by
   * the scripted `ScrollMode.GIF` walk to give hold-start / hold-end frames a longer dwell (e.g.
   * 1000ms) than the in-motion scroll frames (80ms) within a single GIF. Each frame's GCE already
   * gets its own `delayTime` attribute, so variable delay is just a matter of plumbing the
   * per-frame value through.
   */
  fun encode(frames: List<BufferedImage>, outputFile: File, frameDelaysMs: IntArray): File? {
    if (frames.isEmpty()) return null
    require(frameDelaysMs.size == frames.size) {
      "frameDelaysMs size ${frameDelaysMs.size} != frames size ${frames.size}"
    }
    val writer =
      ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull() ?: return null

    val disposal = disposalMethodFor(frames)

    outputFile.parentFile?.mkdirs()
    // Truncate, rather than write over whatever is there. `FileImageOutputStream` opens a
    // `RandomAccessFile` in "rw" mode, which does NOT truncate: re-encoding a shorter sequence into
    // an existing longer file leaves the previous encode's tail past the GIF trailer. Decoders stop
    // at the trailer and show the right animation, so the only symptom is a file whose LENGTH is
    // the high-water mark of every render that ever wrote it — measured on `wear-m3-catalog`'s
    // placeholder recordings, a 28-frame re-render of a 46-frame capture came out byte-for-byte the
    // same size as the 46-frame one, carrying 62KB of the old render inside it. That makes the
    // artifact a function of the build directory's history rather than of its frames, which costs
    // reproducibility and quietly misleads any byte-level comparison of two renders.
    //
    // `setLength(0)` rather than `delete()`, because the two need different permissions and only
    // one of them matches what the write itself needs. Unlinking needs write+execute on the
    // PARENT DIRECTORY; truncating and writing need write on the FILE. So in a directory that
    // does not permit unlinking — a sticky `/tmp`, a read-only output dir holding a writable file
    // — `delete()` returns `false`, nothing checks it, `FileImageOutputStream` opens and
    // overwrites anyway, and the stale tail survives the fix that was supposed to remove it.
    // Truncating in place cannot fail where the encode below would succeed, and it throws rather
    // than returning a boolean, so a genuine permission problem surfaces instead of being encoded
    // into the artifact.
    RandomAccessFile(outputFile, "rw").use { it.setLength(0L) }
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
    writer.dispose()
    return outputFile
  }

  /**
   * The per-frame GIF `delayTime`s (centiseconds) for [frameDelaysMs], chosen so the GIF's
   * **cumulative** playback time tracks the captured timeline rather than each frame being rounded
   * on its own.
   *
   * GIF stores delays in 1/100 s. Truncating every frame independently — what this encoder used to
   * do — writes a 33 ms frame as 30 ms, so a default-interval (`33 ms`) capture played ~10% fast
   * and the error grew with every frame. Instead the exact milliseconds are accumulated and frame
   * `i` gets `round(cumulative_i / 10) - round(cumulative_{i-1} / 10)`: 33 ms frames come out as a
   * mix of 3 and 4 cs averaging 33 ms, and the running total is never more than 5 ms (half a
   * centisecond) away from the exact one. Intervals that are whole centiseconds (50, 80, 100 ms)
   * encode exactly as before.
   *
   * **Minimum delay: [MIN_FRAME_DELAY_MS] (2 cs).** Browsers treat a `delayTime` of 0 or 1 cs as
   * "unspecified" and play it at ~100 ms, so a frame meant to be fast would become the slowest in
   * the GIF. Every input below 20 ms is therefore raised to 20 ms *before* accumulation; with every
   * input ≥ 2 cs the rounded running total advances by ≥ 2 cs per frame, so no 0 or 1 cs frame can
   * be emitted. The cost is that a sub-20 ms cadence (a 16 ms / 60 fps capture) cannot be
   * represented: it plays at a uniform 20 ms / 50 fps. Use APNG for those (`MotionFormat.Apng`),
   * whose rational delays stay exact.
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
   * The disposal method the whole sequence is written with, decided by whether [frames] carry an
   * alpha channel.
   *
   * **`none` is only correct for opaque frames, and picking it for translucent ones smears the
   * recording.** `none` means "leave this frame on the canvas"; the next frame is then composited
   * over it, and wherever that next frame is TRANSPARENT the previous one shows through. Opaque
   * frames paint over every pixel, so nothing shows through and `none` is the cheap, flicker-free
   * choice — which is the scroll case this encoder was written for.
   *
   * A motion capture of a component sticker is the other case, and it is now the common one:
   * `@Preview(showBackground = false)` renders on transparency by design, so everything outside the
   * component's silhouette is see-through. With `none`, every silhouette the animation has ever
   * drawn stays on the canvas — a morphing shape accumulates its own outlines and a travelling
   * indicator leaves a trail. Measured on `wear-m3-catalog`'s media transport recording (a
   * scalloped play/pause button morphing against a circle): 47,609 opaque pixels on the first frame
   * and 50,780 by the last, a smear that grows monotonically because nothing ever clears.
   *
   * `restoreToBackgroundColor` clears each frame's area before the next is drawn, so a translucent
   * frame stands alone. The same recording holds flat at ~47,600 opaque pixels across all 61.
   *
   * Note the flag this cannot control: `transparentColorFlag` is requested `FALSE` below and the
   * `ImageIO` GIF writer sets it anyway when the incoming raster has alpha — it has to, to have an
   * index to put those pixels in. That is why the bug existed at all, and why the fix is disposal
   * rather than transparency.
   */
  private fun disposalMethodFor(frames: List<BufferedImage>): String =
    if (frames.any { it.colorModel.hasAlpha() }) "restoreToBackgroundColor" else "none"

  /**
   * Writes the per-frame `GraphicControlExtension` (delay + disposal) and, on the first frame only,
   * the `ApplicationExtensions / NETSCAPE2.0` sub-block that switches on infinite looping.
   *
   * `IIOMetadata` is navigated through `javax_imageio_gif_image_1.0`'s tree shape — the names and
   * attribute keys here are what `GIFImageMetadata` declares, not invented by us.
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
