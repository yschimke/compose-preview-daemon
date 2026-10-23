package ee.schimke.composeai.renderer

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Which activities an app tour follows: a system screen the app opens, such as the runtime
 * permission dialog an activity asks for on first composition (compose-ai-tools #635), resolves to
 * nothing in the sandbox and is passed over; the app's own intents are always followed, and fail
 * the tour when they don't resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppTourSystemIntentTest {
  private val application: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun aRuntimePermissionRequestIsASystemScreenTheTourPassesOver() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 1)
    val request = Shadows.shadowOf(application).nextStartedActivity
    assertTrue("the request is recorded as a started activity", request != null)
    assertTrue(isUnresolvableSystemIntent(application, request!!))
  }

  @Test
  fun anAppsOwnIntentIsFollowedEvenWhenItDoesNotResolve() {
    val explicit = Intent().setClassName(application, "com.example.NotDeclaredActivity")
    assertFalse(isUnresolvableSystemIntent(application, explicit))
    val scoped = Intent("com.example.OPEN").setPackage(application.packageName)
    assertFalse(isUnresolvableSystemIntent(application, scoped))
  }
}
