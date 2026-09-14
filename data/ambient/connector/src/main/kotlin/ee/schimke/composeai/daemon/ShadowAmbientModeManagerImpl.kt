package ee.schimke.composeai.daemon

import android.app.Activity
import androidx.wear.compose.foundation.AmbientMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for Wear Compose's private `AmbientModeManagerImpl`.
 *
 * `rememberAmbientModeManager()` constructs this implementation before a caller can provide its
 * result through `LocalAmbientModeManager`. The real constructor immediately enters the Wear OS
 * system-only `com.google.wear.services.ambient` API, which is deliberately absent from ordinary
 * Android SDK and Robolectric classpaths. The connector includes descriptor-only linkage shims for
 * those private types because Robolectric resolves the real implementation's declared members
 * before attaching this shadow. Replacing the constructor and lifecycle methods avoids executing
 * that unavailable system seam; [getCurrentAmbientMode] then reads the same controller as
 * [AmbientOverrideExtension], so factory and composition-local callers observe one state.
 */
@Implements(
  className = "androidx.wear.compose.foundation.AmbientModeManagerImpl",
  isInAndroidSdk = false,
)
class ShadowAmbientModeManagerImpl {

  @Suppress("FunctionName", "UNUSED_PARAMETER")
  @Implementation
  protected fun __constructor__(activity: Activity) = Unit

  @Implementation fun getCurrentAmbientMode(): AmbientMode = AmbientStateController.modeState.value

  @Implementation fun startListening() = Unit

  @Implementation fun stopListening() = Unit

  companion object {
    const val SHADOW_FQN: String = "ee.schimke.composeai.daemon.ShadowAmbientModeManagerImpl"
  }
}
