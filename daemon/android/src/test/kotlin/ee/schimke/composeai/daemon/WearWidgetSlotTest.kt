package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Wear widget preview forced onto a watch `device` renders inside its carousel slot, centred on
 * the frame, instead of across the whole screen under the round clip (#402).
 *
 * The marker is the manifest entry's `widget` object, which compose-ai-tools discovery already
 * writes. [RedSquare] stands in for the widget player: like `RemoteDocumentPreview` it fills
 * whatever it is given, so the screen it paints red is the box the renderer handed it.
 */
class WearWidgetSlotTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun entry(widgetJson: String?, kind: String? = null): PreviewManifestEntry {
    val widget = widgetJson?.let { ""","widget":$it""" } ?: ""
    val kindJson = kind?.let { ""","kind":"$it"""" } ?: ""
    return json.decodeFromString(
      PreviewManifestEntry.serializer(),
      """{"id":"w","className":"a.B","functionName":"f","params":{"density":2.0,""" +
        """"previewParameterProviderClassName":""" +
        """"androidx.glance.wear.tooling.preview.RoundSmallWidgetPreviewParams"$kindJson}""" +
        "$widget}",
    )
  }

  @Test
  fun `discovery's wear widget object marks the spec`() {
    val spec = entry("""{"host":"wear","profile":"wear-widgets"}""").renderSpec()
    assertEquals(
      WearWidgetSpec(WearWidgetSpec.Shape.ROUND, WearWidgetSpec.Container.SMALL),
      spec.wearWidget,
    )
  }

  @Test
  fun `explicit slot fields are read and fields this daemon does not know are ignored`() {
    val spec =
      entry(
          """{"host":"wear","slotWidthDp":180,"slotHeightDp":90,"slotCornerRadiusDp":12,""" +
            """"futureField":{"x":1}}"""
        )
        .renderSpec()
    assertEquals(WearWidgetSlot(180f, 90f, 12f), spec.wearWidget?.slotFor("id:wearos_small_round"))
  }

  @Test
  fun `no widget, a launcher widget, an unknown host or a non-Compose kind is not marked`() {
    assertNull(entry(null).renderSpec().wearWidget)
    assertNull(entry("""{"host":"launcher"}""").renderSpec().wearWidget)
    assertNull(entry("""{"host":"car"}""").renderSpec().wearWidget)
    assertNull(entry("""{}""").renderSpec().wearWidget)
    assertNull(entry("""{"host":"wear"}""", kind = "TILE").renderSpec().wearWidget)
  }

  @Test
  fun `a wear widget on a watch device renders inside its slot`() {
    val image = render(widget = PreviewWidgetEntry(host = "wear"), device = "id:wearos_large_round")
    // 227dp at density 2 → a 454px frame; the default large squircle slot on a large screen is
    // 200×108dp → 400×216px, centred: x 27..427, y 119..335.
    assertEquals(454, image.width)
    assertEquals(454, image.height)
    assertRed(image, 227, 227, "slot centre")
    assertRed(image, 40, 227, "slot left edge, mid-height")
    assertRed(image, 414, 227, "slot right edge, mid-height")
    assertNotRed(image, 227, 80, "above the slot, inside the round screen")
    assertNotRed(image, 227, 374, "below the slot, inside the round screen")
    // The slot's own 26dp corner is clipped, as the carousel draws it.
    assertNotRed(image, 29, 121, "slot's top-left corner")
  }

  @Test
  fun `without the marker the device frame is unchanged`() {
    val image = render(widget = null, device = "id:wearos_large_round")
    assertEquals(454, image.width)
    assertRed(image, 227, 227, "centre")
    assertRed(image, 227, 80, "above where a slot would be")
  }

  @Test
  fun `a wear widget without a device override keeps its own size`() {
    // Discovery strips a widget's device, so this is the ordinary sticker render: the marker is
    // inert and the 64dp frame is filled.
    val image = render(widget = PreviewWidgetEntry(host = "wear"), device = null)
    assertEquals(128, image.width)
    assertRed(image, 4, 4, "corner of the unframed render")
  }

  private fun render(widget: PreviewWidgetEntry?, device: String?): BufferedImage {
    val outputDir = tempFolder.newFolder()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = "wear-widget",
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
              params = PreviewParamsEntry(widthDp = 64, heightDp = 64, density = 2.0f),
              widget = widget,
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            target = preview("wear-widget", device?.let { PreviewOverrides(device = it) })
          ),
          timeoutMs = 120_000,
        )
      val path = result.artifact.pathOrNull()
      assertNotNull("render must produce a PNG", path)
      return ImageIO.read(File(path!!)) ?: error("PNG failed to decode")
    } finally {
      host.shutdown()
    }
  }

  private fun isRed(argb: Int): Boolean {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return a > 200 && r in 0xD0..0xFF && g in 0x30..0x70 && b in 0x30..0x70
  }

  private fun assertRed(image: BufferedImage, x: Int, y: Int, where: String) =
    assertTrue(
      "$where ($x,$y) should be the widget's red, was #${Integer.toHexString(image.getRGB(x, y))}",
      isRed(image.getRGB(x, y)),
    )

  private fun assertNotRed(image: BufferedImage, x: Int, y: Int, where: String) =
    assertTrue(
      "$where ($x,$y) should be outside the widget, was #${Integer.toHexString(image.getRGB(x, y))}",
      !isRed(image.getRGB(x, y)),
    )
}
