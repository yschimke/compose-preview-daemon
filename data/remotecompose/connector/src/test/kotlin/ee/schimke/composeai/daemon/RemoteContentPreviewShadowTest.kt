@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.tooling.preview.RemoteContentPreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.render.IrSidecarChannel
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * Pins [ShadowRemoteContentPreview], the interception that lets a project's own `@PreviewWrapper` —
 * one that calls upstream `RemoteContentPreview(profile = …)` itself rather than being the upstream
 * `RemotePreviewWrapper` [RemoteComposeWrapperSubstitution] swaps — still capture its `.rc`
 * document and play it through [RemoteOverridablePreview] (compose-ai-tools#6116).
 *
 * Two halves. The shape checks are what keep the shadow honest across `compose-remote` bumps:
 * Robolectric matches a shadow method by name and parameter types, so an upstream signature change
 * would leave this shadow silently unused and every custom wrapper back on the stock player. The
 * composition check then drives a custom wrapper end to end under a sandbox with the shadow
 * registered, the way the daemon's `SandboxHoldingRunner` registers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowRemoteContentPreview::class])
class RemoteContentPreviewShadowTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    IrSidecarChannel.setCurrentPreviewId(null)
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `the shadow targets the upstream file class by name`() {
    val implements = ShadowRemoteContentPreview::class.java.getAnnotation(Implements::class.java)
    assertNotNull(implements)
    assertEquals(ShadowRemoteContentPreview.TARGET_CLASS, implements!!.className)
    assertEquals(
      "TARGET_CLASS must name the class upstream RemoteContentPreview compiles into",
      upstreamRemoteContentPreview().declaringClass.name,
      ShadowRemoteContentPreview.TARGET_CLASS,
    )
  }

  @Test
  fun `the shadow method matches the upstream composable's JVM signature`() {
    val upstream = upstreamRemoteContentPreview()
    val shadow =
      ShadowRemoteContentPreview::class.java.declaredMethods.single {
        it.name == "RemoteContentPreview" && JvmModifier.isStatic(it.modifiers)
      }
    assertArrayEquals(
      "Robolectric dispatches by parameter types — a mismatch leaves the shadow unused",
      upstream.parameterTypes,
      shadow.parameterTypes,
    )
    assertEquals(upstream.returnType, shadow.returnType)
    assertTrue(
      "the static bridge must carry @Implementation for Robolectric to use it",
      shadow.isAnnotationPresent(Implementation::class.java),
    )
  }

  @Test
  fun `a custom wrapper calling RemoteContentPreview captures and plays through the connector`() {
    RemoteComposeController.clearDeclarations()
    IrSidecarChannel.setCurrentPreviewId(PREVIEW_ID)

    rule.setContent { CustomCenteringWrapper { RemoteBox() } }
    rule.waitForIdle()

    val capture = IrSidecarChannel.consume(PREVIEW_ID)
    assertNotNull(
      "the custom wrapper's document must reach the IR sidecar (compose/remotecompose-doc)",
      capture,
    )
    assertEquals(IrSidecarChannel.FORMAT_REMOTECOMPOSE, capture!!.format)
    assertTrue("captured .rc bytes must not be empty", capture.bytes.isNotEmpty())

    val declarations = RemoteComposeController.declarationsJson()
    assertNotNull("playback must go through RemoteOverridablePreview's player", declarations)
    val payload =
      Json.decodeFromString(RemoteComposeDeclarationsPayload.serializer(), declarations!!)
    assertNotNull("the connector records which player drew the capture", payload.capturePlayer)
  }

  /**
   * The shape compose-ai-tools#6116 reports: a project wrapper that adds its own scaffolding and
   * passes a `Profile` explicitly, ending in upstream `RemoteContentPreview`.
   */
  @Composable
  private fun CustomCenteringWrapper(content: @Composable @RemoteComposable () -> Unit) {
    RemoteContentPreview(profile = RcPlatformProfiles.ANDROIDX) { content() }
  }

  private fun upstreamRemoteContentPreview(): Method =
    Class.forName("androidx.compose.remote.tooling.preview.RemoteContentPreviewKt")
      .declaredMethods
      .single { it.name == "RemoteContentPreview" && JvmModifier.isPublic(it.modifiers) }

  private companion object {
    const val PREVIEW_ID = "custom-wrapper-preview"
  }
}
