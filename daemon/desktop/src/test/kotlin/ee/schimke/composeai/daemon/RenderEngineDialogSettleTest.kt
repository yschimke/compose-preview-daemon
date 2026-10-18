package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenderEngineDialogSettleTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `auto settled Material dialog reaches its opaque surface`() {
    val unsettled = render("unsettled", null)
    val early = render("early", """{"afterMs":80,"maxMs":1000}""")
    val exact = render("exact", """{"afterMs":1000,"maxMs":1000}""")
    val auto = render("auto", """{"afterMs":0,"maxMs":1000}""")
    assertTrue("an explicit early phase must stay mid-entrance", opaqueSurfacePixels(early) < 1000)
    assertTrue(
      "control reproduces the unannotated early capture",
      opaqueSurfacePixels(unsettled) < 1000,
    )
    assertTrue("auto settle must reach the opaque surface", opaqueSurfacePixels(auto) > 1000)
    assertEquals(
      "auto settle and an exact late phase reach the same surface",
      opaqueSurfacePixels(exact),
      opaqueSurfacePixels(auto),
    )
  }

  private fun opaqueSurfacePixels(image: BufferedImage): Int =
    (0 until image.height).sumOf { y ->
      (0 until image.width).count { x -> image.getRGB(x, y) == 0xFF00C853.toInt() }
    }

  private fun render(name: String, settle: String?): BufferedImage {
    val previous = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    val manifest = tempFolder.newFile("$name-previews.json")
    val settleField = if (settle == null) "" else ",\"settle\":$settle"
    manifest.writeText(
      """{"previews":[{"id":"Dialog","className":"ee.schimke.composeai.daemon.RenderEngineDialogSettleTestKt","functionName":"InitiallyOpenDialog","captures":[{"renderOutput":"Dialog.png"$settleField}]}]}"""
    )
    System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, manifest.absolutePath)
    try {
      val engine = RenderEngine(outputDir = tempFolder.newFolder("renders-$name"))
      val result =
        engine.render(
          RenderSpec(
            previewId = "Dialog",
            className = "ee.schimke.composeai.daemon.RenderEngineDialogSettleTestKt",
            functionName = "InitiallyOpenDialog",
            widthPx = 412,
            heightPx = 915,
            density = 1f,
            outputBaseName = "Dialog",
          ),
          1L,
          javaClass.classLoader,
        )
      val png = File(result.artifact.pathOrNull()!!)
      val keep =
        File(
          "build/dialog-settle-evidence/${System.getProperty("composeai.dialog.evidenceLine", "production")}/$name.png"
        )
      keep.parentFile.mkdirs()
      png.copyTo(keep, overwrite = true)
      val image = ImageIO.read(png)
      val green = opaqueSurfacePixels(image)
      println("Dialog $name: ${image.width}x${image.height}, opaque surface pixels=$green")
      return image
    } finally {
      if (previous == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
      else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previous)
    }
  }
}

@Suppress("unused")
@Composable
fun InitiallyOpenDialog() {
  MaterialTheme {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0E11))) {
      AlertDialog(
        onDismissRequest = {},
        title = { Text("Settled dialog") },
        text = { Text("The dialog surface should be fully opaque.") },
        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
        containerColor = Color(0xFF00C853),
      )
    }
  }
}
