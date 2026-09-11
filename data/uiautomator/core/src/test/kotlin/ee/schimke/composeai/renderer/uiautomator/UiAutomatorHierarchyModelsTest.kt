package ee.schimke.composeai.renderer.uiautomator

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAutomatorHierarchyModelsTest {
  @Suppress("DEPRECATION")
  @Test
  fun `schema v2 writes canonical bounds and compatibility alias`() {
    val node =
      UiAutomatorHierarchyNode(
        testTag = "submit",
        testTagAncestors = emptyList(),
        text = "Submit",
        contentDescription = null,
        role = "Button",
        actions = listOf("click"),
        boundsInScreen = "10,20,30,40",
        merged = true,
      )

    val json = Json.encodeToString(node)

    assertTrue(json, json.contains("\"boundsInScreen\":\"10,20,30,40\""))
    assertTrue(json, json.contains("\"boundsInRoot\":\"10,20,30,40\""))
  }
}
