package ee.schimke.composeai.motion

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Minimal pure-JVM Animated PNG decoder: returns every frame as the full canvas a viewer shows,
 * after applying each `fcTL`'s region, `dispose_op` and `blend_op` per the
 * [APNG spec](https://wiki.mozilla.org/APNG_Specification).
 *
 * The JDK's `ImageIO` reads only an APNG's default image, so a capture's later frames are otherwise
 * invisible to a JVM test or tool. This is the counterpart [ApngEncoder]'s round-trip tests are
 * written against, and what the renderers' capture tests use to look at frames; it decodes any
 * 8-bit APNG (each frame's data is rewrapped as a standalone PNG and handed to `ImageIO`, so every
 * colour type `ImageIO` reads is covered). A plain PNG decodes as one frame.
 */
object ApngDecoder {

  /** A decoded animation: the composited [frames], in order, each with its [ApngFrameDelay]. */
  data class Animation(
    val width: Int,
    val height: Int,
    /** `acTL.num_plays`; `0` = infinite. */
    val loopCount: Int,
    val frames: List<Frame>,
  )

  /**
   * One composited frame: [image] is the whole canvas after this frame was applied
   * (`TYPE_INT_ARGB`, non-premultiplied); [region] is the rectangle its `fcTL` covered.
   */
  data class Frame(val image: BufferedImage, val delay: ApngFrameDelay, val region: Rectangle)

  private const val DISPOSE_NONE = 0
  private const val DISPOSE_BACKGROUND = 1
  private const val DISPOSE_PREVIOUS = 2
  private const val BLEND_SOURCE = 0

  private class Chunk(val type: String, val data: ByteArray)

  private class Control(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val delay: ApngFrameDelay,
    val dispose: Int,
    val blend: Int,
  )

  fun decode(file: File): Animation = decode(file.readBytes())

  fun decode(bytes: ByteArray): Animation {
    val chunks = readChunks(bytes)
    val ihdr = chunks.firstOrNull { it.type == "IHDR" } ?: error("ApngDecoder: no IHDR")
    val header = ByteBuffer.wrap(ihdr.data)
    val width = header.int
    val height = header.int
    // Chunks every rewrapped frame needs to decode. Colour-space chunks are left out on purpose:
    // the frames are compared as stored values, not as colour-managed output.
    val shared = chunks.filter { it.type == "PLTE" || it.type == "tRNS" }
    val acTl = chunks.firstOrNull { it.type == "acTL" }
    if (acTl == null) {
      val image = decodeFrame(ihdr.data, width, height, shared, chunks.filter { it.type == "IDAT" })
      return Animation(
        width,
        height,
        loopCount = 0,
        frames = listOf(Frame(toArgb(image), ApngFrameDelay(0, 1), Rectangle(0, 0, width, height))),
      )
    }
    val loopCount =
      ByteBuffer.wrap(acTl.data).let {
        it.int
        it.int
      }

    // Group image data under the fcTL that precedes it. IDAT before any fcTL is a default image
    // that is not part of the animation, so it is skipped.
    val controls = mutableListOf<Control>()
    val data = mutableListOf<MutableList<ByteArray>>()
    for (chunk in chunks) {
      when (chunk.type) {
        "fcTL" -> {
          val b = ByteBuffer.wrap(chunk.data)
          b.int // sequence
          val w = b.int
          val h = b.int
          val x = b.int
          val y = b.int
          val num = b.short.toInt() and 0xFFFF
          val den = (b.short.toInt() and 0xFFFF).let { if (it == 0) 100 else it }
          controls +=
            Control(w, h, x, y, ApngFrameDelay(num, den), b.get().toInt(), b.get().toInt())
          data += mutableListOf<ByteArray>()
        }
        "IDAT" -> data.lastOrNull()?.add(chunk.data)
        "fdAT" -> data.last().add(chunk.data.copyOfRange(4, chunk.data.size))
      }
    }

    val canvas = IntArray(width * height)
    val frames = mutableListOf<Frame>()
    for ((index, control) in controls.withIndex()) {
      require(control.x + control.width <= width && control.y + control.height <= height) {
        "ApngDecoder: frame $index region exceeds the canvas"
      }
      val saved = if (control.dispose == DISPOSE_PREVIOUS) canvas.copyOf() else null
      val image =
        decodeFrame(
          ihdr.data,
          control.width,
          control.height,
          shared,
          data[index].map { Chunk("IDAT", it) },
        )
      val pixels = image.getRGB(0, 0, control.width, control.height, null, 0, control.width)
      for (row in 0 until control.height) {
        for (col in 0 until control.width) {
          val target = (control.y + row) * width + control.x + col
          val source = pixels[row * control.width + col]
          canvas[target] =
            if (control.blend == BLEND_SOURCE) source else over(source, canvas[target])
        }
      }
      val snapshot = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      snapshot.setRGB(0, 0, width, height, canvas, 0, width)
      frames +=
        Frame(
          snapshot,
          control.delay,
          Rectangle(control.x, control.y, control.width, control.height),
        )

      // Dispose before the next frame. PREVIOUS on the first frame acts as BACKGROUND.
      when {
        control.dispose == DISPOSE_BACKGROUND ||
          (control.dispose == DISPOSE_PREVIOUS && index == 0) ->
          for (row in 0 until control.height) {
            val start = (control.y + row) * width + control.x
            canvas.fill(0, start, start + control.width)
          }
        control.dispose == DISPOSE_PREVIOUS -> saved!!.copyInto(canvas)
        else -> check(control.dispose == DISPOSE_NONE) { "ApngDecoder: bad dispose_op" }
      }
    }
    return Animation(width, height, loopCount, frames)
  }

  /** Non-premultiplied source-over of [src] onto [dst], both 8-bit ARGB. */
  private fun over(src: Int, dst: Int): Int {
    val sa = src ushr 24
    if (sa == 0xFF) return src
    if (sa == 0) return dst
    val da = dst ushr 24
    val outA = sa + da * (255 - sa) / 255
    if (outA == 0) return 0
    fun channel(shift: Int): Int {
      val s = (src ushr shift) and 0xFF
      val d = (dst ushr shift) and 0xFF
      return (s * sa + d * da * (255 - sa) / 255) / outA
    }
    return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  private fun toArgb(image: BufferedImage): BufferedImage {
    val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    out.setRGB(
      0,
      0,
      image.width,
      image.height,
      image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
      0,
      image.width,
    )
    return out
  }

  /** Rewrap one frame's data as a standalone PNG of [width]×[height] and decode it. */
  private fun decodeFrame(
    ihdr: ByteArray,
    width: Int,
    height: Int,
    shared: List<Chunk>,
    data: List<Chunk>,
  ): BufferedImage {
    val png = ByteArrayOutputStream()
    val out = DataOutputStream(png)
    out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    val header = ihdr.copyOf()
    ByteBuffer.wrap(header).putInt(width).putInt(height)
    writeChunk(out, "IHDR", header)
    shared.forEach { writeChunk(out, it.type, it.data) }
    data.forEach { writeChunk(out, "IDAT", it.data) }
    writeChunk(out, "IEND", ByteArray(0))
    return ImageIO.read(MemoryCacheImageInputStream(png.toByteArray().inputStream()))
      ?: error("ApngDecoder: frame failed to decode")
  }

  private fun readChunks(bytes: ByteArray): List<Chunk> {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    require(bytes.size >= 8 && signature.indices.all { bytes[it] == signature[it] }) {
      "ApngDecoder: not a PNG"
    }
    val buffer = ByteBuffer.wrap(bytes)
    buffer.position(8)
    val chunks = mutableListOf<Chunk>()
    while (buffer.remaining() >= 12) {
      val length = buffer.int
      val type = ByteArray(4).also { buffer.get(it) }
      val data = ByteArray(length).also { buffer.get(it) }
      val crc =
        CRC32().apply {
          update(type)
          update(data)
        }
      check(buffer.int == crc.value.toInt()) {
        "ApngDecoder: CRC mismatch in ${String(type, Charsets.US_ASCII)}"
      }
      val name = String(type, Charsets.US_ASCII)
      chunks += Chunk(name, data)
      if (name == "IEND") break
    }
    return chunks
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
