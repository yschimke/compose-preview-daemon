package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A manifest-declared `renderOutput` routes the Android daemon's still to that file
 * (compose-ai-tools#6114), and the real Robolectric render writes it and reports it as `pngPath`.
 * Twin of `:daemon:desktop`'s `PreviewManifestRouterDeclaredOutputTest`.
 */
class PreviewManifestRouterDeclaredOutputTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val manifestJson =
    """
    { "previews": [
        { "id": "red-square",
          "className": "com.example.PreviewsKt", "functionName": "RedSquare",
          "widthPx": 32, "heightPx": 32, "density": 1.0,
          "captures": [{"renderOutput": "declared/nested/red-square.png"}] },
        { "id": "rows",
          "className": "com.example.PreviewsKt", "functionName": "Rows",
          "previewParameterProviderClassName": "com.example.TintProvider",
          "captures": [{"renderOutput": "declared/rows.png"}] },
        { "id": "timed",
          "className": "com.example.PreviewsKt", "functionName": "Timed",
          "captures": [
            {"renderOutput": "declared/timed_TIME_0ms.png", "advanceTimeMillis": 0},
            {"renderOutput": "declared/timed_TIME_500ms.png", "advanceTimeMillis": 500}
          ] },
        { "id": "undeclared",
          "className": "com.example.PreviewsKt", "functionName": "Plain" }
    ] }
    """
      .trimIndent()

  private fun manifestFile(): File =
    tempFolder.newFolder("manifest").resolve("previews.json").apply { writeText(manifestJson) }

  private fun router(manifest: File, withDir: Boolean = true) =
    PreviewManifestRouter(
      PreviewManifestRouter.loadManifest(manifest),
      manifestDir = if (withDir) manifest.parentFile else null,
    )

  private fun PreviewManifestRouter.routed(previewId: String): RenderTarget.Spec =
    routeTarget(RenderTarget.Preview(previewId = previewId)) as RenderTarget.Spec

  @Test
  fun `a declared still routes to its path relative to the manifest`() {
    val manifest = manifestFile()
    val routed = router(manifest).routed("red-square")
    assertEquals(
      manifest.parentFile.resolve("declared/nested/red-square.png").absolutePath,
      routed.outputPath,
    )
    // The stem still keys data products, history and logs exactly as before.
    assertEquals("red-square", routed.spec.outputBaseName)
  }

  @Test
  fun `a row gets its own declared file`() {
    val manifest = manifestFile()
    val router = router(manifest)
    assertEquals(
      manifest.parentFile.resolve("declared/rows.png").absolutePath,
      router.routed("rows").outputPath,
    )
    assertEquals(
      manifest.parentFile.resolve("declared/rows_Dark.png").absolutePath,
      router.routed("rows_Dark").outputPath,
    )
  }

  @Test
  fun `an ambiguous or absent declaration keeps the default output`() {
    val manifest = manifestFile()
    assertNull(router(manifest).routed("timed").outputPath)
    assertNull(router(manifest).routed("undeclared").outputPath)
    assertNull(router(manifest, withDir = false).routed("red-square").outputPath)
  }

  @Test
  fun `the declared path survives the sandbox classloader crossing`() {
    val manifest = manifestFile()
    val routed = router(manifest).routed("red-square")
    assertEquals(routed, RenderTarget.decode(RenderTarget.encode(routed)))
  }

  @Test
  fun `the render writes the declared file and reports it as pngPath`() {
    val outputDir = tempFolder.newFolder("renders")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val manifest =
      tempFolder.newFolder("real-manifest").resolve("previews.json").apply {
        writeText(
          """
          { "previews": [
              { "id": "red-square",
                "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                "functionName": "RedSquare",
                "params": {"widthDp": 32, "heightDp": 32, "density": 1.0},
                "captures": [{"renderOutput": "declared/nested/red-square.png"}] }
          ] }
          """
            .trimIndent()
        )
      }
    val host =
      PreviewManifestRouter(
        PreviewManifestRouter.loadManifest(manifest),
        manifestDir = manifest.parentFile,
      )
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Preview(previewId = "red-square")),
          timeoutMs = 120_000,
        )
      val declared = manifest.parentFile.resolve("declared/nested/red-square.png")
      assertEquals(declared.absolutePath, result.artifact.pathOrNull())
      assertTrue("declared PNG must exist", declared.isFile && declared.length() > 0)
      assertFalse(
        "the default output must not be written as well",
        outputDir.resolve("red-square.png").exists(),
      )
    } finally {
      host.shutdown()
    }
  }
}
