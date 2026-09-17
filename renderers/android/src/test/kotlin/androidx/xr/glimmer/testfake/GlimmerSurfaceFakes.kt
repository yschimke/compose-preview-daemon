package androidx.xr.glimmer.testfake

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.InspectorInfo

/**
 * Stand-ins for the three `androidx.xr.glimmer` modifier elements the layout-inspector connector
 * reads reflectively, for use in a **live composition**.
 *
 * ## Why these exist rather than the real library
 *
 * The connector reads Glimmer the way it reads Wear's and Material's internals: by class simple
 * name, off the modifier element and the live node. `GlimmerSurfaceTest` and
 * `PainterBrushGradientTest` cover those reads as pure functions, against plain objects.
 *
 * What a plain object cannot answer is the question that governs the exported card's **geometry**:
 * `SurfaceNodeElement` is draw-only, so it creates no `NodeCoordinator` of its own, and Glimmer's
 * `CardImpl` follows it with `.defaultMinSize(minHeight).padding(contentPadding)`. Whether the
 * resolved paint box is therefore the card's outer box or its 12dp-padded content box depends on
 * which coordinator that element ends up attached to — a fact about how Compose builds a node
 * chain, only observable in a real composition. It resolved to the padded box, which is why the
 * published SVG drew the card 12px in on every side, sharing a top-left corner with its own header.
 *
 * That is a **Compose** fact, not a Glimmer one: any draw-only element sitting between a `clip` and
 * a `padding` answers it identically. So the chain is reproduced here and composed for real, and
 * the real library stays off the classpath — `androidx.xr.glimmer:glimmer` drags the whole Compose
 * test classpath from this module's `compose-bom-compat` up to a 1.12 beta while `material3` stays
 * behind, and the resulting skew breaks unrelated render tests with `AbstractMethodError`.
 *
 * ## Why the package is `androidx.xr.glimmer.testfake`
 *
 * Load-bearing, not cosmetic. `VectorGraphicExtractor.findVectorPainter` only descends into an
 * element whose class name starts with `androidx` — a guard that keeps the reflective scan out of
 * unrelated object graphs — and the icon's painter is reached *through* [IconColorFilterElement]. A
 * fake in this repository's own package would never be scanned, so the tint path under test would
 * not run at all.
 *
 * The `.testfake` suffix is equally deliberate: the simple names are what the connector matches on,
 * so they have to be exact, but sitting in a sub-package means these can never collide with the
 * real library's own classes if it ever does join a classpath.
 *
 * The fields mirror `alpha19`. Keep them in step with
 * [GlimmerSurface][ee.schimke.composeai.daemon.GlimmerSurface]'s reads, which name each one.
 */
internal fun Modifier.fakeSurface(
  shape: Shape,
  color: Color,
  focusedColor: Color,
  contentColor: Color,
  focusedContentColor: Color,
  focusProgress: Float = 0f,
  pressedProgress: Float = 0f,
): Modifier =
  this.then(
    SurfaceNodeElement(
      color = color,
      focusedColor = focusedColor,
      contentColor = contentColor,
      focusedContentColor = focusedContentColor,
      shape = shape,
      focusProgress = focusProgress,
      pressedProgress = pressedProgress,
    )
  )

/**
 * Tints [painter] from the nearest ancestor content-colour provider, the way Glimmer's `Icon` does.
 */
internal fun Modifier.fakeIconTint(painter: Painter, tint: ColorProducer? = null): Modifier =
  this.then(IconColorFilterElement(useContentColor = tint == null, tint = tint, painter = painter))

/**
 * `androidx.xr.glimmer.SurfaceNodeElement`.
 *
 * The real one publishes `enabled` / `color` / `focusedColor` / `contentColor` /
 * `focusedContentColor` / `shape` / `interactionSource` as inspector properties and holds each as a
 * field; the connector prefers the inspector projection and falls back to the field, so both are
 * provided here.
 */
internal class SurfaceNodeElement(
  private val color: Color,
  private val focusedColor: Color,
  private val contentColor: Color,
  private val focusedContentColor: Color,
  private val shape: Shape,
  private val focusProgress: Float,
  private val pressedProgress: Float,
) : ModifierNodeElement<SurfaceNode>() {

  override fun create() =
    SurfaceNode(
      color = color,
      focusedColor = focusedColor,
      contentColor = contentColor,
      focusedContentColor = focusedContentColor,
      shape = shape,
      focusProgress = focusProgress,
      pressedProgress = pressedProgress,
    )

  override fun update(node: SurfaceNode) {
    node.color = color
    node.shape = shape
  }

  override fun equals(other: Any?) =
    other is SurfaceNodeElement &&
      color == other.color &&
      focusedColor == other.focusedColor &&
      contentColor == other.contentColor &&
      focusedContentColor == other.focusedContentColor &&
      shape == other.shape &&
      focusProgress == other.focusProgress &&
      pressedProgress == other.pressedProgress

  override fun hashCode(): Int {
    var result = color.hashCode()
    result = 31 * result + focusedColor.hashCode()
    result = 31 * result + contentColor.hashCode()
    result = 31 * result + focusedContentColor.hashCode()
    result = 31 * result + shape.hashCode()
    result = 31 * result + focusProgress.hashCode()
    result = 31 * result + pressedProgress.hashCode()
    return result
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "surface"
    properties["enabled"] = true
    properties["color"] = color
    properties["focusedColor"] = focusedColor
    properties["contentColor"] = contentColor
    properties["focusedContentColor"] = focusedContentColor
    properties["shape"] = shape
  }
}

/**
 * `androidx.xr.glimmer.SurfaceNode` — a `DelegatingNode` that is also a `DrawModifierNode`, so it
 * creates no coordinator of its own. That is the whole point of the fixture.
 *
 * It draws the background outline the way the real node's pre-API-33 branch does. The real one also
 * strokes an AGSL-shaded border and, on API 33+, records both into a `GraphicsLayer` it blurs with
 * a two-pass runtime shader — none of which changes what the connector can *read*, so it is not
 * reproduced. The connector never inspects a draw; it reads the element's colours and this node's
 * settled animation state.
 *
 * `_focusProgress` / `_pressedProgress` carry the leading underscore the real node uses, because
 * that is the field the connector reads through. `focusedBorderColor1..3` are the tone-shifted
 * corners the real node caches in `updateFocusedBorderColors`.
 */
internal class SurfaceNode(
  var color: Color,
  private val focusedColor: Color,
  contentColor: Color,
  focusedContentColor: Color,
  var shape: Shape,
  focusProgress: Float,
  pressedProgress: Float,
) : DelegatingNode(), DrawModifierNode {

  @Suppress("PropertyName", "unused")
  private val _focusProgress: Animatable<Float, *> =
    Animatable(focusProgress, Float.VectorConverter)

  @Suppress("PropertyName", "unused")
  private val _pressedProgress: Animatable<Float, *> =
    Animatable(pressedProgress, Float.VectorConverter)

  @Suppress("unused") private val focusedBorderColor0: Color = Color.White
  @Suppress("unused") private val focusedBorderColor1: Color = focusedColor
  @Suppress("unused") private val focusedBorderColor2: Color = focusedColor
  @Suppress("unused") private val focusedBorderColor3: Color = focusedColor

  /** The real node delegates exactly this, which is how an `Icon` below it finds its tint. */
  @Suppress("unused")
  private val contentColorNode =
    delegate(
      ContentColorProviderNode(if (focusProgress >= 1f) focusedContentColor else contentColor)
    )

  private val drawnColor: Color
    get() = if (_focusProgress.value >= 1f) focusedColor else color

  override fun ContentDrawScope.draw() {
    drawOutline(shape.createOutline(size, layoutDirection, this), color = drawnColor)
    drawContent()
  }
}

/** `androidx.xr.glimmer.ContentColorProviderNode`, reached by the icon's ancestor traversal. */
internal class ContentColorProviderNode(var contentColor: Color) :
  TraversableNode, Modifier.Node() {
  override val traverseKey: String = CONTENT_COLOR_TRAVERSE_KEY
}

private const val CONTENT_COLOR_TRAVERSE_KEY = "androidx.xr.glimmer.ContentColor"

/**
 * `androidx.xr.glimmer.IconColorFilterElement` — the element Glimmer's `Icon` tints through.
 *
 * The real one is annotated `@Suppress("ModifierNodeInspectableProperties")` and publishes nothing,
 * which is the defect's cause: there was no filter for the vector extractor to read, so a Material
 * `ImageVector` exported in its own source colour. So this publishes nothing either — the connector
 * has to reach `useContentColor` / `tint` / `painter` by reflection, as it does in production.
 *
 * It holds the painter, so the extractor's scan reaches the `VectorPainter` *through* this element
 * and sees it as the paint modifier — the reason the tint has to be recovered from here rather than
 * from a `Modifier.paint` `colorFilter`.
 */
@Suppress("ModifierNodeInspectableProperties")
internal class IconColorFilterElement(
  private val useContentColor: Boolean,
  private val tint: ColorProducer?,
  private val painter: Painter,
) : ModifierNodeElement<IconColorFilterNode>() {
  override fun create() = IconColorFilterNode(useContentColor, tint)

  override fun update(node: IconColorFilterNode) = Unit

  override fun equals(other: Any?) =
    other is IconColorFilterElement &&
      useContentColor == other.useContentColor &&
      tint == other.tint &&
      painter == other.painter

  override fun hashCode(): Int {
    var result = useContentColor.hashCode()
    result = 31 * result + (tint?.hashCode() ?: 0)
    result = 31 * result + painter.hashCode()
    return result
  }
}

/**
 * The node behind [IconColorFilterElement], reproducing the real one's mechanism rather than its
 * effect: record the content into a `GraphicsLayer`, set that layer's `colorFilter` from
 * `currentContentColor()` at draw time, draw the layer.
 *
 * Done this way, not as a `SrcIn` rect over the content, for two reasons. It masks to the vector's
 * own alpha, so the fixture's own render shows the tinted *icon* rather than a tinted box — which
 * matters, because that render is the ground truth the exported SVG is compared against. And it is
 * the shape that makes the tint invisible to reflection in the first place: the colour lives on a
 * layer, set during draw, reachable from no inspector property and no element field but `tint` /
 * `useContentColor`. That invisibility is the defect under test.
 */
internal class IconColorFilterNode(
  private val useContentColor: Boolean,
  private val tint: ColorProducer?,
) : DelegatingNode() {

  @Suppress("unused")
  private val cacheDrawNode =
    delegate(
      CacheDrawModifierNode {
        val layer = obtainGraphicsLayer()
        layer.record { drawContent() }
        onDrawWithContent {
          val resolved =
            if (useContentColor) currentContentColor() else tint?.invoke() ?: Color.Unspecified
          layer.colorFilter = if (resolved.isSpecified) ColorFilter.tint(resolved) else null
          drawLayer(layer)
        }
      }
    )

  /** `androidx.xr.glimmer.currentContentColor()`: the nearest ancestor provider, else white. */
  private fun currentContentColor(): Color {
    var contentColor = Color.White
    traverseAncestors(CONTENT_COLOR_TRAVERSE_KEY) {
      if (it is ContentColorProviderNode) {
        contentColor = it.contentColor
        false
      } else true
    }
    return contentColor
  }
}
