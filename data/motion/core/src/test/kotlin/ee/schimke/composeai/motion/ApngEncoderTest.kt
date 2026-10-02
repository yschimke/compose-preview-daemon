package ee.schimke.composeai.motion

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApngEncoderTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `translucent frames round-trip pixel-exact, duplicates included`() {
    val random = Random(7)
    val base = image(40, 30) { _, _ -> random.nextInt() } // every alpha value, any RGB
    val frames = mutableListOf(base)
    // A change in one corner, a change in the opposite corner, a duplicate, a translucent wash.
    frames += frames.last().edited { x, y, p -> if (x < 3 && y < 2) 0x80FF0000.toInt() else p }
    frames += frames.last().edited { x, y, p -> if (x == 39 && y == 29) 0x00123456 else p }
    frames += frames.last().copy()
    frames += frames.last().copy()
    frames += frames.last().edited { x, y, p -> if (y in 10..12) 0x40000000 or (x * 5) else p }
    frames += image(40, 30) { _, _ -> 0 } // fully transparent, RGB zero
    frames += image(40, 30) { _, _ -> 0x00FFFFFF } // fully transparent, RGB non-zero
    val delays = frames.indices.map { ApngFrameDelay.ofMillis(10 + it * 7) }

    val decoded = ApngDecoder.decode(encode(frames, delays))

    assertEquals(frames.size, decoded.frames.size)
    assertEquals(0, decoded.loopCount)
    for (i in frames.indices) {
      assertArrayEquals("frame $i pixels", pixels(frames[i]), pixels(decoded.frames[i].image))
      assertEquals("frame $i delay", delays[i], decoded.frames[i].delay)
    }
    assertEquals(colorTypeRgba, colorType(encode(frames, delays)))
  }

  @Test
  fun `each later frame stores only the rectangle that changed`() {
    val first = image(20, 10) { _, _ -> 0xFF0000FF.toInt() }
    val second = first.edited { x, y, p -> if (x in 4..6 && y in 2..7) 0xFFFF0000.toInt() else p }
    val third = second.edited { x, y, p ->
      if ((x == 1 && y == 1) || (x == 18 && y == 3)) -1 else p
    }

    val decoded =
      ApngDecoder.decode(
        encode(listOf(first, second, third, third.copy()), List(4) { ApngFrameDelay(1, 30) })
      )

    assertEquals(
      listOf(
        Rectangle(0, 0, 20, 10),
        Rectangle(4, 2, 3, 6),
        Rectangle(1, 1, 18, 3),
        // An unchanged frame is still a frame: 1×1, so the count and its delay survive.
        Rectangle(0, 0, 1, 1),
      ),
      decoded.frames.map { it.region },
    )
  }

  @Test
  fun `opaque frames are written as RGB and round-trip exactly`() {
    val random = Random(11)
    val frames = List(5) { image(17, 9) { _, _ -> random.nextInt() or 0xFF000000.toInt() } }
    val bytes = encode(frames, List(5) { ApngFrameDelay.ofMillis(40) })

    assertEquals(colorTypeRgb, colorType(bytes))
    val decoded = ApngDecoder.decode(bytes)
    for (i in frames.indices) {
      assertArrayEquals("frame $i", pixels(frames[i]), pixels(decoded.frames[i].image))
    }
  }

  @Test
  fun `per-frame delays are exact rationals`() {
    assertEquals(ApngFrameDelay(1, 2), ApngFrameDelay.ofMillis(500))
    assertEquals(ApngFrameDelay(1, 1), ApngFrameDelay.ofMillis(1000))
    assertEquals(ApngFrameDelay(2, 125), ApngFrameDelay.ofMillis(16))
    assertEquals(ApngFrameDelay(33, 1000), ApngFrameDelay.ofMillis(33))
    assertEquals(ApngFrameDelay(0, 1), ApngFrameDelay.ofMillis(0))
    assertEquals(ApngFrameDelay(1, 60), ApngFrameDelay.ofFrameInterval(16))
    assertEquals(ApngFrameDelay(1, 30), ApngFrameDelay.ofFrameInterval(33))

    // The Android @AnimatedPreview shape: a 500 ms hold, 16 ms steps, a 1000 ms hold.
    val delays =
      listOf(ApngFrameDelay.ofMillis(500)) +
        List(3) { ApngFrameDelay.ofFrameInterval(16) } +
        ApngFrameDelay.ofMillis(1000)
    val frames = List(delays.size) { i -> image(4, 4) { _, _ -> 0xFF000000.toInt() or i } }
    val decoded = ApngDecoder.decode(encode(frames, delays))
    assertEquals(delays, decoded.frames.map { it.delay })
    assertEquals(500.0 + 3 * 1000.0 / 60 + 1000.0, decoded.frames.sumOf { it.delay.millis }, 1e-9)
  }

  @Test
  fun `the uniform file API keeps its delay and round-trips PNG files`() {
    val random = Random(3)
    val images = List(4) { image(12, 12) { _, _ -> random.nextInt() } }
    val files = images.mapIndexed { i, image ->
      File(tmp.root, "frame-$i.png").also { ImageIO.write(image, "PNG", it) }
    }
    val out = File(tmp.root, "out/anim.apng")

    ApngEncoder.encodeFromPngFrames(files, 1.toShort(), 60.toShort(), loopCount = 3, out = out)

    val decoded = ApngDecoder.decode(out)
    assertEquals(3, decoded.loopCount)
    assertEquals(List(4) { ApngFrameDelay(1, 60) }, decoded.frames.map { it.delay })
    for (i in images.indices) {
      assertArrayEquals("frame $i", pixels(images[i]), pixels(decoded.frames[i].image))
    }
    // The first frame is the PNG default image, so a non-APNG viewer still shows frame 0.
    assertArrayEquals(pixels(images[0]), pixels(ImageIO.read(out)))
  }

  @Test
  fun `the chunk stream is well formed and deterministic`() {
    val random = Random(5)
    val frames = List(3) { image(8, 8) { _, _ -> random.nextInt() } }
    val delays = List(3) { ApngFrameDelay.ofMillis(100) }
    val bytes = encode(frames, delays)

    val types = chunkTypes(bytes)
    assertEquals(listOf("IHDR", "acTL", "fcTL", "IDAT"), types.take(4))
    assertEquals(listOf("fcTL", "fdAT", "fcTL", "fdAT", "IEND"), types.drop(4))
    assertArrayEquals(bytes, encode(frames, delays))
  }

  @Test
  fun `rejects malformed input`() {
    val a = image(8, 8) { _, _ -> 0 }
    val b = image(9, 8) { _, _ -> 0 }
    val delay = ApngFrameDelay(1, 30)
    assertThrows(IllegalArgumentException::class.java) { encode(emptyList(), emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { encode(listOf(a, b), List(2) { delay }) }
    assertThrows(IllegalArgumentException::class.java) { encode(listOf(a, a), listOf(delay)) }
    assertThrows(IllegalArgumentException::class.java) { ApngFrameDelay(1, 0) }
    assertThrows(IllegalArgumentException::class.java) { ApngFrameDelay.ofMillis(70_000) }
    val notPng = File(tmp.root, "not.png").apply { writeText("hello") }
    assertThrows(IllegalArgumentException::class.java) {
      ApngEncoder.encodeFromPngFrames(listOf(notPng), listOf(delay), 0, File(tmp.root, "x.apng"))
    }
  }

  private val colorTypeRgb = 2
  private val colorTypeRgba = 6

  private fun encode(frames: List<BufferedImage>, delays: List<ApngFrameDelay>): ByteArray =
    ByteArrayOutputStream().also { ApngEncoder.encode(frames, delays, 0, it) }.toByteArray()

  private fun image(w: Int, h: Int, argb: (Int, Int) -> Int): BufferedImage =
    BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
      for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb(x, y))
    }

  private fun BufferedImage.edited(f: (Int, Int, Int) -> Int): BufferedImage =
    image(width, height) { x, y -> f(x, y, getRGB(x, y)) }

  private fun BufferedImage.copy(): BufferedImage = edited { _, _, p -> p }

  private fun pixels(image: BufferedImage): IntArray =
    image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

  private fun colorType(bytes: ByteArray): Int = bytes[8 + 8 + 9].toInt()

  private fun chunkTypes(bytes: ByteArray): List<String> {
    val types = mutableListOf<String>()
    val buffer = ByteBuffer.wrap(bytes)
    buffer.position(8)
    while (buffer.remaining() >= 12) {
      val length = buffer.int
      val type = ByteArray(4).also { buffer.get(it) }
      buffer.position(buffer.position() + length + 4)
      types += String(type, Charsets.US_ASCII)
    }
    assertTrue(buffer.remaining() == 0)
    return types
  }
}
