package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preview declared in an `object` (compose-preview-daemon#343) is an instance method; with a
 * partial knob seed it still has to go through the defaults mask, on its receiver, so the unseeded
 * primitive takes its author default instead of a null that `Method.invoke` rejects.
 */
class InstancePreviewMaskTest {
  @Test
  fun `a partial seed on an object preview fills the unseeded default on its receiver`() {
    val method =
      InstanceMaskFixtures::class
        .java
        .getDeclaredComposableMethod("Sized", Int::class.javaPrimitiveType!!, String::class.java)
    var driven = false
    ImageComposeScene(16, 16) {
        driven =
          PreviewParameterSupport.invokeWithDefaultMask(
            method,
            InstanceMaskFixtures,
            listOf(null, "seeded"),
            currentComposer,
          )
      }
      .use { it.render() }
    assertTrue("the masked path must drive an instance method given its receiver", driven)
    assertEquals(40, InstanceMaskFixtures.lastSize)
    assertEquals("seeded", InstanceMaskFixtures.lastLabel)
  }
}

object InstanceMaskFixtures {
  var lastSize = -1
  var lastLabel = ""

  @Composable
  fun Sized(sizeDp: Int = 40, label: String = "default") {
    lastSize = sizeDp
    lastLabel = label
  }
}
