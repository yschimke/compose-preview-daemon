package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties.Names as DaemonPropertiesNames
import ee.schimke.composeai.daemon.protocol.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-render work trace (compose-preview-server#1181): a desktop render reports, through
 * [RenderResult.metrics] and [RenderWorkTraceMetrics.fromMetrics], exactly the post-capture
 * processors that actually ran on its frame, in order — which is what `renderFinished.workTrace`
 * carries to the client.
 */
class RenderEngineWorkTraceTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun redSquareSpec(outputBaseName: String) =
    RenderSpec(
      previewId = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare",
      className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
      functionName = "RedSquare",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = true,
      outputBaseName = outputBaseName,
    )

  /** A post-capture processor that records each call, and optionally throws. */
  private class RecordingProcessor(
    id: String,
    private val calls: MutableList<String>,
    private val fail: Boolean = false,
  ) : PostCaptureProcessor {
    override val id: DataExtensionId = DataExtensionId(id)
    override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
    override val constraints: DataExtensionConstraints = DataExtensionConstraints()

    override fun process(context: ExtensionPostCaptureContext) {
      calls += id.value
      if (fail) error("${id.value} blew up")
    }
  }

  /** Planned but not a post-capture processor, so the engine's loop must not run or report it. */
  private class NotAProcessor : PlannedDataExtension {
    override val id: DataExtensionId = DataExtensionId("test/not-a-processor")
    override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AroundComposable)
    override val constraints: DataExtensionConstraints = DataExtensionConstraints()
  }

  @Test
  fun `the trace lists the processors that actually ran, in order`() {
    val calls = CopyOnWriteArrayList<String>()
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        dataArtifactExtensions =
          listOf(
            RecordingProcessor("test/first", calls),
            NotAProcessor(),
            // A processor that throws still ran — and still cost the render its time.
            RecordingProcessor("test/throws", calls, fail = true),
            RecordingProcessor("test/last", calls),
          ),
      )

    val result = engine.render(redSquareSpec("work-trace-custom"), requestId = 1L)

    val trace = RenderWorkTraceMetrics.fromMetrics(result.metrics)
    assertNotNull("a desktop render must record a work trace: ${result.metrics}", trace)
    val expected = listOf("test/first", "test/throws", "test/last")
    assertEquals("the processors the engine actually invoked", expected, calls.toList())
    assertEquals(expected, trace!!.processors)
    assertEquals(expected, trace.dataKinds)
    assertEquals(expected.toSet(), trace.stepMs!!.keys)
    assertTrue("step times are non-negative", trace.stepMs!!.values.all { it >= 0L })
  }

  @Test
  fun `a render that runs no processor records an empty trace, not a missing one`() {
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        dataArtifactExtensions = emptyList(),
      )

    val result = engine.render(redSquareSpec("work-trace-empty"), requestId = 1L)

    val trace = RenderWorkTraceMetrics.fromMetrics(result.metrics)
    assertNotNull("recorded, and nothing ran: ${result.metrics}", trace)
    assertEquals(emptyList<String>(), trace!!.processors)
    assertEquals(emptyList<String>(), trace.dataKinds)
  }

  @Test
  fun `the default processor set is reported, figma-svg included`() {
    // Without a `postCaptureKinds` allowlist this engine gates nothing: it pays for the figma-svg
    // export on every render, and the trace has to say so — that visibility is the point of it.
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))

    val result = engine.render(redSquareSpec("work-trace-default"), requestId = 1L)

    val processors = RenderWorkTraceMetrics.fromMetrics(result.metrics)?.processors
    assertEquals(
      listOf(
          ComposeSemanticsExtension.ID,
          ComposeSemanticsWireframeExtension.ID,
          LayoutInspectorExtension.ID,
          ComposeFigmaSvgExtension.ID,
        )
        .map { it.value },
      processors,
    )
    assertTrue("compose/figma-svg" in processors.orEmpty())
  }

  @Test
  fun `a postCaptureKinds allowlist runs only the allowlisted and requested processors`() {
    val calls = CopyOnWriteArrayList<String>()
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        dataArtifactExtensions =
          listOf(
            RecordingProcessor("compose/semantics", calls),
            RecordingProcessor("compose/semantics-wireframe", calls),
            RecordingProcessor("layout/inspector", calls),
            RecordingProcessor("compose/figma-svg", calls),
          ),
      )

    val result =
      withPostCaptureKinds("compose/semantics,layout/inspector") {
        engine.render(
          // A subscription or fetch adds its kind on top of the allowlist.
          redSquareSpec("work-trace-allowlist")
            .copy(requestedDataKinds = setOf("compose/figma-svg")),
          requestId = 1L,
        )
      }

    val ran = listOf("compose/semantics", "layout/inspector", "compose/figma-svg")
    assertEquals(ran, calls.toList())
    assertEquals(ran, RenderWorkTraceMetrics.fromMetrics(result.metrics)!!.processors)
    assertTrue(PostCaptureGate.wasSkipped(result.metrics, "compose/semantics-wireframe"))
  }

  @Test
  fun `without an allowlist the requested kinds gate nothing on desktop`() {
    val calls = CopyOnWriteArrayList<String>()
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        dataArtifactExtensions =
          listOf(
            RecordingProcessor("compose/semantics", calls),
            RecordingProcessor("compose/figma-svg", calls),
          ),
      )

    // An unsubscribed `renderNow` arrives with an empty (not null) requested set; existing clients
    // that read sidecars straight off disk must still get every one of them.
    withPostCaptureKinds(null) {
      engine.render(
        redSquareSpec("work-trace-no-allowlist").copy(requestedDataKinds = emptySet()),
        requestId = 1L,
      )
    }

    assertEquals(listOf("compose/semantics", "compose/figma-svg"), calls.toList())
  }

  private fun <T> withPostCaptureKinds(value: String?, block: () -> T): T {
    val name = DaemonPropertiesNames.POST_CAPTURE_KINDS
    val previous = System.getProperty(name)
    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    try {
      return block()
    } finally {
      if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
  }
}
