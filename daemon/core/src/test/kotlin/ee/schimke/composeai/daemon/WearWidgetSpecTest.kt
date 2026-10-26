package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [WearWidgetSpec] — the Wear widget carousel slot a watch-device render draws inside (#402). */
class WearWidgetSpecTest {

  @Test
  fun `no device, or a device that is not a watch, has no slot`() {
    val spec = WearWidgetSpec()
    assertNull(spec.slotFor(null))
    assertNull(spec.slotFor(""))
    assertNull(spec.slotFor("id:pixel_5"))
  }

  @Test
  fun `the default large squircle takes the screen-class entry for the device`() {
    val spec = WearWidgetSpec()
    // wearos_small_round is 192dp, below the 225dp breakpoint; large_round is 227dp.
    assertEquals(WearWidgetSlot(166f, 96f, 26f), spec.slotFor("id:wearos_small_round"))
    assertEquals(WearWidgetSlot(200f, 108f, 26f), spec.slotFor("id:wearos_large_round"))
    // A `spec:` device counts when it is round, even without "wear" in it.
    assertEquals(
      WearWidgetSlot(200f, 108f, 26f),
      spec.slotFor("spec:width=240dp,height=240dp,dpi=320,isRound=true"),
    )
  }

  @Test
  fun `the glance-wear provider names the slot shape and container`() {
    val pkg = "androidx.glance.wear.tooling.preview."
    assertEquals(
      WearWidgetSpec(WearWidgetSpec.Shape.SQUIRCLE, WearWidgetSpec.Container.SMALL),
      WearWidgetSpec.fromProvider(pkg + "SquircleSmallWidgetPreviewParams"),
    )
    assertEquals(
      WearWidgetSpec(WearWidgetSpec.Shape.ROUND, WearWidgetSpec.Container.LARGE),
      WearWidgetSpec.fromProvider(pkg + "RoundLargeWidgetPreviewParams"),
    )
    assertEquals(
      WearWidgetSpec(WearWidgetSpec.Shape.RECTANGULAR, WearWidgetSpec.Container.LARGE),
      WearWidgetSpec.fromProvider(pkg + "RectangularAllWidgetPreviewParams"),
    )
    // No provider, or an app's own: the default large squircle.
    assertEquals(WearWidgetSpec(), WearWidgetSpec.fromProvider(null))
    assertEquals(WearWidgetSpec(), WearWidgetSpec.fromProvider("com.example.RoundSmallParams"))

    assertEquals(
      WearWidgetSlot(200f, 60f, 999f),
      WearWidgetSpec.fromProvider(pkg + "RoundSmallWidgetPreviewParams")
        .slotFor("id:wearos_large_round"),
    )
    assertEquals(
      WearWidgetSlot(168f, 112f, 0f),
      WearWidgetSpec.fromProvider(pkg + "RectangularLargeWidgetPreviewParams")
        .slotFor("id:wearos_square"),
    )
  }

  @Test
  fun `an explicit slot wins over the derived one, but only on a watch`() {
    val spec = WearWidgetSpec(slotWidthDp = 180f, slotHeightDp = 90f, slotCornerRadiusDp = 12f)
    assertEquals(WearWidgetSlot(180f, 90f, 12f), spec.slotFor("id:wearos_small_round"))
    assertNull(spec.slotFor("id:pixel_5"))
    // Half a size is no size: the derived slot applies.
    assertEquals(
      WearWidgetSlot(200f, 108f, 26f),
      WearWidgetSpec(slotWidthDp = 180f).slotFor("id:wearos_large_round"),
    )
  }

  @Test
  fun `the marker survives a device override merge and the sandbox crossing`() {
    val base = RenderSpec(className = "a.B", functionName = "f", wearWidget = WearWidgetSpec())
    val merged =
      base.mergedWith(
        ee.schimke.composeai.daemon.protocol.PreviewOverrides(device = "id:wearos_large_round")
      )
    assertEquals(WearWidgetSpec(), merged.wearWidget)
    assertEquals("id:wearos_large_round", merged.device)
    assertEquals(merged, RenderSpec.decode(RenderSpec.encode(merged)))
  }
}
