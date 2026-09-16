package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The resolved-token sidecar still reaches disk when the specimen is *composed*, not just when
 * [CatalogTokenSidecar.writeResolved] is called directly.
 *
 * [CatalogTokenSidecarTest] covers the writer; nothing covered the call site. That gap mattered
 * when [ThemeSpecimen] moved its emission from `remember(previewId) { … }` to `SideEffect { … }`:
 * `remember` runs its calculation *during* composition, `SideEffect` only once a composition is
 * successfully applied. Both are "once" for a render that composes a single frame — but that is the
 * claim, and an unproven claim about a data product is how a sidecar silently stops being written.
 *
 * So this composes the real composable through the same Robolectric + Compose rule the renderer
 * uses and asserts the file appears, with the theme's live `MaterialTheme.colorScheme` in it. The
 * other two emission sites in `PreviewRenderStrategy` use the same mechanism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSpecimenSidecarEmissionTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `composing the specimen emits the resolved-token sidecar`() {
    val renders = Files.createTempDirectory("specimen-sidecar").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      composeRule.setContent {
        // A colour that cannot come from the default scheme, so the assertion below proves the
        // sidecar carries *this* composition's resolved roles rather than anything canned.
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFF6F61))) {
          ThemeSpecimen(previewId = "themecatalog__Probe", themeName = "Probe")
        }
      }
      composeRule.waitForIdle()

      val sidecar = CatalogTokenSidecar.pathFor(renders, "themecatalog__Probe")
      assertTrue("sidecar not written at ${sidecar.path}", sidecar.exists())
      val json = sidecar.readText()
      assertTrue(
        "missing previewId in $json",
        json.contains("\"previewId\":\"themecatalog__Probe\""),
      )
      assertTrue("missing theme key in $json", json.contains("\"theme\":\"Probe\""))
      assertTrue(
        "primary role not resolved from the composed theme in $json",
        json.contains("\"hex\":\"#FFFF6F61\""),
      )
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
      renders.parentFile?.deleteRecursively()
    }
  }
}
