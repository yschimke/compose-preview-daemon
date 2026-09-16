package ee.schimke.composeai.designpages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the **layer** half of [DesignPagesManifest]: shared raster backplates, where a page places
 * them, and how each layer composites.
 *
 * The case that named all of it is the Material 3 Glimmer kit's Buttons sheet — five image-backed
 * backplates under component sets authored `mix-blend-mode: screen`. Excluding the plates to keep
 * the page under its size cap left the retained, screen-blended drawing compositing over a pale
 * fallback fill, washing the whole sheet toward white. So the two features are tested together:
 * carrying the plate once and compositing over it correctly are one requirement, not two.
 */
class DesignPagesAssetsTest {

  private val plateId = "a".repeat(64)
  private val otherId = "b".repeat(64)

  private fun asset(id: String = plateId, width: Int = 2048, height: Int = 1024) =
    PageAsset(
      id = id,
      uri = "assets/$id.png",
      format = PageAsset.PNG,
      width = width,
      height = height,
      bytes = 512L * 1024,
    )

  private fun placement(asset: String = plateId, blend: PageBlendMode = PageBlendMode.SOURCE_OVER) =
    PageLayerPlacement(asset = asset, width = 2048.0, height = 1024.0, blend = blend)

  private fun page(
    background: List<PageLayerPlacement> = listOf(placement()),
    designBlend: PageBlendMode = PageBlendMode.SOURCE_OVER,
  ) =
    DesignPage(
      id = "buttons",
      name = "Buttons",
      nodeId = "1:2",
      frame = PageFrame(2048.0, 1024.0),
      image = PageImage(uri = "buttons.svg"),
      background = background,
      designBlend = designBlend,
    )

  private fun manifest(page: DesignPage = page(), assets: List<PageAsset> = listOf(asset())) =
    DesignPagesManifest(
      version = DESIGN_PAGES_VERSION,
      fileKey = "ocdacdEsnHipMJD3egzxKb",
      pages = listOf(page),
      assets = assets,
    )

  private fun parse(text: String): DesignPagesManifest =
    DesignPagesJson.decodeFromString(DesignPagesManifest.serializer(), text)

  // --- the wire format ------------------------------------------------------

  /**
   * The whole shape the producer writes, quoted rather than round-tripped: a round trip would pass
   * even if the serial names drifted, and the serial names are the contract.
   */
  @Test
  fun `parses a manifest with shared assets and blend modes`() {
    val manifest =
      parse(
        """
        {
          "version": 2,
          "fileKey": "ocdacdEsnHipMJD3egzxKb",
          "assets": [
            {
              "id": "$plateId",
              "uri": "assets/$plateId.png",
              "format": "png",
              "width": 2048,
              "height": 1024,
              "bytes": 524288
            }
          ],
          "pages": [
            {
              "id": "buttons",
              "name": "Buttons",
              "nodeId": "1:2",
              "frame": { "width": 2048.0, "height": 1024.0 },
              "image": { "uri": "buttons.svg", "format": "svg" },
              "designBlend": "screen",
              "renderBlend": "plus-lighter",
              "background": [
                {
                  "asset": "$plateId",
                  "x": 0.0, "y": 0.0, "width": 2048.0, "height": 1024.0,
                  "opacity": 0.9, "fit": "cover", "radius": 24.0, "blend": "source-over"
                }
              ]
            }
          ]
        }
        """
          .trimIndent()
      )

    val page = manifest.pages.single()
    assertEquals(PageBlendMode.SCREEN, page.designBlend)
    assertEquals(PageBlendMode.PLUS_LIGHTER, page.renderBlend)

    val placed = manifest.backgroundFor(page).single()
    assertEquals(plateId, placed.asset)
    assertEquals(0.9, placed.opacity)
    assertEquals(24.0, placed.radius)
    assertEquals(PageBlendMode.SOURCE_OVER, placed.blend)
    assertEquals(PageLayerPlacement.COVER, placed.fit)
  }

  /**
   * Every field here defaults to the behaviour that shipped, which is why this is still version 2:
   * a manifest written before any of it existed describes the same stack it always did.
   */
  @Test
  fun `a manifest without layers is unchanged`() {
    val manifest =
      parse(
        """
        {
          "version": 2,
          "fileKey": "k",
          "pages": [
            {
              "id": "shape", "name": "Shape", "nodeId": "1:2",
              "frame": { "width": 10.0, "height": 10.0 },
              "image": { "uri": "shape.svg" }
            }
          ]
        }
        """
          .trimIndent()
      )

    val page = manifest.pages.single()
    assertTrue(manifest.assets.isEmpty())
    assertTrue(manifest.backgroundFor(page).isEmpty())
    assertEquals(PageBlendMode.SOURCE_OVER, page.designBlend)
    assertEquals(PageBlendMode.SOURCE_OVER, page.renderBlend)
  }

  // --- the blend allowlist --------------------------------------------------

  /**
   * The point of the enum. A mode this build has not vetted must not reach a renderer — and it must
   * not take the other thirty pages down with it either, which is what an uncoerced parse would do
   * from inside the `runCatching` every reader wraps this in.
   */
  @Test
  fun `an unknown blend mode degrades to source-over rather than failing the manifest`() {
    val manifest =
      parse(
        """
        {
          "version": 2, "fileKey": "k",
          "pages": [
            {
              "id": "p", "name": "P", "nodeId": "1:2",
              "frame": { "width": 10.0, "height": 10.0 },
              "image": { "uri": "p.svg" },
              "designBlend": "hue",
              "renderBlend": "url(javascript:alert(1))"
            }
          ]
        }
        """
          .trimIndent()
      )

    val page = manifest.pages.single()
    assertEquals(PageBlendMode.SOURCE_OVER, page.designBlend)
    assertEquals(PageBlendMode.SOURCE_OVER, page.renderBlend)
  }

  /**
   * `source-over` is the Porter-Duff name the wire uses and `normal` is what CSS calls the same
   * thing. A renderer emits [PageBlendMode.css], never the manifest's own bytes — this pins the one
   * mapping where the two spellings differ.
   */
  @Test
  fun `css keywords are the vetted spellings`() {
    assertEquals("normal", PageBlendMode.SOURCE_OVER.css)
    assertEquals("screen", PageBlendMode.SCREEN.css)
    assertEquals("multiply", PageBlendMode.MULTIPLY.css)
    assertEquals("plus-lighter", PageBlendMode.PLUS_LIGHTER.css)
  }

  // --- asset validation -----------------------------------------------------

  @Test
  fun `a well-formed asset is accepted`() {
    assertTrue(asset().isWellFormed)
  }

  /**
   * Refused on the **declaration**, before any I/O. A 64 KB file claiming 40000×40000 is a
   * decompression bomb, and learning that from the decoder is learning it too late.
   */
  @Test
  fun `a declared decompression bomb is refused before it is opened`() {
    assertFalse(asset(width = 40_000, height = 40_000).isWellFormed)
    // Both sides under the per-side cap, the product far over the pixel budget.
    assertFalse(asset(width = 16_000, height = 16_000).isWellFormed)
    assertTrue(asset(width = 8_000, height = 8_000).isWellFormed)
  }

  /** An SVG here would be markup no consumer walked, underneath the sanitized layer. */
  @Test
  fun `only inert raster formats are accepted`() {
    assertTrue(asset().copy(format = PageAsset.JPEG).isWellFormed)
    assertTrue(asset().copy(format = PageAsset.WEBP).isWellFormed)
    assertFalse(asset().copy(format = "svg").isWellFormed)
    assertFalse(asset().copy(format = "svg+xml").isWellFormed)
  }

  /** The id is the content address; anything that is not a SHA-256 cannot be one. */
  @Test
  fun `a non-hash id is refused`() {
    assertFalse(asset(id = "plate").isWellFormed)
    assertFalse(asset(id = "A".repeat(64)).isWellFormed)
    assertFalse(asset(id = "a".repeat(63)).isWellFormed)
  }

  @Test
  fun `a placement must describe a drawable box`() {
    assertTrue(placement().isWellFormed)
    assertFalse(placement().copy(width = 0.0).isWellFormed)
    assertFalse(placement().copy(height = Double.NaN).isWellFormed)
    assertFalse(placement().copy(opacity = 1.5).isWellFormed)
    assertFalse(placement().copy(radius = -1.0).isWellFormed)
    assertFalse(placement().copy(fit = "stretch").isWellFormed)
    assertTrue(placement().copy(fit = PageLayerPlacement.CONTAIN).isWellFormed)
  }

  // --- resolution, reachability, and what `--check` reports ------------------

  /** A malformed asset is invisible to every consumer at once, so nothing resolves to it. */
  @Test
  fun `a placement naming a malformed asset does not draw`() {
    val manifest = manifest(assets = listOf(asset(width = 40_000, height = 40_000)))
    assertTrue(manifest.backgroundFor(manifest.pages.single()).isEmpty())
  }

  @Test
  fun `a placement naming nothing stored does not draw and is reported dangling`() {
    val manifest = manifest(page = page(background = listOf(placement(asset = otherId))))
    assertTrue(manifest.backgroundFor(manifest.pages.single()).isEmpty())
    assertEquals(listOf(otherId), manifest.danglingAssetRefs)
  }

  /**
   * Ids are content hashes, so two records under one id disagree about bytes that cannot differ.
   */
  @Test
  fun `a duplicate id keeps the first record`() {
    val first = asset()
    val second = asset().copy(uri = "assets/other.png")
    assertEquals(first, manifest(assets = listOf(first, second)).assetsById[plateId])
  }

  /** A delivery branch is append-only: a plate that stops being placed is carried forever. */
  @Test
  fun `an unplaced asset is reported unreachable`() {
    val orphan = asset(id = otherId)
    val manifest = manifest(assets = listOf(asset(), orphan))
    assertEquals(listOf(otherId), manifest.unreachableAssets.map { it.id })
    assertEquals(setOf(plateId), manifest.referencedAssetIds)
  }

  /**
   * Reachability reads the raw placements, not the resolvable ones. Otherwise a validator would
   * delete the file a currently-unresolvable placement names, then report that same placement as
   * dangling on the next run.
   */
  @Test
  fun `an id named by an unresolvable placement is still reachable`() {
    val manifest =
      manifest(
        page = page(background = listOf(placement().copy(width = 0.0))),
        assets = listOf(asset()),
      )
    assertTrue(manifest.backgroundFor(manifest.pages.single()).isEmpty())
    assertTrue(manifest.unreachableAssets.isEmpty())
  }

  /** One stored plate, many placements — the property that keeps the Buttons page under its cap. */
  @Test
  fun `one asset serves many placements`() {
    val five = (0 until 5).map { placement().copy(x = it * 100.0) }
    val manifest = manifest(page = page(background = five))
    assertEquals(5, manifest.backgroundFor(manifest.pages.single()).size)
    assertEquals(1, manifest.assets.size)
    assertTrue(manifest.unreachableAssets.isEmpty())
    assertTrue(manifest.danglingAssetRefs.isEmpty())
  }
}
