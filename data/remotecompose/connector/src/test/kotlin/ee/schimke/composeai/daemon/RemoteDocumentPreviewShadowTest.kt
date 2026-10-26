@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemoteDocumentPreview
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.captureAndroidxRemoteDocument
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Pins [ShadowRemoteDocumentPreview] (#401): its shape against upstream, so a reshaped upstream
 * fails here rather than silently falling back to the stock player that ignores named values, and
 * that a preview calling upstream `RemoteDocumentPreview(bytes)` reaches it and consumes the seed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowRemoteDocumentPreview::class])
class RemoteDocumentPreviewShadowTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `the shadow targets the upstream file class by name`() {
    val implements = ShadowRemoteDocumentPreview::class.java.getAnnotation(Implements::class.java)
    assertNotNull(implements)
    assertEquals(ShadowRemoteDocumentPreview.TARGET_CLASS, implements!!.className)
    assertEquals(
      upstreamDocumentOverload().declaringClass.name,
      ShadowRemoteDocumentPreview.TARGET_CLASS,
    )
  }

  @Test
  fun `the shadow method matches the upstream RemoteDocument overload's JVM signature`() {
    val upstream = upstreamDocumentOverload()
    val shadow =
      ShadowRemoteDocumentPreview::class.java.declaredMethods.single {
        it.name == "RemoteDocumentPreview" && JvmModifier.isStatic(it.modifiers)
      }
    assertArrayEquals(
      "Robolectric dispatches by parameter types — a mismatch leaves the shadow unused",
      upstream.parameterTypes,
      shadow.parameterTypes,
    )
    assertEquals(upstream.returnType, shadow.returnType)
    assertTrue(shadow.isAnnotationPresent(Implementation::class.java))
  }

  @Test
  fun `a preview playing document bytes consumes the seeded named values`() {
    val bytes = runBlocking {
      val metrics = rule.activity.resources.displayMetrics
      captureAndroidxRemoteDocument(
        rule.activity,
        metrics.widthPixels,
        metrics.heightPixels,
        metrics.densityDpi,
        1f,
        RemoteDensitySelection.configured,
        RcPlatformProfiles.ANDROIDX,
      ) {
        RemoteBox()
      }
    }
    RemoteComposeController.set(
      RemoteComposeOverride.Builder()
        .also { it.namedValues = mapOf("fill" to RemoteNamedValue.ColorValue("#FF00D775")) }
        .build()
    )
    RemoteComposeController.clearDeclarations()
    assertFalse(RemoteComposeController.namedValuesWereConsumed())

    rule.setContent { RemoteDocumentPreview(bytes) }
    rule.waitForIdle()

    assertTrue(
      "the ByteArray overload must reach the shadowed player and consume the seed",
      RemoteComposeController.namedValuesWereConsumed(),
    )
  }

  private fun upstreamDocumentOverload(): Method =
    Class.forName(ShadowRemoteDocumentPreview.TARGET_CLASS).declaredMethods.single {
      it.name == "RemoteDocumentPreview" &&
        JvmModifier.isPublic(it.modifiers) &&
        it.parameterTypes.firstOrNull()?.name ==
          "androidx.compose.remote.player.core.RemoteDocument"
    }
}
