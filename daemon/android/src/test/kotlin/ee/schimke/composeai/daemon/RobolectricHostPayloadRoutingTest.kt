package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for manifest-backed `previewId` resolution on the host thread. */
class RobolectricHostPayloadRoutingTest {

  /** Resolves [target] host-side and unwraps the spec the sandbox would receive. */
  private fun RobolectricHost.resolvedSpec(target: RenderTarget): RenderSpec =
    (reshapeRenderTarget(target) as RenderTarget.Spec).spec

  @Test
  fun `reshape forwards preview parameter provider from resolved manifest spec`() {
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.TimePlayButtonKt",
            functionName = "TimePlayButtonPreview",
            previewParameterProviderClassName = "com.example.ThemePreviewParameterProvider",
            previewParameterLimit = 3,
          )
        }
      )

    val spec =
      host.resolvedSpec(preview("time-play-button", PreviewOverrides(uiMode = UiMode.DARK)))

    assertEquals(
      "com.example.ThemePreviewParameterProvider",
      spec.previewParameterProviderClassName,
    )
    assertEquals(3, spec.previewParameterLimit)
    assertEquals(RenderSpec.SpecUiMode.DARK, spec.uiMode)
  }

  @Test
  fun `reshape rotates the discovery-time frame for a device-less orientation request`() {
    // The production Android bundle daemon never mounts a `PreviewManifestRouter` — it reshapes
    // here. A device-less `orientation` arrives with no dimensions for
    // `JsonRpcServer.renderTargetFor` to rotate, so this is the only lane that can turn the
    // preview's own frame. Missing it captured a landscape bitmap while `applyPreviewQualifiers`
    // derived `port` from the same spec (#3552 review).
    val host = host(widthPx = 800, heightPx = 400)

    val spec = host.resolvedSpec(preview("p", PreviewOverrides(orientation = Orientation.PORTRAIT)))

    assertEquals(400, spec.widthPx)
    assertEquals(800, spec.heightPx)
    assertEquals(RenderSpec.SpecOrientation.PORTRAIT, spec.orientation)
  }

  @Test
  fun `reshape leaves a frame already in the requested orientation alone`() {
    val host = host(widthPx = 400, heightPx = 800)

    val spec = host.resolvedSpec(preview("p", PreviewOverrides(orientation = Orientation.PORTRAIT)))

    assertEquals(400, spec.widthPx)
    assertEquals(800, spec.heightPx)
  }

  @Test
  fun `reshape lets explicit pixels outrank the orientation request`() {
    val host = host(widthPx = 800, heightPx = 400)

    val spec =
      host.resolvedSpec(
        preview(
          "p",
          PreviewOverrides(widthPx = 1000, heightPx = 200, orientation = Orientation.PORTRAIT),
        )
      )

    assertEquals(1000, spec.widthPx)
    assertEquals(200, spec.heightPx)
  }

  @Test
  fun `reshape trades the wrap axis with a rotated frame`() {
    // wrapWidth/wrapHeight name an axis, so rotating the frame without trading them measures and
    // crops the axis that is no longer the free one.
    val host = host(widthPx = 800, heightPx = 400, wrapHeight = true)

    val routed =
      host.resolvedSpec(preview("p", PreviewOverrides(orientation = Orientation.PORTRAIT)))

    assertTrue("rotated frame should now wrap width: $routed", routed.wrapWidth)
    assertFalse("...and no longer wrap height: $routed", routed.wrapHeight)
  }

  @Test
  fun `held-session overrides trade the wrap axis with a rotated frame`() {
    // `applyOverrides` is the interactive / recording lane. It copies the merged dimensions onto
    // the base spec, so without consuming `merged.rotated` the wrap flags stayed on the old axis
    // and the held-session measure-and-crop pass sized the wrong one (#3552 review).
    val host = host(widthPx = 800, heightPx = 400, wrapHeight = true)

    val spec =
      host.applyOverridesForTest(
        RenderSpec(
          previewId = "p",
          className = "com.example.PlainPreviewKt",
          functionName = "PlainPreview",
          widthPx = 800,
          heightPx = 400,
          wrapHeight = true,
        ),
        PreviewOverrides(orientation = Orientation.PORTRAIT),
      )

    assertEquals(400, spec.widthPx)
    assertEquals(800, spec.heightPx)
    assertTrue("rotated frame should now wrap width", spec.wrapWidth)
    assertFalse("...and no longer wrap height", spec.wrapHeight)
  }

  private fun host(
    widthPx: Int,
    heightPx: Int,
    wrapWidth: Boolean = false,
    wrapHeight: Boolean = false,
  ) =
    RobolectricHost(
      previewSpecResolver = {
        RenderSpec(
          previewId = it,
          className = "com.example.PlainPreviewKt",
          functionName = "PlainPreview",
          widthPx = widthPx,
          heightPx = heightPx,
          wrapWidth = wrapWidth,
          wrapHeight = wrapHeight,
        )
      }
    )

  @Test
  fun `reshape omits preview parameter tokens for an ordinary preview`() {
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.PlainPreviewKt",
            functionName = "PlainPreview",
          )
        }
      )

    val routed = host.resolvedSpec(preview("plain"))

    assertNull(routed.previewParameterProviderClassName)
    assertEquals(Int.MAX_VALUE, routed.previewParameterLimit)
  }
}
