package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeFigmaSvgDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-figma-svg-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `protocol preview id resolves fetch and attachments for latest concrete output`() {
    val protocolPreviewId = "com.example.SharedPreview"
    val lightSvg = writeSvg("shared-light", "light")
    val darkSvg = writeSvg("shared-dark", "dark")
    val registry = ComposeFigmaSvgDataProductRegistry(rootDir)

    registry.onRender(
      protocolPreviewId,
      RenderResult(
        id = 1,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "shared-light",
      ),
    )
    assertEquals(lightSvg.absolutePath, fetchPath(registry, protocolPreviewId))
    assertEquals(
      lightSvg.absolutePath,
      registry
        .attachmentsFor(protocolPreviewId, setOf(ComposeFigmaSvgDataProducer.KIND))
        .single()
        .path,
    )

    registry.onRender(
      protocolPreviewId,
      RenderResult(
        id = 2,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "shared-dark",
      ),
    )
    assertEquals(darkSvg.absolutePath, fetchPath(registry, protocolPreviewId))
    assertEquals(
      darkSvg.absolutePath,
      registry
        .attachmentsFor(protocolPreviewId, setOf(ComposeFigmaSvgDataProducer.KIND))
        .single()
        .path,
    )
    assertTrue("the prior variant artifact must remain isolated", lightSvg.exists())
  }

  @Test
  fun `a render that skipped the on-demand export re-renders on fetch instead of serving stale svg`() {
    val previewId = "com.example.Preview"
    val svg = writeSvg("preview", "stale")
    val registry = ComposeFigmaSvgDataProductRegistry(rootDir)
    val kind = ComposeFigmaSvgDataProducer.KIND

    registry.onRender(
      previewId,
      RenderResult(
        id = 1,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "preview",
        metrics = mapOf("tookMs" to 5L, PostCaptureGate.skippedMetricKey(kind) to 0L),
      ),
    )
    val skipped = registry.fetch(previewId, kind, params = null, inline = false)
    assertEquals(DataProductRegistry.Outcome.RequiresRerender(mode = ""), skipped)
    assertTrue(registry.attachmentsFor(previewId, setOf(kind)).isEmpty())

    // The fetch-driven re-render requests the kind, so the export runs and the file is fresh.
    registry.onRender(
      previewId,
      RenderResult(
        id = 2,
        classLoaderHashCode = 0,
        classLoaderName = "test",
        outputBaseName = "preview",
        metrics = mapOf("tookMs" to 5L, PostCaptureGate.ranMetricKey(kind) to 42L),
      ),
    )
    assertEquals(svg.absolutePath, fetchPath(registry, previewId))
  }

  @Test
  fun `a missing export asks for a re-render`() {
    val registry = ComposeFigmaSvgDataProductRegistry(rootDir)
    assertEquals(
      DataProductRegistry.Outcome.RequiresRerender(mode = ""),
      registry.fetch("com.example.Missing", ComposeFigmaSvgDataProducer.KIND, null, false),
    )
  }

  private fun writeSvg(outputBaseName: String, marker: String): File =
    rootDir
      .resolve(outputBaseName)
      .also { it.mkdirs() }
      .resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
      .also { it.writeText("<svg data-variant=\"$marker\"/>") }

  private fun fetchPath(registry: ComposeFigmaSvgDataProductRegistry, previewId: String): String? {
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = ComposeFigmaSvgDataProducer.KIND,
        params = null,
        inline = false,
      )
    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    return (outcome as DataProductRegistry.Outcome.Ok).result.path
  }
}
