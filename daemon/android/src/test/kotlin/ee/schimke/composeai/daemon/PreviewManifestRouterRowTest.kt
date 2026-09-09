package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing coverage for `@PreviewParameter` **row** ids on the Android daemon (issue #3749). Twin of
 * `:daemon:desktop`'s `PreviewManifestRouterRowTest` — the two routers must resolve a row the same
 * way, so keep the cases in lockstep.
 *
 * The manifest the gradle plugin writes carries one entry per parameterized function — discovery
 * reads bytecode and cannot instantiate a provider — so before this a `renderNow` naming a row
 * ("…MyScreenPreview_Light_PARAM_4") died in [PreviewManifestRouter] with *no manifest entry for
 * previewId*, and `serve` / `render_preview` could only ever show value 0. The pixel-level proof
 * that row 1 actually binds the provider's second value is the harness scenario
 * `PreviewParameterAndroidRealModeTest`.
 */
class PreviewManifestRouterRowTest {

  private val provider = "com.example.TintProvider"

  private fun entry(id: String, parameterized: Boolean = true) =
    PreviewManifestEntry(
      id = id,
      className = "com.example.PreviewsKt",
      functionName = "Screen",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      previewParameterProviderClassName = if (parameterized) provider else null,
    )

  private fun router(vararg entries: PreviewManifestEntry) =
    PreviewManifestRouter(PreviewManifest(previews = entries.toList()))

  /** Routes [previewId] and unwraps the resolved spec the router produced. */
  private fun PreviewManifestRouter.routedSpec(
    previewId: String,
    previewParameterRow: String? = null,
  ): RenderSpec {
    val routed =
      routeTarget(
        RenderTarget.Preview(previewId = previewId, previewParameterRow = previewParameterRow)
      )
    return (routed as RenderTarget.Spec).spec
  }

  @Test
  fun `an index-addressed row routes to its base entry and carries the row token`() {
    val spec = router(entry("Screen")).routedSpec("Screen_PARAM_4")
    assertEquals("com.example.PreviewsKt", spec.className)
    assertEquals("Screen", spec.functionName)
    assertEquals(provider, spec.previewParameterProviderClassName)
    assertEquals("PARAM_4", spec.previewParameterRow)
  }

  @Test
  fun `a label-addressed row carries the label verbatim`() {
    assertEquals("Dark", router(entry("Screen")).routedSpec("Screen_Dark").previewParameterRow)
  }

  /**
   * The row render is its own preview downstream: it reports the id the caller asked for, and it
   * writes its own artifact rather than clobbering the base render's PNG.
   */
  @Test
  fun `a row render keeps the requested id and gets its own output stem`() {
    val spec = router(entry("Screen")).routedSpec("Screen_PARAM_2")
    assertEquals("Screen_PARAM_2", spec.previewId)
    assertEquals("Screen_PARAM_2", spec.outputBaseName)
  }

  /** The bare base id is unchanged by all this — no row token, no suffixed stem. */
  @Test
  fun `the base id still renders value 0 with no row token`() {
    val spec = router(entry("Screen")).routedSpec("Screen")
    assertEquals("Screen", spec.previewId)
    assertEquals("Screen", spec.outputBaseName)
    assertNull("base render must not carry a row", spec.previewParameterRow)
  }

  /**
   * `MyScreenPreview_Light_PARAM_4` from the issue: a multi-preview annotation already contributed
   * `_Light`, so the longest parameterized prefix has to win or row 4 of the *Light* variant would
   * render as the bare preview.
   */
  @Test
  fun `the longest parameterized base wins over a shorter one`() {
    val spec = router(entry("Screen"), entry("Screen_Light")).routedSpec("Screen_Light_PARAM_4")
    assertEquals("Screen_Light_PARAM_4", spec.previewId)
    assertEquals("PARAM_4", spec.previewParameterRow)
  }

  /** A preview with no provider has no rows, so nothing can be read as a row token of it. */
  @Test
  fun `an unknown id whose prefix is not parameterized still fails loudly`() {
    val router = router(entry("Screen", parameterized = false))
    val failure = runCatching { router.routedSpec("Screen_Dark") }.exceptionOrNull()
    assertTrue(
      "expected the pre-existing unknown-previewId error, got $failure",
      failure?.message?.contains("no manifest entry for previewId='Screen_Dark'") == true,
    )
  }

  /** An explicit token lets a caller render a row of the bare base id without minting a row id. */
  @Test
  fun `an inbound row token wins over the one parsed out of the id`() {
    val spec = router(entry("Screen")).routedSpec("Screen_PARAM_4", previewParameterRow = "Dark")
    assertEquals("Dark", spec.previewParameterRow)
    assertEquals("Screen_Dark", spec.outputBaseName)
  }

  /** Row addressing is inert for a preview that declares no provider — no token is emitted. */
  @Test
  fun `a non-parameterized preview never gets a row token`() {
    val spec =
      router(entry("Screen", parameterized = false))
        .routedSpec("Screen", previewParameterRow = "Dark")
    assertNull(spec.previewParameterRow)
  }
}
