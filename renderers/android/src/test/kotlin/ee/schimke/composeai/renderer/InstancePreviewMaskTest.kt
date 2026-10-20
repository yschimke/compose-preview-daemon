package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A preview declared in an `object` (compose-preview-daemon#343) is an instance method; with a
 * partial knob seed it still has to go through the defaults mask, on its receiver, so the unseeded
 * primitive takes its author default instead of a null that `Method.invoke` rejects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstancePreviewMaskTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `a partial seed on an object preview fills the unseeded default on its receiver`() {
    val method =
      InstanceMaskFixtures::class
        .java
        .getDeclaredComposableMethod("Sized", Int::class.javaPrimitiveType!!, String::class.java)
    var driven = false
    rule.setContent {
      driven =
        PreviewParameterSupport.invokeWithDefaultMask(
          method,
          InstanceMaskFixtures,
          listOf(null, "seeded"),
          currentComposer,
        )
    }
    rule.waitForIdle()
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
