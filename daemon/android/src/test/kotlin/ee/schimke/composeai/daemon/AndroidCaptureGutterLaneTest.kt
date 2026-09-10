package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@CaptureGutter` on the **live daemon** lane, Android half (issue #4443).
 *
 * Twin of `:daemon:desktop`'s `DesktopCaptureGutterLaneTest`, and deliberately asserting the same
 * arithmetic: the whole point of the change is that the four lanes — batch desktop, batch Android,
 * daemon desktop, daemon Android — grow a guttered capture by the same pixels, so a preview does
 * not change size when a viewer toggles PNG↔Live or a catalog switches backends
 * (RENDER_LANE_PARITY.md).
 */
class AndroidCaptureGutterLaneTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `a declared gutter reaches the spec through the production previews-json path`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "Foo",
          className = "com.example.FooKt",
          methodName = "Foo",
          params =
            PreviewParamsDto(
              density = 2.0f,
              captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5),
            ),
        )
      )
    assertEquals(4, spec.gutterStartDp)
    assertEquals(5, spec.gutterBottomDp)
    assertTrue(spec.hasCaptureGutter())
    // 4+4 dp across and 4+5 dp down at density 2 ⇒ 16 px and 18 px, per-edge rounded — the same
    // pixels the desktop lane resolves for the same annotation.
    assertEquals(16, spec.gutterHorizontalPx())
    assertEquals(18, spec.gutterVerticalPx())
  }

  @Test
  fun `an un-annotated preview keeps a zero gutter`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "Foo",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = PreviewParamsDto(),
        )
      )
    assertTrue(!spec.hasCaptureGutter())
    assertEquals(0, spec.gutterHorizontalPx())
  }

  @Test
  fun `the gutter survives the boundary round-trip, and an older client's spec has none`() {
    val parsed =
      RenderSpec.decode(
        RenderSpec.encode(
          RenderSpec(
            className = "com.example.FooKt",
            functionName = "Foo",
            gutterStartDp = 1,
            gutterTopDp = 2,
            gutterEndDp = 3,
            gutterBottomDp = 4,
          )
        )
      )
    assertEquals(1, parsed.gutterStartDp)
    assertEquals(2, parsed.gutterTopDp)
    assertEquals(3, parsed.gutterEndDp)
    assertEquals(4, parsed.gutterBottomDp)

    // A spec encoded before the field existed carries no gutter keys at all.
    val legacy =
      RenderSpec.decode("""{"className":"com.example.FooKt","functionName":"Foo","legacyKey":1}""")
    assertTrue(!legacy.hasCaptureGutter())
  }

  @Test
  fun `the host-side resolve carries the gutter, which is the lane production takes`() {
    // The router below is the harness lane. The Android bundle daemon and `compose-preview serve`
    // never mount one: they resolve `previewId` through `RobolectricHost.reshapeRenderTarget`,
    // which used to re-serialise the spec into a payload string. That round-trip dropped the gutter
    // (#4822) — and dropped it silently, because the payload parser defaulted every edge to 0 — so
    // any override that forced a request off the baked lane (a theme, a knob, a Remote Compose
    // seed) came back clipped to the composable's own frame. The spec now crosses whole, so the
    // gutter cannot be dropped without dropping the field itself.
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(
            previewId = it,
            className = "com.example.FooKt",
            functionName = "Foo",
            widthPx = 384,
            heightPx = 128,
            density = 2.0f,
            gutterStartDp = 0,
            gutterTopDp = 8,
            gutterEndDp = 0,
            gutterBottomDp = 8,
          )
        }
      )

    val resolved =
      host.reshapeRenderTarget(
        preview("media-podcastcontrolbuttons", PreviewOverrides(uiMode = UiMode.LIGHT))
      )
    assertTrue("the resolve must produce a spec target: $resolved", resolved is RenderTarget.Spec)
    val spec = (resolved as RenderTarget.Spec).spec

    assertTrue("the gutter must survive the resolve", spec.hasCaptureGutter())
    assertEquals(0, spec.gutterStartDp)
    assertEquals(8, spec.gutterTopDp)
    assertEquals(0, spec.gutterEndDp)
    assertEquals(8, spec.gutterBottomDp)
    // 8+8 dp down at density 2 ⇒ 32 px, the difference between the reported 384×160 baked render
    // and the 384×128 the live daemon was returning.
    assertEquals(0, spec.gutterHorizontalPx())
    assertEquals(32, spec.gutterVerticalPx())
  }

  @Test
  fun `the host-side resolve leaves an un-annotated preview without a gutter`() {
    // A preview that declares no gutter must resolve to the all-zero one — the same guarantee the
    // router makes.
    val host =
      RobolectricHost(
        previewSpecResolver = {
          RenderSpec(previewId = it, className = "com.example.FooKt", functionName = "Foo")
        }
      )

    val resolved = host.reshapeRenderTarget(preview("plain"))
    assertTrue(!(resolved as RenderTarget.Spec).spec.hasCaptureGutter())
  }

  @Test
  fun `the router resolves a manifest-declared gutter and omits an absent one`() {
    val entry =
      PreviewManifestEntry(
        id = "sticker",
        className = "com.example.FooKt",
        functionName = "Foo",
        params =
          PreviewParamsEntry(
            captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5)
          ),
      )
    assertEquals(CaptureGutterDto(4, 4, 4, 5), entry.resolved().captureGutter)
    assertTrue(
      PreviewManifestEntry(
          id = "b",
          className = "c",
          functionName = "d",
          params = PreviewParamsEntry(),
        )
        .resolved()
        .captureGutter
        .isEmpty()
    )
  }

  /**
   * The **held-session** lane — `interactive/start`, `recording/start`, `stream/start` — resolves
   * its spec through the router's `previewSpecResolver`, not through `routeTarget`. That resolver
   * did not carry the gutter, so scrubbing a guttered preview in the panel resized it the moment
   * the session took over: exactly the failure the desktop twin's resolver comment describes, on
   * the backend that never got the fix.
   *
   * It stayed hidden because the one-shot lane re-read the gutter off the manifest entry inside the
   * router, so only the held lane was short. With one shared merge there is only the resolved spec,
   * and the gap has nowhere left to hide.
   */
  @Test
  fun `the held-session resolver carries a declared gutter`() {
    val entry =
      PreviewManifestEntry(
        id = "guttered",
        className = "com.example.FooKt",
        functionName = "Foo",
        params =
          PreviewParamsEntry(
            widthDp = 32,
            heightDp = 32,
            density = 1.0f,
            captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5),
          ),
      )

    val spec = entry.renderSpec()

    assertTrue(
      "a held session must compose the same gutter the one-shot render does",
      spec.hasCaptureGutter(),
    )
    assertEquals(4, spec.gutterStartDp)
    assertEquals(4, spec.gutterTopDp)
    assertEquals(4, spec.gutterEndDp)
    assertEquals(5, spec.gutterBottomDp)
    // …and survives the shared override merge untouched, with or without an override in play.
    assertEquals(5, spec.mergedWith(null).gutterBottomDp)
    assertEquals(5, spec.mergedWith(PreviewOverrides(uiMode = UiMode.DARK)).gutterBottomDp)
  }

  @Test
  fun aGutteredRenderGrowsTheCanvasAndLeavesTheComponentAlone() {
    val outputDir = tempFolder.newFolder("renders-gutter")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val bare = renderFixture("bare", captureGutter = null)
    val guttered =
      renderFixture(
        "guttered",
        captureGutter = CaptureGutterDto(start = 4, top = 4, end = 4, bottom = 5),
      )

    // 32×32 dp at density 1 with a 4 dp gutter a side and 5 dp at the bottom ⇒ 40×41.
    assertEquals(bare.width + 8, guttered.width)
    assertEquals(bare.height + 9, guttered.height)
    assertEquals(40, guttered.width)
    assertEquals(41, guttered.height)
  }

  @Test
  fun `a guttered dialog capture keeps the component at its un-guttered scale`() {
    // A fixed-size dialog preview is deliberately rescaled back to its declared Studio frame on
    // this lane (`DialogWindowRenderTest` pins it), and what gets rescaled is the dialog crop. Once
    // that crop carries a gutter, targeting `frame + gutter` would scale the capture by
    // `(frame + gutter) / (dialog + gutter)` rather than the un-guttered `frame / dialog` — so
    // adding the annotation would resize the component. The target has to grow with the crop.
    val engine = RenderEngine(outputDir = tempFolder.newFolder("unused"))
    // The `DialogWindowSurface` fixture is a 64 dp dialog; declared frame 96 px at density 1.
    assertEquals(96, engine.fixedAxisTargetPx(96, 0, 64))
    // 4+4 dp across ⇒ crop 72, scaled by the same 96/64 ⇒ 108. 4+5 dp down ⇒ crop 73 ⇒ 109.5 ⇒ 110.
    assertEquals(108, engine.fixedAxisTargetPx(96, 8, 64))
    assertEquals(110, engine.fixedAxisTargetPx(96, 9, 64))
    // Off the dialog path the capture is already `frame + gutter`, so the resize is a no-op.
    assertEquals(104, engine.fixedAxisTargetPx(96, 8, null))
  }

  @Test
  fun `the viewport qualifier grows by the combined pixel extent, not by quantized dp`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("unused-qualifier"))
    // An un-guttered render must not move at all — the qualifier stays what it was.
    assertEquals(0, engine.gutterQualifierDp(101, 0, 2.0f))
    // 101 px at density 2 truncates to 50 dp for the base. The content plus a 4+4 dp gutter needs
    // 117 px, i.e. 59 dp — so the gutter must contribute 9 dp, not the 8 dp an independently
    // ceilinged gutter would give (58 dp = 116 px leaves the window a pixel short and the layout
    // clamps a gutter pixel away).
    assertEquals(9, engine.gutterQualifierDp(101, 16, 2.0f))
    // The exactly-divisible case is unchanged: 100 px + 16 px = 116 px = 58 dp, base 50 dp ⇒ 8 dp.
    assertEquals(8, engine.gutterQualifierDp(100, 16, 2.0f))
  }

  /**
   * Renders the fixed-size red fixture through the real router → `RenderEngine` path, which is what
   * a `renderNow` from the VS Code panel takes.
   */
  private fun renderFixture(id: String, captureGutter: CaptureGutterDto?): BufferedImage {
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = id,
              className = "ee.schimke.composeai.daemon.PreviewWrapperResolutionFixturesKt",
              functionName = "WrappedFixturePreview",
              params =
                PreviewParamsEntry(
                  widthDp = 32,
                  heightDp = 32,
                  density = 1.0f,
                  captureGutter = captureGutter,
                ),
            )
          )
      )
    val host = PreviewManifestRouter(manifest = manifest)
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Preview(previewId = "$id")),
          timeoutMs = 120_000,
        )
      assertNotNull("$id: pngPath must be populated", result.artifact.pathOrNull())
      val png = File(result.artifact.pathOrNull()!!)
      assertTrue("$id: rendered PNG must exist", png.exists())
      return ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
        ?: error("$id: PNG failed to decode")
    } finally {
      host.shutdown()
    }
  }
}
