package ee.schimke.composeai.daemon

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [ModifierTokenResolver.painterGradient] — the read that turns
 * `Image(BrushPainter(Brush.linearGradient(…)))` into a real `<linearGradient>` instead of an
 * `<image>` crop of a rasterised ramp.
 *
 * The case that drove it is the Glimmer kit `Card`'s header artwork: a 1000px-square brush drawn
 * `ContentScale.FillWidth` into the card's 396x248 header slot. Two things about it are easy to get
 * wrong and both were, so both are pinned here against the numbers the real render produces:
 * - `Brush.linearGradient`'s `intrinsicSize` is the span between its endpoints, and `BrushPainter`
 *   forwards it — so `Modifier.paint` really does scale and centre the painter, and the brush's
 *   absolute endpoints live in *that* space. Reading them against the node box put the whole ramp
 *   74px late.
 * - `objectBoundingBox` scales the gradient's space by (width, height), and a non-uniform scale
 *   does not preserve a direction's own perpendicular. Dividing each endpoint by its own extent
 *   rotated the 45° brush over the non-square slot.
 */
class PainterBrushGradientTest {

  /** Stands in for `androidx.compose.ui.draw.PainterElement`, which the resolver reflects. */
  @Suppress("unused")
  private class PainterElement(
    @JvmField val painter: Any,
    @JvmField val alpha: Float = 1f,
    @JvmField val colorFilter: Any? = null,
    @JvmField val alignment: Alignment = Alignment.Center,
    @JvmField val contentScale: ContentScale = ContentScale.Fit,
  )

  /** The Glimmer catalog's own header brush: four stops across a 1000px square. */
  private val headerBrush =
    Brush.linearGradient(
      0.0f to Color(0xFF3C8CDE),
      0.4f to Color(0xFFED73A8),
      0.6f to Color(0xFFED73A8),
      1.0f to Color(0xFFE763F9),
      start = Offset.Zero,
      end = Offset(1000f, 1000f),
    )

  private fun resolve(
    painter: Any,
    widthPx: Int,
    heightPx: Int,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    colorFilter: Any? = null,
  ) =
    PainterElement(painter, alpha, colorFilter, contentScale = contentScale).let { element ->
      ModifierTokenResolver.painterGradient(emptyMap(), element, widthPx, heightPx)
    }

  @Test
  fun `the Glimmer card header resolves the gradient the render drew`() {
    val g =
      requireNotNull(
        resolve(BrushPainter(headerBrush), 396, 248, contentScale = ContentScale.FillWidth)
      )

    assertEquals(
      listOf("#FF3C8CDE", "#FFED73A8", "#FFED73A8", "#FFE763F9"),
      g.colors,
    )
    assertEquals(listOf(0f, 0.4f, 0.6f, 1f), g.stops)
    // FillWidth scales the 1000px square to 396x396 and `Alignment.Center` lifts it 74px above the
    // slot's top, so the ramp starts off the top of the box …
    assertEquals(0f, g.startX, 1e-4f)
    assertEquals(-74f / 248f, g.startY, 1e-4f)
    // … and the endpoint is the aspect-corrected bounding-box vector for a 45° pixel-space ramp.
    // Verified against the published render: with these four numbers the browser reproduces the
    // header's pixels exactly.
    assertEquals(3.62770f, g.endX, 1e-4f)
    assertEquals(1.97351f, g.endY, 1e-4f)
  }

  @Test
  fun `an axis-aligned brush is unaffected by the aspect correction`() {
    val brush =
      Brush.linearGradient(
        listOf(Color.Red, Color.Blue),
        start = Offset.Zero,
        end = Offset(100f, 0f),
      )
    // An intrinsic width of 100 and no intrinsic height: FillBounds stretches it to the whole box,
    // so the brush's 100px run spans it.
    val g =
      requireNotNull(resolve(BrushPainter(brush), 400, 100, contentScale = ContentScale.FillBounds))
    assertEquals(0f, g.startX, 1e-4f)
    assertEquals(0f, g.startY, 1e-4f)
    assertEquals(0.25f, g.endX, 1e-4f)
    assertEquals(0f, g.endY, 1e-4f)
  }

  @Test
  fun `a painter that is not a brush resolves no gradient`() {
    // A `ColorPainter` is the flat-fill path's business, and a bitmap / vector painter keeps the
    // raster fallback: only a `BrushPainter`'s single `drawRect(brush)` is safe to reduce.
    assertNull(resolve(ColorPainter(Color.Red), 100, 100))
  }

  @Test
  fun `a radial brush still rasters`() {
    val radial = Brush.radialGradient(listOf(Color.Red, Color.Blue))
    assertNull(resolve(BrushPainter(radial), 100, 100))
  }

  @Test
  fun `a tinted or faded paint still rasters`() {
    assertNull(resolve(BrushPainter(headerBrush), 396, 248, colorFilter = Any()))
    assertNull(resolve(BrushPainter(headerBrush), 396, 248, alpha = 0.5f))
  }

  @Test
  fun `a degenerate box resolves nothing`() {
    assertNull(resolve(BrushPainter(headerBrush), 0, 248))
    assertNull(resolve(BrushPainter(headerBrush), 396, 0))
  }
}
