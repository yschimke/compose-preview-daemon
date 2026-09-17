package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.testfake.fakeIconTint
import androidx.xr.glimmer.testfake.fakeSurface
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `compose/figma-svg` export of a Glimmer (`androidx.xr.glimmer`) `Card`, driven through a real
 * composition of Glimmer's own modifier chain.
 *
 * Every Glimmer container fills and rings itself from one `Modifier.surface`, which expands to
 * `surfaceDepthEffect(…).clip(shape).then(SurfaceNodeElement)` — a `DrawModifierNode` that draws
 * the background and an AGSL-shaded border itself and, on API 33+, records both into a
 * `GraphicsLayer` it blurs with a two-pass runtime shader. None of that looks like
 * `Modifier.background` or `Modifier.border`, so the export used to resolve no paint for it and
 * emit an **empty** layer: a white card, white-on-white text, a black icon where a white one
 * renders, and the header artwork cropped to a raster `<image>`.
 *
 * `GlimmerSurfaceTest` and `PainterBrushGradientTest` cover the reflective reads as pure functions.
 * What they cannot reach is the card's **geometry**: `SurfaceNodeElement` is draw-only, so it makes
 * no coordinator of its own, and `CardImpl` follows it with
 * `.defaultMinSize(minHeight).padding(contentPadding)` at `contentPadding = 12.dp`. Which
 * coordinator that element ends up attached to — and therefore whether the resolved paint box is
 * the card's outer box or its padded content box — is a fact about how Compose builds a node chain,
 * visible only in a live composition. It resolved to the padded box, which is why the published SVG
 * drew the card 12px in on every side, sharing a top-left corner with its own header image.
 *
 * So the chain is composed here for real, against [androidx.xr.glimmer.testfake.fakeSurface] —
 * stand-in elements carrying Glimmer's class simple names, fields and inspector projection, in a
 * package that satisfies the extractor's `androidx` scan guard. That file records why the real
 * library is not on this classpath: it drags Compose up to a 1.12 beta while `material3` stays on
 * this module's `compose-bom-compat`, and the skew breaks unrelated render tests with
 * `AbstractMethodError`. What the fixture asserts is Compose behaviour anyway — any draw-only
 * element between a `clip` and a `padding` answers it identically.
 *
 * Runs at [SDK] — the SDK the production render host pins, and above the API 33 floor where
 * `SurfaceNode` takes its shader-and-blur branch, so the path exercised here is the one every
 * Glimmer render takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgGlimmerCardRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-glimmer-card").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a Glimmer surface resolves its fill, its border and its outer paint box`() {
    val export = export("glimmer-card-tokens") { GlimmerCard() }
    val card = export.layout.node { it.tokens?.backgroundColor != null }

    // Glimmer's `Colors.surface`, read off `SurfaceNodeElement.color`.
    assertEquals("#FF303030", card.tokens?.backgroundColor)
    // `DefaultSurfaceBorderWidth`, and the four `BorderShader` idle constants along the diagonal
    // the shader's angular ramp reduces to.
    assertEquals("1.5dp", card.tokens?.borderWidth)
    assertEquals(
      listOf("#E6CFCFCF", "#80404040", "#66292929", "#B37D7D7D"),
      card.tokens?.borderGradient?.colors,
    )
    assertEquals(listOf(0f, 0.2f, 0.5f, 0.8f), card.tokens?.borderGradient?.stops)
    assertEquals("36.0dp", card.tokens?.cornerRadius)

    // THE point of rendering rather than faking. The node's own placed `bounds` is the padded
    // content box; `paintBox` is the box the surface element's coordinator reports, and the surface
    // sits outside `CardImpl`'s 12dp `contentPadding`. So the two must differ by exactly that
    // padding on every side it pads — which is the 12px the published SVG lost.
    val bounds = card.bounds
    val paintBox = requireNotNull(card.tokens?.paintBox) { "the surface must resolve a paint box" }
    assertEquals(
      "the card still reads its padded content box",
      "12.0dp",
      card.tokens?.padding?.start,
    )
    assertEquals(12, bounds.left - paintBox.left)
    assertEquals(12, bounds.top - paintBox.top)
    assertEquals(12, paintBox.right - bounds.right)
  }

  @Test
  fun `a Glimmer card header resolves the gradient its brush painter draws`() {
    val export = export("glimmer-card-header") { GlimmerCard() }
    val header = export.layout.node { it.tokens?.backgroundGradient != null }
    val gradient = requireNotNull(header.tokens?.backgroundGradient)

    assertEquals(listOf("#FF3C8CDE", "#FFED73A8", "#FFED73A8", "#FFE763F9"), gradient.colors)
    assertEquals(listOf(0f, 0.4f, 0.6f, 1f), gradient.stops)
    // The coordinates `PainterBrushGradientTest` pins from the other side, here against a real
    // `Modifier.paint`: `FillWidth` scales the 1000px-square brush to 396x396 and
    // `Alignment.Center` lifts it 74px above the 396x248 slot, and the endpoint is the
    // aspect-corrected bounding-box vector for the resulting 45° pixel-space ramp.
    assertEquals(0f, gradient.startX, 1e-4f)
    assertEquals(-74f / 248f, gradient.startY, 1e-4f)
    assertEquals(3.62770f, gradient.endX, 1e-4f)
    assertEquals(1.97351f, gradient.endY, 1e-4f)
  }

  @Test
  fun `a Glimmer card draws its surface and tints its icon in the emitted SVG`() {
    val export = export("glimmer-card-svg") { GlimmerCard() }
    val svg = export.svg

    // One rect carrying both halves of the surface: the fill and the gradient-stroked border.
    val card =
      boxOf(svg, """fill="#303030"""")
        ?: error("the card's #303030 surface fill must be emitted as a rect:\n$svg")
    assertTrue(
      "the surface border must be emitted as a gradient stroke:\n$svg",
      Regex("""<rect[^>]*stroke="url\(#[^"]+\)"""").containsMatchIn(svg),
    )

    // Glimmer's `Icon` tints through an `IconColorFilterElement` that publishes no inspectable
    // properties and sets the filter on a graphics layer at draw time, so the vector used to be
    // emitted in its own source colour. [SenderIcon] is declared black to make that visible.
    assertTrue(
      "the leading icon must be tinted white, not left in its source black:\n$svg",
      Regex("""<path[^>]*fill="#FFFFFF"""").containsMatchIn(svg),
    )
    assertTrue("nothing may be emitted in the icon's source black:\n$svg", !svg.contains("#000000"))

    // The geometry again, now as the emitter placed it: the card's rect starts outside the header's
    // on every padded side. Not exactly 12px — a stroked rect is inset by half its stroke so both
    // edges fit the box, which is `BorderLogic`'s own rule; the exact padding is asserted on the
    // tokens above.
    val header =
      boxOf(svg, """(fill="url\(#|href=)""")
        ?: error("the header must be emitted as a rect or an image:\n$svg")
    assertTrue(
      "the card must be drawn at its outer box, outside the header it pads " +
        "(card=$card header=$header):\n$svg",
      card.left < header.left && card.top < header.top && card.right > header.right,
    )
  }

  /**
   * A node the opaque-by-name rule matches, whose paint the token model fully flattened, is emitted
   * as SVG rather than rastered.
   *
   * This was the emitter-side half of the Glimmer gap, and it inverted with contracts 3.1.1.
   * `FigmaSvgModel.toLayer` used to check its opaque-by-name rule (`DEFAULT_RASTER_COMPONENTS`,
   * which holds `"Image"`) *before* looking at whether the node's paint had a vector form — and
   * `androidx.compose.foundation.Image`'s node resolves to its measure-policy class, `ImageKt`, so
   * `"ImageKt".contains("Image")` rastered the header however well the token model understood it.
   *
   * Both halves of the name match still hold here, which is what makes the assertion meaningful:
   * the component *is* opaque by name, and the gradient is emitted anyway. The published
   * `glimmer-catalog` SVG never hit the bug — its nodes resolve no source info and read
   * `ReusableComposeNode` — so this render, where source info *does* resolve, is the only place the
   * ordering is observable (yschimke/compose-preview-contracts#82).
   */
  @Test
  fun `a flattened painter fill is emitted as SVG even on a node named Image`() {
    val export = export("glimmer-card-raster-gap") { GlimmerCard() }
    val header = export.layout.node { it.tokens?.backgroundGradient != null }

    assertTrue(
      "the header node is opaque by name (component=${header.component})",
      header.component.contains("Image"),
    )
    assertFalse(
      "a resolved gradient must outrank opaque-by-name — contracts 3.1.1 reordered " +
        "FigmaSvgModel.toLayer:\n" +
        export.svg,
      export.svg.contains("<image"),
    )
    assertTrue(
      "and the header is painted by the gradient the painter resolved:\n" + export.svg,
      export.svg.contains("fill=\"url(#gf-"),
    )
  }

  /**
   * Glimmer's `Card`, chain for chain: the surface's `clip` + draw-only element, then the
   * `defaultMinSize` and 12dp `contentPadding` that make the node's placed bounds its *content*
   * box, then the header slot, leading icon and text column `CardImpl` lays out.
   *
   * [focusProgress] drives the surface's settled focus animation, which is what the catalog's
   * harness-driven `focused` variant captures.
   */
  @Composable
  private fun GlimmerCard(focusProgress: Float = 0f) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(
        Modifier.fillMaxWidth()
          .clip(CardShape)
          .fakeSurface(
            shape = CardShape,
            color = SurfaceColor,
            focusedColor = FocusedSurfaceColor,
            contentColor = Color.White,
            focusedContentColor = Color.Black,
            focusProgress = focusProgress,
          )
          .defaultMinSize(minHeight = 80.dp)
          .padding(CardContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // The header slot: `constrainHeightToAspectRatio(1.6).clip(HeaderShape)` around an `Image`
        // whose painter is a gradient brush.
        Box(Modifier.fillMaxWidth().size(width = 396.dp, height = 248.dp).clip(HeaderShape)) {
          Image(
            BrushPainter(
              Brush.linearGradient(
                0.0f to Color(0xFF3C8CDE),
                0.4f to Color(0xFFED73A8),
                0.6f to Color(0xFFED73A8),
                1.0f to Color(0xFFE763F9),
                start = Offset.Zero,
                end = Offset(HEADER_INTRINSIC, HEADER_INTRINSIC),
              )
            ),
            contentDescription = "Header artwork",
            contentScale = ContentScale.FillWidth,
          )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
          val painter = rememberVectorPainter(SenderIcon)
          Box(
            Modifier.padding(end = 12.dp)
              .size(48.dp)
              .fakeIconTint(painter)
              .paint(painter, contentScale = ContentScale.Fit)
          )
          Column(Modifier.fillMaxWidth()) {
            // Glimmer's own `Text` takes its colour from `currentContentColor()`, i.e. the white
            // the surface provides. Stated explicitly here because a bare material3 `Text` with no
            // theme resolves black, which would put the icon's source colour back in the SVG and
            // make the assertion below meaningless.
            Text("Title", color = Color.White)
            Text("Subtitle", color = Color.White)
            Text("Body", color = Color.White)
          }
        }
      }
    }
  }

  /** What one render hands back: the emitted SVG and the layout payload it was built from. */
  private class Export(val svg: String, val layout: LayoutInspectorPayload)

  /** The first node in the captured tree matching [predicate], depth-first. */
  private fun LayoutInspectorPayload.node(
    predicate: (LayoutInspectorNode) -> Boolean
  ): LayoutInspectorNode {
    fun walk(node: LayoutInspectorNode): LayoutInspectorNode? =
      if (predicate(node)) node else node.children.firstNotNullOfOrNull(::walk)
    return requireNotNull(walk(root)) { "no node in the captured tree matched" }
  }

  /** A drawn element's box, in the SVG's own pixel space. */
  private data class SvgBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString() = "($left,$top)-($right,$bottom)"
  }

  /**
   * The first `<rect>` / `<image>` whose attributes match [paint], as a box.
   *
   * Deliberately not an XML parse: attribute order is the emitter's business, and every other
   * `FigmaSvg*RenderTest` here reads the emitted string the same way.
   */
  private fun boxOf(svg: String, paint: String): SvgBox? {
    val element =
      Regex("""<(?:rect|image)[^>]*>""")
        .findAll(svg)
        .map { it.value }
        .firstOrNull { Regex(paint).containsMatchIn(it) } ?: return null
    fun attr(name: String) =
      Regex("""\b$name="(-?[\d.]+)"""").find(element)?.groupValues?.get(1)?.toDouble()?.toInt()
    val x = attr("x") ?: return null
    val y = attr("y") ?: return null
    val w = attr("width") ?: return null
    val h = attr("height") ?: return null
    return SvgBox(x, y, x + w, y + h)
  }

  private fun export(previewId: String, content: @Composable () -> Unit): Export {
    // The catalog's own canvas: 420dp wide at density 1, so the emitted coordinates are the px the
    // published render is measured in and Glimmer's 12dp content padding is 12px.
    RuntimeEnvironment.setQualifiers("w420dp-h375dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    lateinit var export: Export
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.setContent { InspectableGlimmerContent(slotTables, content) }
          rule.waitForIdle()
          val frameFile = File(rootDir, "$previewId-frame.png")
          frameFile.parentFile?.mkdirs()
          rule.onRoot().captureRoboImage(file = frameFile)
          val semanticsRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val previewContext =
            PreviewContext.Builder(
                previewId = previewId,
                backend = null,
                renderMode = null,
                outputBaseName = previewId,
              )
              .rootForTest(semanticsRoot.root as RootForTest)
              .addSlotTables(slotTables.toList())
              .parameterInformationCollected()
              .build()
          val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density = 1f)!!
          val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = semantics,
            density = 1f,
            // A frame is supplied on purpose: it is what enables the hybrid raster fallback, so a
            // paint the token model cannot flatten becomes an `<image>` rather than vanishing.
            frameImage = frameFile,
          )
          export = Export(File(rootDir, "$previewId/compose-figma.svg").readText(), layout)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return export
  }
}

/** The SDK `RobolectricHost.ANDROID_SDK` pins for the production render host. */
private const val SDK = 35

/** The catalog's header brush intrinsic: a large square, so `FillWidth` really does scale it. */
private const val HEADER_INTRINSIC = 1000f

/** `GlimmerTheme.shapes.medium`, which `CardDefaults.shape` resolves to. */
private val CardShape = RoundedCornerShape(36.dp)

/** `androidx.xr.glimmer.HeaderShape`. */
private val HeaderShape = RoundedCornerShape(24.dp)

/**
 * `GlimmerTheme.componentSpacingValues.medium`, which `CardDefaults.contentPadding` resolves to.
 */
private val CardContentPadding = 12.dp

/** `GlimmerTheme.colors.surface`, and the tone `SurfaceDefaults.focusedColor` derives from it. */
private val SurfaceColor = Color(0xFF303030)
private val FocusedSurfaceColor = Color(0xFF9BBFFF)

/**
 * A stand-in leading icon, declared in **black** on purpose.
 *
 * Built here rather than taken from `material-icons-core` so this test's only new dependency is
 * Glimmer itself — and, more to the point, so the source colour is a stated fact of the fixture.
 * Every Material `ImageVector` is authored black too, which is why an untinted export of one all
 * but disappears on a dark Glimmer surface; asserting the emitted path is white only means
 * something because the vector handed in is not.
 */
private val SenderIcon: ImageVector =
  ImageVector.Builder(
      name = "Sender",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        lineTo(22f, 22f)
        lineTo(2f, 22f)
        close()
      }
    }
    .build()

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableGlimmerContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
