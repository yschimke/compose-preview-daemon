package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.motion.ApngEncoder as SharedApngEncoder
import ee.schimke.composeai.motion.ApngFrameDelay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The recording path's Animated PNG encoder: assembles the per-frame PNGs a recording session's
 * playback loop writes ([DesktopRecordingSession.encode], `AndroidRecordingSession.encode`) into a
 * single looping APNG an agent, webview or browser can play back.
 *
 * A thin adapter over `:data-motion-core`'s [ee.schimke.composeai.motion.ApngEncoder] — the encoder
 * the renderers' `@InteractionPreview`, `@AnimatedPreview` and Lottie captures use — so a recording
 * and a capture of the same frames produce the same container. This used to be a separate copy: the
 * renderers had to avoid depending on `:daemon:core` (it shadowed their Lottie test fixtures), but
 * `:data-motion-core` depends on nothing, so this edge is free. What this adapter adds is reading
 * the frames through [fileSystem], as the rest of the recording path does.
 *
 * Each frame after the first is stored as only the rectangle that changed since the previous one,
 * and every input frame — duplicates included — stays a frame, so the frame count and timing are
 * the recording's. See the shared encoder for the wire shape.
 *
 * **Loop count.** `0` means "infinite" per APNG spec — that's the default.
 */
public object ApngEncoder {

  private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 PNG\r\n SUB \n

  public fun encodeFromPngFrames(
    frames: List<File>,
    delayNumerator: Short,
    delayDenominator: Short,
    loopCount: Int,
    out: File,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    require(frames.isNotEmpty()) { "ApngEncoder: at least one frame required" }
    require(delayDenominator > 0) { "ApngEncoder: delayDenominator must be > 0" }
    require(loopCount >= 0) { "ApngEncoder: loopCount must be ≥ 0 (0 = infinite)" }
    val delay =
      ApngFrameDelay(delayNumerator.toInt() and 0xFFFF, delayDenominator.toInt() and 0xFFFF)
    SharedApngEncoder.encode(
      frames =
        object : AbstractList<BufferedImage>() {
          override val size: Int
            get() = frames.size

          override fun get(index: Int): BufferedImage = readPng(frames[index], fileSystem)
        },
      delays = List(frames.size) { delay },
      loopCount = loopCount,
      out = out,
    )
  }

  private fun readPng(file: File, fileSystem: FileSystem): BufferedImage {
    val bytes = fileSystem.read(file.path.toPath()) { readByteArray() }
    require(bytes.size > PNG_SIGNATURE.size) {
      "ApngEncoder: ${file.absolutePath} is too small to be a PNG"
    }
    for (i in PNG_SIGNATURE.indices) {
      require(bytes[i] == PNG_SIGNATURE[i]) {
        "ApngEncoder: ${file.absolutePath} is not a valid PNG (signature mismatch at byte $i)"
      }
    }
    return ImageIO.read(MemoryCacheImageInputStream(bytes.inputStream()))
      ?: throw IllegalArgumentException("ApngEncoder: ${file.absolutePath} failed to decode")
  }
}
