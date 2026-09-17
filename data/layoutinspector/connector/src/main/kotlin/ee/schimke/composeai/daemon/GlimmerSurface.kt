package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorGradient
import java.util.Locale
import kotlin.math.min

/**
 * Reads the container paint a Glimmer (`androidx.xr.glimmer`) `Modifier.surface` puts on the canvas
 * so the token export can draw it as SVG primitives.
 *
 * Every Glimmer container — `Card`, `ActionCard`, `Button`, `IconButton`, `ListItem`, `TitleChip`,
 * `ToggleButton` — fills and rings itself through that one modifier, and nothing about it looks
 * like `Modifier.background` / `Modifier.border`: `SurfaceNode` is a `DrawModifierNode` that
 * `drawOutline`s the background itself, strokes an AGSL-shaded border over it through Glimmer's own
 * fork of the foundation border logic, and (API 33+) records both into a `GraphicsLayer` it blurs
 * with a two-pass runtime shader. So the resolver saw no fill token, no border token, and — because
 * a shader/`drawLayer` draw is not something [DrawCaptureExtractor] can record and the node is not
 * a childless leaf — no raster either. A Glimmer preview exported as an *empty* layer: a white
 * card, white-on-white title and subtitle, and a black icon where a white one renders.
 *
 * What is recovered here is the paint, not the blur. `SurfaceNodeElement`'s inspector properties
 * (`color`, `focusedColor`, `contentColor`, `focusedContentColor`, `shape`, `enabled`) name the
 * colours, the shape is already read by the resolver's ordinary shape pass, and the border geometry
 * comes from Glimmer's own documented constants below. The progressive edge blur is deliberately
 * dropped: it is a `RuntimeShader` render effect with no SVG form, and a crisp stroke at the width
 * Glimmer strokes is both closer to the render than nothing and the editable primitive a designer
 * importing the SVG actually wants.
 *
 * ### The animated state comes off the live node
 *
 * A Glimmer surface interpolates between its resting and focused colours on `focusProgress`, and
 * widens its border on `pressedProgress`. Neither is on the element — the element carries both
 * *endpoints* — so they are read off the live `SurfaceNode` reached from the modifier's
 * coordinator, exactly as `ComposeSemanticsDataProducer`'s Material focus-ring read reaches a
 * settled ripple node. That is what makes the catalog's harness-driven `focused` and `pressed`
 * variants export the surface they actually drew rather than the resting one; with no reachable
 * node the resting paint is used, which is what every unfocused capture resolves to anyway.
 *
 * **Not** recovered: the disabled overlay. `SurfaceNode` paints `DisabledOverlayColor` *after*
 * `drawContent()`, so it dims the label and icon as much as the fill — and a `backgroundColor`
 * token draws under a layer's children, never over them. A disabled Glimmer surface therefore
 * exports with its enabled fill; representing the overlay needs a layer the token model has no slot
 * for.
 */
internal object GlimmerSurface {

  /**
   * The element class `Modifier.surface` installs. Matched by simple name like every other
   * library-specific read in this file's neighbours: a release build compiles the inspector
   * `nameFallback` out, leaving only the class, and `SurfaceNodeElement` is unambiguous — no other
   * Compose library ships that name.
   */
  private const val SURFACE_ELEMENT = "SurfaceNodeElement"

  /**
   * `Modifier.contentColorProvider(color)`'s element — the other node that provides content colour.
   */
  private const val CONTENT_COLOR_ELEMENT = "ContentColorProviderElement"

  /** The live node [SURFACE_ELEMENT] creates, as it appears in the modifier-node chain. */
  private const val SURFACE_NODE = "SurfaceNode"

  /** `androidx.xr.glimmer.DefaultSurfaceBorderWidth`. */
  private const val DEFAULT_BORDER_WIDTH_DP = 1.5f

  /** `androidx.xr.glimmer.FocusedSurfaceBorderWidth`. */
  private const val FOCUSED_BORDER_WIDTH_DP = 2f

  /**
   * The four border colours Glimmer's `BorderShader` holds as AGSL constants
   * (`uBorderIdleColor0..3`), in the order the shader declares them.
   *
   * The shader is angular, but symmetrically so: it reduces a fragment's angle to `dist`, the
   * *unsigned* angular distance from the top-left corner, so the two off-diagonal corners resolve
   * to the same colour and the ramp runs top-left → bottom-right. That is a diagonal linear
   * gradient, which is why [border] can express it as one rather than approximate a cone.
   */
  private val IDLE_BORDER_COLORS =
    listOf(
      Color(0.81f, 0.81f, 0.81f, 0.9f),
      Color(0.25f, 0.25f, 0.25f, 0.5f),
      Color(0.16f, 0.16f, 0.16f, 0.4f),
      Color(0.49f, 0.49f, 0.49f, 0.7f),
    )

  /**
   * Where each of [IDLE_BORDER_COLORS] lands along the top-left → bottom-right diagonal.
   *
   * `BorderShader` blends the four with `smoothstep(0, 0.1)`, `smoothstep(0.1, 0.25)` and
   * `smoothstep(0.25, 0.4)` over a `dist` that runs 0 → 0.5 from the top-left corner to the
   * bottom-right one, so each colour is fully reached at twice its threshold: 0, 0.2, 0.5, 0.8.
   */
  private val BORDER_STOPS = listOf(0f, 0.2f, 0.5f, 0.8f)

  /** `androidx.xr.glimmer.SurfaceNode.focusedBorderColor0` — the focused top-left corner. */
  private val FOCUSED_BORDER_COLOR_0 = Color.White

  /** `androidx.xr.glimmer.PressedOverlayColor` at `PressedOverlayAlpha`. */
  private val PRESSED_OVERLAY_COLOR = Color.White
  private const val PRESSED_OVERLAY_ALPHA = 0.16f

  /** The API level at which `SurfaceNode` switches to the shader border + progressive blur. */
  private const val TIRAMISU = 33

  /** The paint one `Modifier.surface` contributes, already reduced to wire tokens. */
  internal class Paint(
    /** The resolved background fill as `#AARRGGBB`, or null when the colour could not be read. */
    val fillArgb: String?,
    /** The border stroke width as a `"1.5dp"`-shaped token, or null when there is no border. */
    val borderWidthDp: String?,
    /** The border's diagonal ramp, or null when the border colours could not be read. */
    val border: LayoutInspectorGradient?,
  )

  /** Whether [modifier] is a Glimmer `Modifier.surface` element. */
  fun isSurfaceElement(modifier: Any): Boolean = modifier.javaClass.simpleName == SURFACE_ELEMENT

  /**
   * The live `SurfaceNode` behind a `Modifier.surface`, reached from the modifier's coordinator.
   *
   * Compose hands a capture walk the modifier *element*, never the node it created, so the node is
   * found the same way the Material ripple read finds a settled ripple node: from the coordinator's
   * tail, following `delegate` / `child` / `parent`. `SurfaceNode` is a `DelegatingNode`, so the
   * delegate hop matters. Null when the chain can't be walked, which leaves the caller on the
   * element's resting colours.
   */
  fun liveNode(coordinates: Any?): Any? {
    val tail = call(coordinates ?: return null, "getTail") ?: return null
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    fun visit(candidate: Any?): Any? {
      if (candidate == null || !seen.add(candidate)) return null
      if (candidate.javaClass.simpleName == SURFACE_NODE) return candidate
      for (accessor in NODE_LINKS) {
        val next =
          accessor.asSequence().mapNotNull { call(candidate, it) }.firstOrNull() ?: continue
        visit(next)?.let {
          return it
        }
      }
      return null
    }
    return visit(tail)
  }

  /** The `Modifier.Node` links walked looking for a [SURFACE_NODE], in the order they are tried. */
  private val NODE_LINKS =
    listOf(
      listOf("getDelegate\$ui_release", "getDelegate\$ui", "getDelegate"),
      listOf("getChild\$ui_release", "getChild\$ui", "getChild"),
      listOf("getParent\$ui_release", "getParent\$ui", "getParent"),
    )

  /**
   * The fill + border a Glimmer surface paints, or null when [element] is not a surface element.
   *
   * [node] is the live `SurfaceNode` from [liveNode] (null is fine — the resting paint is used
   * then), [minDimensionPx] the shorter side of the node's measured box (the pressed border width
   * is a fraction of it) and [density] the render's px-per-dp.
   */
  fun paint(element: Any, node: Any?, minDimensionPx: Int, density: Float): Paint? {
    if (!isSurfaceElement(element)) return null
    val focusProgress = animatedValue(node, "_focusProgress")
    val pressedProgress = animatedValue(node, "_pressedProgress")
    val fill =
      interpolated(colorField(element, "color"), colorField(element, "focusedColor"), focusProgress)
        ?.let { base ->
          // The pressed white wash is the pre-API-33 path's own compositing step; the shader path
          // carries the press in the border width and the blur instead, so folding it in there
          // would lighten a surface the render never lightened.
          if (pressedProgress > 0f && sdkInt() < TIRAMISU) {
            PRESSED_OVERLAY_COLOR.copy(alpha = PRESSED_OVERLAY_ALPHA * pressedProgress)
              .compositeOver(base)
          } else base
        }
    val widthDp = borderWidthDp(focusProgress, pressedProgress, minDimensionPx, density)
    return Paint(
      fillArgb = fill?.let(::wire),
      borderWidthDp = widthDp?.let { "${(it * 100f).toInt() / 100f}dp" },
      border = widthDp?.let { border(node, focusProgress) },
    )
  }

  /**
   * The content colour a Glimmer node provides to its descendants, as a packed `Color`, or null
   * when [modifier] provides none.
   *
   * Two elements do: `Modifier.surface`, which delegates a `ContentColorProviderNode` built from
   * its `contentColor` / `focusedContentColor` pair, and an explicit
   * `Modifier.contentColorProvider(color)`. Glimmer's `Icon` reads whichever is nearest through
   * `currentContentColor()`, so the walk that threads this down the layout tree has to see both.
   */
  fun providedContentColor(modifier: Any, coordinates: Any?): Color? {
    val simpleName = modifier.javaClass.simpleName
    if (simpleName == CONTENT_COLOR_ELEMENT) return colorField(modifier, "contentColor")
    if (simpleName != SURFACE_ELEMENT) return null
    // The chain walk is only paid for on a modifier that really is a surface: this runs for every
    // modifier of every node in the tree, and [liveNode] follows the whole node chain.
    return providedContentColorOf(modifier, liveNode(coordinates))
  }

  /** [providedContentColor]'s surface arm, for callers (and tests) that hold the node already. */
  internal fun providedContentColorOf(element: Any, node: Any?): Color? {
    return interpolated(
      colorField(element, "contentColor"),
      colorField(element, "focusedContentColor"),
      animatedValue(node, "_focusProgress"),
    )
  }

  /**
   * [resting] interpolated towards [focused] by [progress], the way `SurfaceNode` interpolates its
   * own colour pairs — but short-circuiting at both ends.
   *
   * The endpoints are not an optimisation. `Color`'s `lerp` interpolates through Oklab, so a
   * round-trip at fraction 0 can come back a unit off in a channel, and these colours are matched
   * against the theme's `#AARRGGBB` → role-name map: a `#FF303030` that exported as `#FF313030`
   * would lose its `surface` variable name in the SVG for no reason at all.
   */
  private fun interpolated(resting: Color?, focused: Color?, progress: Float): Color? =
    when {
      resting == null || focused == null -> resting ?: focused
      progress <= 0f -> resting
      progress >= 1f -> focused
      else -> lerp(resting, focused, progress)
    }

  /**
   * The border's diagonal ramp: Glimmer's four idle corner colours, each interpolated towards its
   * focused counterpart on [focusProgress], laid along the top-left → bottom-right diagonal at
   * [BORDER_STOPS].
   *
   * The focused corners are computed by `SurfaceNode.updateFocusedBorderColors` (tone-shifted from
   * the surface's `focusedColor`) and cached on the node, so a focused surface needs the live node;
   * without one the idle ramp is emitted, which is the whole of an unfocused surface's border.
   *
   * The shader's white "peak" pulse is not modelled. It exists only *during* the focus and ambient
   * animations (`focusPulse` is zero at both progress 0 and 1), and every still capture the export
   * sees is settled at one end or the other.
   */
  private fun border(node: Any?, focusProgress: Float): LayoutInspectorGradient? {
    val colors = IDLE_BORDER_COLORS.mapIndexed { index, idle ->
      val focused =
        if (index == 0) FOCUSED_BORDER_COLOR_0
        else node?.let { colorField(it, "focusedBorderColor$index") }
      interpolated(idle, focused, focusProgress) ?: idle
    }
    if (colors.all { it.alpha <= 0f }) return null
    return LayoutInspectorGradient(
      colors = colors.map(::wire),
      stops = BORDER_STOPS,
      startX = 0f,
      startY = 0f,
      endX = 1f,
      endY = 1f,
    )
  }

  /**
   * `SurfaceNode.calculateBorderWidth` / `calculateSolidBorderWidth`, in dp.
   *
   * Both lerp the resting 1.5dp to the focused 2dp on the focus progress; the shader path
   * additionally lerps *that* to an eighth of the node's shorter side on the press progress, which
   * is how a pressed Glimmer surface reads as a thick glowing rim rather than an outline.
   */
  private fun borderWidthDp(
    focusProgress: Float,
    pressedProgress: Float,
    minDimensionPx: Int,
    density: Float,
  ): Float? {
    val focusWidth =
      DEFAULT_BORDER_WIDTH_DP +
        (FOCUSED_BORDER_WIDTH_DP - DEFAULT_BORDER_WIDTH_DP) * focusProgress.coerceIn(0f, 1f)
    val width =
      if (pressedProgress > 0f && sdkInt() >= TIRAMISU && minDimensionPx > 0 && density > 0f) {
        val pressedWidth = minDimensionPx / 8f / density
        focusWidth + (pressedWidth - focusWidth) * pressedProgress.coerceIn(0f, 1f)
      } else focusWidth
    // `BorderLogic` caps the stroke at half the shorter side so both edges fit the canvas; past
    // that it fills the shape outright, which a stroke token cannot say — so clamp rather than
    // publish a stroke wider than the box.
    val capDp = if (minDimensionPx > 0 && density > 0f) minDimensionPx / 2f / density else width
    return min(width, capDp).takeIf { it > 0f }
  }

  /**
   * The current value of one of `SurfaceNode`'s progress `Animatable`s, or 0 when it can't be read.
   *
   * Reading the animated value rather than the `isFocused` / `isPressed` flag is deliberate, and
   * the same choice the Material focus-ring read makes: a settled capture reports 0 or 1, and a
   * capture taken mid-animation reports the paint that frame actually drew.
   */
  private fun animatedValue(node: Any?, field: String): Float {
    val animatable = node?.let { reflectedField(it, field) } ?: return 0f
    return (call(animatable, "getValue") as? Float)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
      ?: 0f
  }

  /**
   * The platform API level of the process the capture runs in, or [TIRAMISU] when there is no
   * `android.os.Build` at all.
   *
   * This module is a plain JVM library shared by the Robolectric and the Desktop hosts, so the
   * class is reached reflectively. Defaulting to [TIRAMISU] keeps the desktop host — where Glimmer
   * cannot run in the first place — on the same branch as every Android host new enough to render
   * Glimmer's shader border, rather than on the legacy solid-border arithmetic.
   */
  private fun sdkInt(): Int =
    runCatching {
      Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
    }
      .getOrNull() ?: TIRAMISU

  /** A `Color`-typed field, read from the inspector-free element/node by reflection. */
  private fun colorField(instance: Any, name: String): Color? {
    val raw = reflectedField(instance, name) ?: return null
    val packed =
      when (raw) {
        is Color -> return raw.takeIf { it != Color.Unspecified }
        is Long -> raw.toULong()
        else -> return null
      }
    // A non-zero low word is a wide-gamut packing whose components we can't read as flat sRGB.
    if (packed and 0xFFFFFFFFuL != 0uL) return null
    return Color(packed).takeIf { it != Color.Unspecified }
  }

  private fun wire(color: Color): String = "#${String.format(Locale.US, "%08X", color.toArgb())}"

  private fun reflectedField(instance: Any, name: String): Any? =
    generateSequence(instance.javaClass as Class<*>?) { it.superclass }
      .flatMap { it.declaredFields.asSequence() }
      .firstOrNull { it.name == name }
      ?.let { field ->
        runCatching {
          field.isAccessible = true
          field.get(instance)
        }
          .getOrNull()
      }

  private fun call(receiver: Any, method: String): Any? = runCatching {
    receiver.javaClass.methods
      .firstOrNull { it.name == method && it.parameterCount == 0 }
      ?.let {
        it.isAccessible = true
        it.invoke(receiver)
      }
  }
    .getOrNull()
}
