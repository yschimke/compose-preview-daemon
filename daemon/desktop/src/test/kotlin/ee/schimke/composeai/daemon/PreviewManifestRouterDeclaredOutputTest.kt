package ee.schimke.composeai.daemon

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A manifest-declared `renderOutput` is where the desktop daemon writes the still, and what
 * `pngPath` reports (compose-ai-tools#6114). Before this the daemon always wrote
 * `<outputDir>/<outputBaseName>.png`, and the Bazel CLI copied it to the declared path afterwards.
 * Twin of `:daemon:android`'s `PreviewManifestRouterDeclaredOutputTest`.
 */
class PreviewManifestRouterDeclaredOutputTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val manifestJson =
    """
    { "previews": [
        { "id": "red-square",
          "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          "functionName": "RedSquare",
          "widthPx": 32, "heightPx": 32, "density": 1.0,
          "captures": [{"renderOutput": "declared/nested/red-square.png"}] },
        { "id": "rows",
          "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          "functionName": "RedSquare",
          "previewParameterProviderClassName": "com.example.TintProvider",
          "captures": [{"renderOutput": "declared/rows.png"}] },
        { "id": "timed",
          "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          "functionName": "RedSquare",
          "captures": [
            {"renderOutput": "declared/timed_TIME_0ms.png", "advanceTimeMillis": 0},
            {"renderOutput": "declared/timed_TIME_500ms.png", "advanceTimeMillis": 500}
          ] },
        { "id": "undeclared",
          "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          "functionName": "RedSquare" }
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

  private fun PreviewManifestRouter.routedOutput(previewId: String): String? =
    (routeTarget(RenderTarget.Preview(previewId = previewId)) as RenderTarget.Spec).outputPath

  @Test
  fun `a declared still routes to its path relative to the manifest`() {
    val manifest = manifestFile()
    assertEquals(
      manifest.parentFile.resolve("declared/nested/red-square.png").absolutePath,
      router(manifest).routedOutput("red-square"),
    )
  }

  @Test
  fun `a row gets its own declared file`() {
    val manifest = manifestFile()
    val router = router(manifest)
    assertEquals(
      manifest.parentFile.resolve("declared/rows.png").absolutePath,
      router.routedOutput("rows"),
    )
    assertEquals(
      manifest.parentFile.resolve("declared/rows_PARAM_1.png").absolutePath,
      router.routedOutput("rows_PARAM_1"),
    )
  }

  @Test
  fun `an ambiguous or absent declaration keeps the default output`() {
    val manifest = manifestFile()
    assertNull(router(manifest).routedOutput("timed"))
    assertNull(router(manifest).routedOutput("undeclared"))
    assertNull(router(manifest, withDir = false).routedOutput("red-square"))
  }

  @Test
  fun `the render writes the declared file and reports it as pngPath`() {
    val outputDir = tempFolder.newFolder("renders")
    val manifest = manifestFile()
    val host =
      PreviewManifestRouter(
        PreviewManifestRouter.loadManifest(manifest),
        engine = RenderEngine(outputDir = outputDir),
        manifestDir = manifest.parentFile,
      )
    host.start()
    try {
      val declared = host.submit(RenderRequest.Render(target = RenderTarget.Preview("red-square")))
      val declaredFile = manifest.parentFile.resolve("declared/nested/red-square.png")
      assertEquals(declaredFile.absolutePath, declared.artifact.pathOrNull())
      assertTrue("declared PNG must exist", declaredFile.isFile)
      assertEquals(32, ImageIO.read(declaredFile).width)
      assertFalse(
        "the default output must not be written as well",
        outputDir.resolve("red-square.png").exists(),
      )

      val fallback = host.submit(RenderRequest.Render(target = RenderTarget.Preview("undeclared")))
      assertEquals(outputDir.resolve("undeclared.png").absolutePath, fallback.artifact.pathOrNull())
    } finally {
      host.shutdown()
    }
  }
}
