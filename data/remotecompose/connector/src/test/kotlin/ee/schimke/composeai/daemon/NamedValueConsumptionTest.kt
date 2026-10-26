package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A `LocalRemoteComposeHost` read consumes only the name it reads (#401 review): reading
 * `namedFloat("progress")` must not hide that a `WearM3.primary` override nothing read was dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NamedValueConsumptionTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  private val override: RemoteComposeOverride =
    RemoteComposeOverride.Builder()
      .also {
        it.namedValues =
          mapOf(
            "progress" to RemoteNamedValue.FloatValue(0.5f),
            "WearM3.primary" to RemoteNamedValue.ColorValue("#FF00D775"),
          )
      }
      .build()

  @Composable
  private fun Seeded(content: @Composable () -> Unit) {
    val extension = remember { RemoteComposeOverrideExtension(override) }
    extension.Around(
      ExtensionComposeContext(
        extensionId = RemoteComposeOverrideExtension.ID,
        previewId = PREVIEW_ID,
        renderMode = null,
      )
    ) {
      content()
    }
  }

  private fun renderedWarnings(): List<String> {
    val registry = RemoteComposeDataProductRegistry()
    registry.onRender(
      previewId = PREVIEW_ID,
      result =
        RenderResult(
          id = 1L,
          classLoaderHashCode = 0,
          classLoaderName = "test",
          previewContext = context(),
        ),
      overrides = PreviewOverrides(remoteCompose = override),
      previewContext = context(),
    )
    val outcome = registry.fetch(PREVIEW_ID, RemoteComposeDataProductRegistry.KIND, null, true)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!
    return Json.decodeFromJsonElement(RemoteComposePayload.serializer(), payload).warnings
  }

  @Test
  fun `reading one named value does not consume an unread override`() {
    rule.setContent { Seeded { LocalRemoteComposeHostRead("progress") } }
    rule.waitForIdle()

    val warnings = renderedWarnings()
    assertEquals(1, warnings.size)
    assertTrue(warnings.single(), warnings.single().contains("WearM3.primary"))
    assertFalse(
      "the value the preview did read was applied: ${warnings.single()}",
      warnings.single().contains("progress"),
    )
  }

  @Test
  fun `reading every named value consumes them all`() {
    rule.setContent {
      Seeded {
        LocalRemoteComposeHostRead("progress")
        LocalRemoteComposeHostRead("WearM3.primary")
      }
    }
    rule.waitForIdle()

    assertTrue(renderedWarnings().isEmpty())
  }

  @Composable
  private fun LocalRemoteComposeHostRead(name: String) {
    val host = LocalRemoteComposeHost.current
    if (name.startsWith("WearM3.")) host.namedColor(name, "#FF000000")
    else host.namedFloat(name, 0f)
  }

  private fun context(): PreviewContext =
    PreviewContext(
      previewId = PREVIEW_ID,
      backend = "test",
      renderMode = null,
      outputBaseName = null,
    )

  private companion object {
    const val PREVIEW_ID = "named-consumption"
  }
}
