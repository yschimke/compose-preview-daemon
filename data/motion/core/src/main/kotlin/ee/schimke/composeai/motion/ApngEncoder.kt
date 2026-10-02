package ee.schimke.composeai.motion

import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Pure-JVM Animated PNG encoder that stores each frame as only the rectangle that changed.
 *
 * **Where it lives.** Both renderers need it and neither can see the other: the desktop backend
 * stitches Skiko's per-frame PNGs (`renderLottieApng`, the motion captures), and the Robolectric
 * backend stitches Roborazzi's (`handleInteractionCapture`). An `@InteractionPreview` is published
 * from whichever backend the module happens to build with, so one encoder means one component's
 * capture cannot differ in container bytes from its sibling's for no reason a reader could see.
 * `:daemon:core`'s `ApngEncoder` (the recording path) delegates here too; it used to be a copy only
 * because the renderers once had to avoid a `:daemon:core` dependency — this module depends on
 * nothing, so the reverse edge is free.
 *
 * **Why APNG.** These captures carry anti-aliased edges over transparency — a Lottie companion
 * against no background, a state layer mid-fade, a shape mid-morph. GIF's 1-bit alpha cannot hold
 * that edge (it churned run-to-run) and its 1/100 s delay quantisation cannot express 60 fps. APNG
 * is a standard PNG container with full 8-bit alpha and rational frame delays, so the RGBA frames
 * travel through unchanged at the rate they were authored for.
 *
 * **Changed-region frames.** Frame 0 is stored whole. Every later frame stores only the bounding
 * rectangle of the pixels whose ARGB value differs from the previous frame, with `dispose_op =
 * NONE` (the canvas keeps the previous frame) and `blend_op = SOURCE` (the rectangle replaces the
 * canvas, alpha included). Replacing rather than compositing is what keeps translucent pixels
 * exact: an `OVER` blend of a half-transparent pixel onto its predecessor would not reproduce it. A
 * frame identical to its predecessor is still written — as a 1×1 rectangle that repeats the pixel
 * already there — so the frame count and every frame's delay survive; the preview-diff bot compares
 * frame counts, and merging duplicates would read as a change.
 *
 * Decoded and composited per the [APNG spec](https://wiki.mozilla.org/APNG_Specification), every
 * output frame is pixel-identical to its input frame (non-premultiplied 8-bit ARGB); [ApngDecoder]
 * performs that composition and the tests assert it.
 *
 * **Pixel format.** 8-bit RGB (colour type 2) when every pixel of every frame is opaque, otherwise
 * 8-bit RGBA (colour type 6). Each scanline picks the PNG filter with the smallest sum of absolute
 * residuals (the libpng heuristic), and the stream is deflated at [DEFLATE_LEVEL]. Ancillary chunks
 * of the source frames (`sRGB`, `gAMA`, `pHYs`, text) are not carried, as before.
 */
object ApngEncoder {

  /**
   * zlib level for frame data. Level 9 over 6 buys ~1–3 % on these captures at roughly twice the
   * deflate time — still well under the time the frames took to render.
   */
  private const val DEFLATE_LEVEL = 9

  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 PNG\r\n SUB \n

  private const val COLOR_TYPE_RGB = 2
  private const val COLOR_TYPE_RGBA = 6
  private const val DISPOSE_OP_NONE: Byte = 0
  private const val BLEND_OP_SOURCE: Byte = 0

  /**
   * Stitch [frames] (PNG files of identical size) into a looping APNG at [out], each frame held for
   * [delayNumerator]/[delayDenominator] seconds; [loopCount] `0` = infinite.
   */
  fun encodeFromPngFrames(
    frames: List<File>,
    delayNumerator: Short,
    delayDenominator: Short,
    loopCount: Int,
    out: File,
  ) {
    val delay =
      ApngFrameDelay(delayNumerator.toInt() and 0xFFFF, delayDenominator.toInt() and 0xFFFF)
    encodeFromPngFrames(frames, List(frames.size) { delay }, loopCount, out)
  }

  /**
   * Stitch [frames] (PNG files of identical size) into a looping APNG at [out]; frame `i` is held
   * for `delays[i]`. [loopCount] `0` = infinite.
   */
  fun encodeFromPngFrames(
    frames: List<File>,
    delays: List<ApngFrameDelay>,
    loopCount: Int,
    out: File,
  ) {
    encode(
      frames =
        object : AbstractList<BufferedImage>() {
          override val size: Int
            get() = frames.size

          override fun get(index: Int): BufferedImage = readPng(frames[index])
        },
      delays = delays,
      loopCount = loopCount,
      out = out,
    )
  }

  /**
   * Encode [frames] (all the same size) as a looping APNG at [out]; frame `i` is held for
   * `delays[i]`. [loopCount] `0` = infinite.
   *
   * [frames] may be a lazy list — each element is read at most twice, in order (once to learn
   * whether any pixel is translucent, once to encode), and never more than two are held at once.
   */
  fun encode(
    frames: List<BufferedImage>,
    delays: List<ApngFrameDelay>,
    loopCount: Int,
    out: File,
  ) {
    out.parentFile?.mkdirs()
    BufferedOutputStream(out.outputStream()).use { encode(frames, delays, loopCount, it) }
  }

  /** As [encode] to a file, writing the APNG to [out] (not closed). */
  fun encode(
    frames: List<BufferedImage>,
    delays: List<ApngFrameDelay>,
    loopCount: Int,
    out: OutputStream,
  ) {
    require(frames.isNotEmpty()) { "ApngEncoder: at least one frame required" }
    require(delays.size == frames.size) {
      "ApngEncoder: ${delays.size} delays for ${frames.size} frames — one delay per frame"
    }
    require(loopCount >= 0) { "ApngEncoder: loopCount must be ≥ 0 (0 = infinite)" }

    // Pass 1: the canvas size, and whether any pixel anywhere is translucent (RGB vs RGBA).
    var width = -1
    var height = -1
    var hasAlpha = false
    for ((index, image) in frames.withIndex()) {
      if (index == 0) {
        width = image.width
        height = image.height
      } else {
        require(image.width == width && image.height == height) {
          "ApngEncoder: frame $index size ${image.width}x${image.height} does not match " +
            "frame 0 size ${width}x$height — frames must share one size"
        }
      }
      if (!hasAlpha && image.colorModel.hasAlpha()) hasAlpha = anyTranslucent(argb(image))
    }
    val colorType = if (hasAlpha) COLOR_TYPE_RGBA else COLOR_TYPE_RGB

    val stream = DataOutputStream(out)
    stream.write(PNG_SIGNATURE)
    writeChunk(
      stream,
      "IHDR",
      bytes(13) {
        writeInt(width)
        writeInt(height)
        writeByte(8) // bit depth
        writeByte(colorType)
        writeByte(0) // compression: deflate
        writeByte(0) // filter method: adaptive
        writeByte(0) // interlace: none
      },
    )
    writeChunk(
      stream,
      "acTL",
      bytes(8) {
        writeInt(frames.size)
        writeInt(loopCount)
      },
    )

    // Pass 2: frame 0 whole, then each later frame's changed rectangle against its predecessor.
    var sequence = 0
    var previous: IntArray? = null
    for ((index, image) in frames.withIndex()) {
      val current = argb(image)
      val region =
        previous?.let { changedRegion(it, current, width, height) } ?: Region(0, 0, width, height)
      writeChunk(
        stream,
        "fcTL",
        bytes(26) {
          writeInt(sequence++)
          writeInt(region.width)
          writeInt(region.height)
          writeInt(region.x)
          writeInt(region.y)
          writeShort(delays[index].numerator)
          writeShort(delays[index].denominator)
          writeByte(DISPOSE_OP_NONE.toInt())
          writeByte(BLEND_OP_SOURCE.toInt())
        },
      )
      val data = compress(current, width, region, hasAlpha)
      if (index == 0) {
        writeChunk(stream, "IDAT", data)
      } else {
        writeChunk(
          stream,
          "fdAT",
          bytes(4 + data.size) {
            writeInt(sequence++)
            write(data)
          },
        )
      }
      previous = current
    }
    writeChunk(stream, "IEND", ByteArray(0))
    stream.flush()
  }

  private data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

  /**
   * Bounding rectangle of the pixels that differ between [previous] and [current]; a 1×1 rectangle
   * at the origin when nothing does, so the frame still exists (see the class KDoc).
   */
  private fun changedRegion(
    previous: IntArray,
    current: IntArray,
    width: Int,
    height: Int,
  ): Region {
    var top = -1
    for (y in 0 until height) {
      if (!rowEqual(previous, current, y * width, width)) {
        top = y
        break
      }
    }
    if (top < 0) return Region(0, 0, 1, 1)
    var bottom = top
    for (y in height - 1 downTo top) {
      if (!rowEqual(previous, current, y * width, width)) {
        bottom = y
        break
      }
    }
    var left = width
    var right = -1
    for (y in top..bottom) {
      val row = y * width
      var x = 0
      while (x < left && previous[row + x] == current[row + x]) x++
      if (x < left) left = x
      x = width - 1
      while (x > right && previous[row + x] == current[row + x]) x--
      if (x > right) right = x
    }
    return Region(left, top, right - left + 1, bottom - top + 1)
  }

  private fun rowEqual(a: IntArray, b: IntArray, offset: Int, length: Int): Boolean =
    java.util.Arrays.equals(a, offset, offset + length, b, offset, offset + length)

  /** Filtered, deflated scanlines of [region] of the [stride]-wide ARGB [pixels]. */
  private fun compress(
    pixels: IntArray,
    stride: Int,
    region: Region,
    hasAlpha: Boolean,
  ): ByteArray {
    val bpp = if (hasAlpha) 4 else 3
    val rowBytes = region.width * bpp
    var prior = ByteArray(rowBytes)
    var raw = ByteArray(rowBytes)
    val candidates = Array(5) { ByteArray(rowBytes) }
    val deflated = ByteArrayOutputStream()
    val deflater = Deflater(DEFLATE_LEVEL)
    try {
      DeflaterOutputStream(deflated, deflater, 64 * 1024).use { z ->
        for (y in region.y until region.y + region.height) {
          var o = 0
          val rowStart = y * stride + region.x
          for (x in rowStart until rowStart + region.width) {
            val p = pixels[x]
            raw[o++] = (p ushr 16).toByte()
            raw[o++] = (p ushr 8).toByte()
            raw[o++] = p.toByte()
            if (hasAlpha) raw[o++] = (p ushr 24).toByte()
          }
          val filter = filterRow(raw, prior, bpp, candidates)
          z.write(filter)
          z.write(candidates[filter])
          val swap = prior
          prior = raw
          raw = swap
        }
      }
    } finally {
      deflater.end()
    }
    return deflated.toByteArray()
  }

  /**
   * Fill [out] with all five PNG filters of [raw] and return the one with the smallest sum of
   * absolute residuals (as signed bytes) — libpng's default heuristic.
   */
  private fun filterRow(raw: ByteArray, prior: ByteArray, bpp: Int, out: Array<ByteArray>): Int {
    val n = raw.size
    val none = out[0]
    val sub = out[1]
    val up = out[2]
    val avg = out[3]
    val paeth = out[4]
    val sums = LongArray(5)
    for (i in 0 until n) {
      val x = raw[i].toInt() and 0xFF
      val a = if (i >= bpp) raw[i - bpp].toInt() and 0xFF else 0
      val b = prior[i].toInt() and 0xFF
      val c = if (i >= bpp) prior[i - bpp].toInt() and 0xFF else 0
      none[i] = x.toByte()
      sub[i] = (x - a).toByte()
      up[i] = (x - b).toByte()
      avg[i] = (x - ((a + b) ushr 1)).toByte()
      paeth[i] = (x - paethPredictor(a, b, c)).toByte()
      sums[0] += kotlin.math.abs(none[i].toInt())
      sums[1] += kotlin.math.abs(sub[i].toInt())
      sums[2] += kotlin.math.abs(up[i].toInt())
      sums[3] += kotlin.math.abs(avg[i].toInt())
      sums[4] += kotlin.math.abs(paeth[i].toInt())
    }
    var best = 0
    for (f in 1 until 5) if (sums[f] < sums[best]) best = f
    return best
  }

  private fun paethPredictor(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
  }

  /** Non-premultiplied 8-bit ARGB of every pixel of [image], row-major. */
  private fun argb(image: BufferedImage): IntArray =
    image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

  private fun anyTranslucent(pixels: IntArray): Boolean = pixels.any { (it ushr 24) != 0xFF }

  private fun readPng(file: File): BufferedImage {
    val bytes = file.readBytes()
    for (i in PNG_SIGNATURE.indices) {
      require(bytes.size > i && bytes[i] == PNG_SIGNATURE[i]) {
        "ApngEncoder: ${file.absolutePath} is not a valid PNG (signature mismatch at byte $i)"
      }
    }
    return ImageIO.read(MemoryCacheImageInputStream(bytes.inputStream()))
      ?: throw IllegalArgumentException("ApngEncoder: ${file.absolutePath} failed to decode")
  }

  private inline fun bytes(size: Int, block: DataOutputStream.() -> Unit): ByteArray {
    val buffer = ByteArrayOutputStream(size)
    DataOutputStream(buffer).use(block)
    return buffer.toByteArray()
  }

  private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    out.writeInt(data.size)
    out.write(typeBytes)
    out.write(data)
    out.writeInt(crc.value.toInt())
  }
}
