package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Color` is a value class over a packed `ULong`; the JVM field the resolver reflects is a long.
 */
private val Color.packed: Long
  get() = value.toLong()

/**
 * Pins [GlimmerSurface] — the read that stopped every Glimmer (`androidx.xr.glimmer`) container
 * from exporting as an empty SVG layer.
 *
 * Glimmer fills and rings a `Card` / `Button` / `ListItem` from one `Modifier.surface`, a
 * `DrawModifierNode` that draws both halves itself. Neither half is a `Modifier.background` or a
 * `Modifier.border`, and its shader/`drawLayer` draw is not something the draw recorder can replay,
 * so the resolver used to come away with no fill token, no border token and no raster: a white card
 * with white-on-white text.
 *
 * Glimmer is deliberately **not** on this module's classpath — it is an Android-XR library the
 * extractors read reflectively, like the Wear and Material shapes their neighbours read — so the
 * element and node shapes are stood in for by fakes matched on their simple names, exactly as
 * [LinearGradientEndpointTest] stands in for Compose's internal `LinearGradient`.
 *
 * Running on the JVM there is no `android.os.Build`, so the reads take the API-33+ (shader border)
 * branch, which is the one every Glimmer render actually takes.
 */
class GlimmerSurfaceTest {

  /**
   * Stands in for `androidx.xr.glimmer.SurfaceNodeElement`. `Color` is a value class over a packed
   * `ULong`, so its fields are plain `long`s on the JVM — which is what the resolver reflects.
   */
  @Suppress("unused")
  private class SurfaceNodeElement(
    @JvmField val color: Long,
    @JvmField val focusedColor: Long,
    @JvmField val contentColor: Long,
    @JvmField val focusedContentColor: Long,
  )

  /** Stands in for `androidx.xr.glimmer.ContentColorProviderElement`. */
  @Suppress("unused") private class ContentColorProviderElement(@JvmField val contentColor: Long)

  /** A settled `Animatable<Float, …>`: all the read needs is a zero-arg `getValue()`. */
  private class FakeAnimatable(val value: Float)

  /**
   * Stands in for the live `androidx.xr.glimmer.SurfaceNode`. The focused corner colours 1..3 are
   * computed by `updateFocusedBorderColors` and cached on the node, so they only exist here.
   */
  @Suppress("unused")
  private class SurfaceNode(
    @JvmField val _focusProgress: FakeAnimatable?,
    @JvmField val _pressedProgress: FakeAnimatable?,
    @JvmField val contentColor: Long = Color.Black.packed,
    @JvmField val focusedContentColor: Long = Color.Black.packed,
    @JvmField val focusedBorderColor1: Long = Color(0xFFBFD4FF).packed,
    @JvmField val focusedBorderColor2: Long = Color(0xFF7A9BE0).packed,
    @JvmField val focusedBorderColor3: Long = Color(0xFF9BBFFF).packed,
  )

  /**
   * Glimmer's own `Colors.surface` and `Colors.primary`, the resting/focused pair a `Card` uses.
   */
  private val surfaceColor = Color(0xFF303030)
  private val focusedColor = Color(0xFF9BBFFF)

  private fun element(
    color: Color = surfaceColor,
    focused: Color = focusedColor,
    contentColor: Color = Color.White,
    focusedContentColor: Color = Color.Black,
  ) =
    SurfaceNodeElement(
      color = color.packed,
      focusedColor = focused.packed,
      contentColor = contentColor.packed,
      focusedContentColor = focusedContentColor.packed,
    )

  private fun node(focus: Float = 0f, pressed: Float = 0f) =
    SurfaceNode(FakeAnimatable(focus), FakeAnimatable(pressed))

  @Test
  fun `a resting surface resolves its fill, its border width and the idle border ramp`() {
    val paint =
      requireNotNull(
        GlimmerSurface.paint(element(), node = null, minDimensionPx = 248, density = 1f)
      )

    assertEquals("#FF303030", paint.fillArgb)
    assertEquals("1.5dp", paint.borderWidthDp)
    val border = requireNotNull(paint.border)
    // Glimmer's `BorderShader` idle constants, in the order the shader declares them.
    assertEquals(listOf("#E6CFCFCF", "#80404040", "#66292929", "#B37D7D7D"), border.colors)
    assertEquals(listOf(0f, 0.2f, 0.5f, 0.8f), border.stops)
    // The shader's angular ramp is symmetric about the top-left → bottom-right diagonal, so a
    // diagonal linear gradient expresses it rather than approximating it.
    assertEquals(0f, border.startX, 0f)
    assertEquals(0f, border.startY, 0f)
    assertEquals(1f, border.endX, 0f)
    assertEquals(1f, border.endY, 0f)
  }

  @Test
  fun `a surface with no reachable live node resolves the same resting paint`() {
    val withoutNode =
      requireNotNull(
        GlimmerSurface.paint(element(), node = null, minDimensionPx = 248, density = 1f)
      )
    val withRestingNode =
      requireNotNull(GlimmerSurface.paint(element(), node(), minDimensionPx = 248, density = 1f))
    assertEquals(withoutNode.fillArgb, withRestingNode.fillArgb)
    assertEquals(withoutNode.borderWidthDp, withRestingNode.borderWidthDp)
    assertEquals(withoutNode.border?.colors, withRestingNode.border?.colors)
  }

  @Test
  fun `a focused surface takes its focused fill, its wider border and the focused corners`() {
    val paint =
      requireNotNull(
        GlimmerSurface.paint(element(), node(focus = 1f), minDimensionPx = 248, density = 1f)
      )

    assertEquals("#FF9BBFFF", paint.fillArgb)
    assertEquals("2.0dp", paint.borderWidthDp)
    val border = requireNotNull(paint.border)
    // Corner 0 is `Color.White` on the node; 1..3 are the tone-shifted colours it caches.
    assertEquals("#FFFFFFFF", border.colors[0])
    assertEquals("#FFBFD4FF", border.colors[1])
    assertEquals("#FF7A9BE0", border.colors[2])
    assertEquals("#FF9BBFFF", border.colors[3])
  }

  @Test
  fun `a half-focused surface reports the paint that frame was drawn with`() {
    val paint =
      requireNotNull(
        GlimmerSurface.paint(element(), node(focus = 0.5f), minDimensionPx = 248, density = 1f)
      )
    // Between the two border widths, and a fill that is neither endpoint.
    assertEquals("1.75dp", paint.borderWidthDp)
    assertTrue(paint.fillArgb != "#FF303030" && paint.fillArgb != "#FF9BBFFF")
  }

  @Test
  fun `a pressed surface widens its border to an eighth of its shorter side`() {
    // 248px / 8 = 31px, and at density 2 that is 15.5dp.
    val paint =
      requireNotNull(
        GlimmerSurface.paint(element(), node(pressed = 1f), minDimensionPx = 248, density = 2f)
      )
    assertEquals("15.5dp", paint.borderWidthDp)
  }

  @Test
  fun `the border never claims a stroke wider than half the box`() {
    // `BorderLogic` caps the stroke at half the shorter side; past that it fills the shape, which
    // a stroke token cannot express, so the width is clamped rather than overstated. A 2px-tall
    // divider cannot carry Glimmer's 1.5dp resting stroke on both edges.
    val paint =
      requireNotNull(GlimmerSurface.paint(element(), node = null, minDimensionPx = 2, density = 1f))
    assertEquals("1.0dp", paint.borderWidthDp)
  }

  @Test
  fun `a surface provides its content colour to descendants, focused-aware`() {
    assertEquals(
      Color.White,
      GlimmerSurface.providedContentColorOf(element(), node = null),
    )
    assertEquals(
      Color.Black,
      GlimmerSurface.providedContentColorOf(element(), node(focus = 1f)),
    )
  }

  @Test
  fun `an explicit contentColorProvider is read as a provider too`() {
    assertEquals(
      Color.Red,
      GlimmerSurface.providedContentColor(
        ContentColorProviderElement(Color.Red.packed),
        coordinates = null,
      ),
    )
  }

  @Test
  fun `a non-Glimmer modifier is neither a surface nor a content-colour provider`() {
    assertFalse(GlimmerSurface.isSurfaceElement(Any()))
    assertNull(GlimmerSurface.paint(Any(), node = null, minDimensionPx = 100, density = 1f))
    assertNull(GlimmerSurface.providedContentColor(Any(), coordinates = null))
  }

  @Test
  fun `a surface whose colours cannot be read still resolves its border`() {
    // A wide-gamut colour packing (a non-zero low word) is not a flat sRGB fill we can publish.
    class SurfaceNodeElement(@JvmField val color: Long, @JvmField val focusedColor: Long)
    val paint =
      requireNotNull(
        GlimmerSurface.paint(
          SurfaceNodeElement(color = 0x00000001L, focusedColor = 0x00000001L),
          node = null,
          minDimensionPx = 100,
          density = 1f,
        )
      )
    assertNull(paint.fillArgb)
    assertNotNull(paint.border)
  }
}
