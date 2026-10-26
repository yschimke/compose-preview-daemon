package ee.schimke.composeai.renderer

import androidx.compose.ui.semantics.SemanticsNode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Flags a still that came out as one flat colour although the composition it captured has content
 * (compose-ai-tools#6229).
 *
 * A batch render was seen writing the first preview correctly and every later one as an identical
 * uniform `#FAFAFA` frame, exiting 0 with no sidecar. That cause — SDK 37 hwui dropping frames
 * whose `VSYNC` went backwards across Robolectric's between-test clock reset — is fixed in
 * [MonotonicFrameVsync]; this stays as the tripwire for the next way a capture can draw nothing: a
 * preview whose semantics tree has nodes but whose pixels are all the same almost certainly drew
 * nothing, and saying so names the preview to chase.
 *
 * A warning on stderr, never a failure — a deliberately blank preview (an empty state on a plain
 * background with a semantics-only child) is legal.
 */
internal object UniformStillCheck {

  /** The single ARGB colour every pixel of [image] has, or `null` when they differ. */
  fun uniformColour(image: BufferedImage): Int? {
    if (image.width == 0 || image.height == 0) return null
    val first = image.getRGB(0, 0)
    val row = IntArray(image.width)
    for (y in 0 until image.height) {
      image.getRGB(0, y, image.width, 1, row, 0, image.width)
      if (row.any { it != first }) return null
    }
    return first
  }

  /**
   * Prints a warning naming [previewId] when [file] (or [decoded], its already-decoded pixels) is a
   * single colour while [root] has descendants. Never throws: a diagnostic must not fail a render.
   */
  fun warnIfUniform(previewId: String, file: File, decoded: BufferedImage?, root: SemanticsNode?) {
    try {
      if (root == null || root.children.isEmpty()) return
      val image = decoded ?: (if (file.isFile) ImageIO.read(file) else null) ?: return
      val colour = uniformColour(image) ?: return
      System.err.println(
        "WARNING: preview '$previewId' captured a uniform " +
          "#%08X %dx%d frame although its composition has %d semantics node(s); the capture "
            .format(colour, image.width, image.height, countDescendants(root)) +
          "probably drew nothing (compose-ai-tools#6229). File: ${file.path}"
      )
    } catch (t: Throwable) {
      if (t is VirtualMachineError) throw t
    }
  }

  private fun countDescendants(node: SemanticsNode): Int =
    node.children.sumOf { 1 + countDescendants(it) }
}
