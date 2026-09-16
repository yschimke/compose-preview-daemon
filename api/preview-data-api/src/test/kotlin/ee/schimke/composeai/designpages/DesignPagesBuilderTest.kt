package ee.schimke.composeai.designpages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The builders are the construction API for the design-pages wire types.
 *
 * They exist for binary compatibility, not ergonomics. These types are consumed as **published
 * binaries** by compose-ai-tools and, through it, by compose-preview-server: adding a property to a
 * Kotlin data class removes the old `<init>` and `copy$default` signatures, so a consumer compiled
 * against the previous release dies at the call site. That is not hypothetical --
 * `NoSuchMethodError: DesignPage.copy$default(...)` out of `ServeDesignPageStore.drawablePages()`
 * is what 3.5.0's two added fields did to render-host 2.15.0.
 *
 * With the constructors `internal` and `@ConsistentCopyVisibility` making the generated `copy`
 * internal with them, neither is reachable from a consumer, so neither can be the thing that
 * breaks. Growth lands on a Builder setter instead, which only ever adds a method.
 */
class DesignPagesBuilderTest {

  private fun page() =
    DesignPage.Builder(
        id = "buttons",
        name = "Buttons",
        nodeId = "1:0",
        frame = PageFrame.Builder(2000.0, 1000.0).build(),
        image = PageImage.Builder("buttons.svg").build(),
      )
      .build()

  @Test
  fun `a builder fills the same defaults the constructor did`() {
    val built = page()
    assertEquals(emptyList(), built.nodes)
    assertEquals(true, built.inventory)
    assertEquals(emptyList(), built.background)
    assertEquals(PageBlendMode.SOURCE_OVER, built.designBlend)
    assertEquals(PageBlendMode.SOURCE_OVER, built.renderBlend)
    assertEquals("svg", built.image.format)
  }

  @Test
  fun `newBuilder round-trips every property`() {
    // The guarantee that makes `newBuilder()` a safe replacement for `copy()`: carrying a value
    // across must not quietly drop the properties the caller did not mention. A property added to
    // the class and forgotten in `newBuilder` would fail here rather than in a consumer's render.
    val original =
      page()
        .newBuilder()
        .also {
          it.nodes = listOf(PageNode.Builder("1:1").also { n -> n.name = "Filled" }.build())
          it.inventory = false
          it.background = listOf(PageLayerPlacement.Builder("a".repeat(64), 2000.0, 1000.0).build())
          it.designBlend = PageBlendMode.SCREEN
          it.renderBlend = PageBlendMode.PLUS_LIGHTER
        }
        .build()

    assertEquals(original, original.newBuilder().build())
  }

  @Test
  fun `newBuilder derives a modified page without touching the original`() {
    // The exact derivation render-host used `copy(nodes = ...)` for.
    val original = page()
    val derived =
      original.newBuilder().also { it.nodes = listOf(PageNode.Builder("1:1").build()) }.build()

    assertEquals(emptyList(), original.nodes)
    assertEquals(1, derived.nodes.size)
    assertNotEquals(original, derived)
    assertEquals(original.id, derived.id)
  }

  @Test
  fun `a manifest builder round-trips its pages and assets`() {
    val manifest =
      DesignPagesManifest.Builder(version = DESIGN_PAGES_VERSION, fileKey = "k")
        .also {
          it.pages = listOf(page())
          it.assets =
            listOf(PageAsset.Builder("b".repeat(64), "assets/b.png", "png", 10, 10, 100L).build())
        }
        .build()

    assertEquals(manifest, manifest.newBuilder().build())
    assertEquals(1, manifest.pages.size)
    assertEquals(1, manifest.assets.size)
  }
}
