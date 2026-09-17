package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how [ModifierTokenResolver.linearGradient] resolves a Compose `LinearGradient`'s endpoints
 * into the `objectBoundingBox` coordinates the SVG `<linearGradient>` uses (issue #2852).
 *
 * Two subtleties, both of which produced a gradient running the wrong way against its render.
 *
 * The *unspecified* endpoint: `Brush.linearGradient(colors)` with no explicit `start`/`end` stores
 * `Offset.Infinite` — infinite on **both** axes — which Compose resolves to the far corner of the
 * box, i.e. the diagonal gradient. Treating only X that way and defaulting Y to 0 flattened those
 * to horizontal.
 *
 * And the box's **aspect ratio**: `objectBoundingBox` scales the gradient's own space by `(width,
 * height)`, and a non-uniform scale does not map a direction to a direction with the same
 * perpendicular — so dividing each endpoint by its own extent, which reads like the obvious
 * reduction, *rotates* every diagonal gradient on a non-square box. The numbers below are the
 * corrected bounding-box vectors, each checked against the pixel-space ramp Compose draws: on a
 * 100x40 box a gradient to the far corner really does reach 1.0 at that corner and 10000/11600 at
 * the top-right one, which is what these coordinates reproduce and what the naive `(1, 1)` did not.
 */
class LinearGradientEndpointTest {

  /** Stands in for `androidx.compose.ui.graphics.LinearGradient` — matched by simple name. */
  @Suppress("unused")
  private class LinearGradient(
    @JvmField val colors: List<Color>,
    @JvmField val stops: List<Float>?,
    @JvmField val start: Long,
    @JvmField val end: Long,
  )

  /** Compose's `Offset` is a value class over two floats packed into a `Long`. */
  private fun offset(x: Float, y: Float): Long =
    (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)

  private val infinite = offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

  private fun resolve(start: Long, end: Long) =
    ModifierTokenResolver.linearGradient(
      mod = Any(),
      elements =
        mapOf(
          "brush" to
            LinearGradient(colors = listOf(Color.Red, Color.Blue), stops = null, start, end)
        ),
      widthPx = 100,
      heightPx = 40,
    )

  @Test
  fun `an unspecified endpoint resolves to the far corner, keeping the gradient diagonal`() {
    val g = requireNotNull(resolve(start = offset(0f, 0f), end = infinite))
    assertEquals(0f, g.startX, 0f)
    assertEquals(0f, g.startY, 0f)
    // The aspect-corrected vector for a pixel-space ramp from (0, 0) to (100, 40): the two
    // coordinates are no longer `(1, 1)`, but they are what makes the rendered ramp reach 1.0 at
    // the far corner instead of running at the wrong angle across the box.
    assertEquals(1.131045f, g.endX, 1e-5f)
    assertEquals("an infinite Y endpoint is the bottom edge, not 0", 0.180967f, g.endY, 1e-5f)
    // Still diagonal — both coordinates positive, and their ratio is the box's, not the vector's.
    assertTrue(g.endX > 0f && g.endY > 0f)
  }

  @Test
  fun `a horizontal gradient keeps its finite zero Y endpoint`() {
    // `Brush.horizontalGradient` leaves Y finite at 0 — only X runs to the edge.
    val g =
      requireNotNull(resolve(start = offset(0f, 0f), end = offset(Float.POSITIVE_INFINITY, 0f)))
    assertEquals(1f, g.endX, 0f)
    assertEquals(0f, g.endY, 0f)
  }

  @Test
  fun `a vertical gradient keeps its finite zero X endpoint`() {
    val g =
      requireNotNull(resolve(start = offset(0f, 0f), end = offset(0f, Float.POSITIVE_INFINITY)))
    assertEquals(0f, g.endX, 0f)
    // An axis-aligned gradient is the case the aspect correction collapses back to the plain
    // per-axis division, so this stays exactly `1.0` (bar float noise) on any box.
    assertEquals(1f, g.endY, 1e-5f)
  }

  @Test
  fun `explicit endpoints keep their start fraction and an aspect-corrected direction`() {
    val g = requireNotNull(resolve(start = offset(25f, 10f), end = offset(75f, 30f)))
    // The *start* is a plain fraction of the box — a point maps straight through.
    assertEquals(0.25f, g.startX, 1e-6f)
    assertEquals(0.25f, g.startY, 1e-6f)
    // The endpoint is start + the corrected vector for the (50, 20) pixel-space run. `(0.75,
    // 0.75)` — each component over its own extent — described a ramp at a different angle.
    assertEquals(0.815523f, g.endX, 1e-5f)
    assertEquals(0.340483f, g.endY, 1e-5f)
  }
}
