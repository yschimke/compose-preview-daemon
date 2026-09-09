package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.OverrideSeed
import ee.schimke.composeai.data.overrides.OverrideSeedKind
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The desktop half of issue #3616 — a synthetic `_VARIANT_` preview must carry its
 * `@OverrideVariant` seed through [PreviewManifestRouter], not compose its base state.
 *
 * #3652 fixed this on `:daemon:android` only, and the router is production on **both** backends
 * (the gradle plugin sets `composeai.harness.previewsManifest` unconditionally — the "harness"
 * prefix is historical; see `ComposePreviewTasks.kt`). So every desktop / CMP catalog kept
 * publishing base-state `compose-figma.svg`, `layout-inspector.json` and `compose-semantics.json`
 * for each variant beside a correct PNG — the PNG comes from the standalone renderer, which seeds
 * the controller itself. yschimke/m3-catalog#201 is that divergence: a disabled button whose
 * exported vector is the enabled container, and a `size=l` cell exported at the small size.
 *
 * The inbound target for a batch render names a previewId and nothing else, so the seed can only
 * reach the engine off the manifest entry.
 */
class PreviewManifestRouterOverrideVariantTest {

  private fun entry(id: String, overrides: OverrideVariantSpec? = null) =
    PreviewManifestEntry(
      id = id,
      className = "com.example.ButtonsKt",
      functionName = "FilledButton",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      overrides = overrides,
    )

  private fun disabledSeed() =
    OverrideVariantSpec(
      name = "disabled",
      seeds = listOf(OverrideSeed(key = "state", kind = OverrideSeedKind.STRING, raw = "disabled")),
    )

  /** Routes [previewId] with [overrides] and unwraps the resolved spec. */
  private fun PreviewManifestRouter.routedSpec(
    previewId: String,
    overrides: PreviewOverrides? = null,
  ): RenderSpec =
    (routeTarget(RenderTarget.Preview(previewId = previewId, overrides = overrides))
        as RenderTarget.Spec)
      .spec

  @Test
  fun `a variant entry's baked seed reaches the routed RenderSpec`() {
    val id = "FilledButton_Light_VARIANT_disabled"
    val router =
      PreviewManifestRouter(PreviewManifest(previews = listOf(entry(id, disabledSeed()))))

    val spec = router.routedSpec(id)

    assertEquals(
      PreviewOverrideValue.StringValue("disabled"),
      spec.overrides?.namedOverrides?.get("state"),
    )
  }

  @Test
  fun `an ordinary entry routes with no overrides`() {
    val router =
      PreviewManifestRouter(PreviewManifest(previews = listOf(entry("FilledButton_Light"))))

    val spec = router.routedSpec("FilledButton_Light")

    assertNull(spec.overrides)
  }

  @Test
  fun `a live knob edit wins per key and leaves the rest of the seed standing`() {
    val id = "FilledButton_Light_VARIANT_disabled"
    val seed =
      OverrideVariantSpec(
        name = "disabled",
        seeds =
          listOf(
            OverrideSeed(key = "state", kind = OverrideSeedKind.STRING, raw = "disabled"),
            OverrideSeed(key = "shape", kind = OverrideSeedKind.STRING, raw = "square"),
          ),
      )
    val router = PreviewManifestRouter(PreviewManifest(previews = listOf(entry(id, seed))))
    val live =
      PreviewOverrides(namedOverrides = mapOf("shape" to PreviewOverrideValue.StringValue("round")))

    val spec = router.routedSpec(id, overrides = live)

    val named = spec.overrides?.namedOverrides.orEmpty()
    assertEquals(PreviewOverrideValue.StringValue("round"), named["shape"])
    assertEquals(PreviewOverrideValue.StringValue("disabled"), named["state"])
  }

  @Test
  fun `a variant entry seeds the interactive spec resolver too`() {
    val id = "FilledButton_Light_VARIANT_disabled"
    val entries = listOf(entry(id, disabledSeed()))

    val spec = PreviewManifestRouter.manifestPreviewSpecResolver(entries.associateBy { it.id })(id)

    assertEquals(
      PreviewOverrideValue.StringValue("disabled"),
      spec?.overrides?.namedOverrides?.get("state"),
    )
  }
}
