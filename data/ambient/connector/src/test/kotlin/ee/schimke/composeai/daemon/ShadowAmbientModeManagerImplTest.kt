package ee.schimke.composeai.daemon

import android.app.Activity
import androidx.wear.compose.foundation.AmbientMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for issue #84: the factory implementation must not load Wear Services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowAmbientModeManagerImpl::class])
class ShadowAmbientModeManagerImplTest {

  @Test
  fun `constructor bypasses Wear Services and reads the controller`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val implementation =
      Class.forName("androidx.wear.compose.foundation.AmbientModeManagerImpl")
        .getDeclaredConstructor(Activity::class.java)
        .apply { isAccessible = true }
        .newInstance(activity)

    val currentMode =
      implementation.javaClass
        .getDeclaredMethod("getCurrentAmbientMode")
        .apply { isAccessible = true }
        .invoke(implementation)

    assertEquals(AmbientMode.Interactive, currentMode)
  }
}
