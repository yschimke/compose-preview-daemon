package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutInspectorSnapshotTest {
  private fun payload(name: String) =
    LayoutInspectorPayload(
      LayoutInspectorNode(
        nodeId = name,
        component = name,
        bounds = LayoutInspectorBounds(0, 0, 100, 100),
        size = LayoutInspectorSize(100, 100),
      )
    )

  @Test
  fun `products share one capture but a new render replaces its output`() {
    val dir = Files.createTempDirectory("layout-snapshot-test").toFile()
    val extension = LayoutInspectorExtension()
    var walks = 0
    fun context(name: String): ExtensionPostCaptureContext {
      val snapshot = LayoutInspectorSnapshot {
        walks++
        payload(name)
      }
      return ExtensionPostCaptureContext(
        extensionId = extension.id,
        previewId = null,
        renderMode = null,
        products = RecordingDataProductStore().scopedFor(extension),
        data =
          ExtensionContextData.of(
            RenderArtifactContextKeys.RootDir provides dir,
            RenderArtifactContextKeys.OutputBaseName provides "same-preview",
            RenderArtifactContextKeys.LayoutSnapshot provides snapshot,
          ),
      )
    }
    try {
      val first = context("First")
      assertEquals(0, walks)
      extension.process(first)
      // The SVG consumer reads through this same helper, without another walk.
      assertSame(first.layoutInspectorPayload(), first.layoutInspectorPayload())
      assertEquals(1, walks)
      val output = dir.resolve("same-preview/${LayoutInspectorDataProducer.FILE}")
      val original = output.readText()
      extension.process(context("Second"))
      assertEquals(2, walks)
      assertNotEquals(original, output.readText())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `missing tree is memoized but failed extraction can be retried`() {
    var missingWalks = 0
    val missing = LayoutInspectorSnapshot {
      missingWalks++
      null
    }
    assertNull(missing.payload)
    assertNull(missing.payload)
    assertEquals(1, missingWalks)

    var attempts = 0
    val expected = payload("Recovered")
    val recovering = LayoutInspectorSnapshot {
      if (++attempts == 1) error("first product failed")
      expected
    }
    assertThrows(IllegalStateException::class.java) { recovering.payload }
    assertSame(expected, recovering.payload)
    assertEquals(2, attempts)
  }
}
