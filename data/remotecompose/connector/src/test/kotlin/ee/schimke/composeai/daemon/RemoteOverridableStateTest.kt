@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.alpha
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.captureAndroidxRemoteDocument
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import kotlinx.coroutines.runBlocking
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
 * Pins the `rememberOverridableRemote*` wrappers: each binds a named value the captured document
 * carries and records its knob declaration, on whichever Remote Compose line this module is built
 * with — and its bytecode binds to nothing that moved between alpha23 and androidx-main.
 *
 * The last part is what failed in the field: a connector built against alpha23 called
 * `RemoteFloatKt.rememberNamedRemoteFloat(String, Domain, Function1, …)` and its string / dp
 * siblings, which androidx-main moved into `RemoteStateKt` with value defaults, so every knob threw
 * `NoSuchMethodError` for a consumer on the newer line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteOverridableStateTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `each wrapper binds a named value in the document and declares its knob`() {
    RemoteComposeController.clearDeclarations()
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
        val label = rememberOverridableRemoteString("knobLabel", "Hello")
        val tint = rememberOverridableRemoteColor("knobTint", Color(0xFF336699))
        val progress = rememberOverridableRemoteFloat("knobProgress", 0.5f)
        val side = rememberOverridableRemoteDp("knobSide", 24.dp)
        RemoteBox(modifier = RemoteModifier.background(tint).size(side).alpha(progress)) {
          RemoteText(label)
        }
      }
    }

    val document = String(bytes, Charsets.ISO_8859_1)
    for (name in listOf("knobLabel", "knobTint", "knobProgress", "knobSide")) {
      assertTrue("the document must carry the named value $name", name in document)
    }
    assertEquals(
      listOf(
        RemoteComposeKnobDeclaration("knobLabel", RemoteNamedValue.StringValue("Hello")),
        RemoteComposeKnobDeclaration("knobTint", RemoteNamedValue.ColorValue("#FF336699")),
        RemoteComposeKnobDeclaration("knobProgress", RemoteNamedValue.FloatValue(0.5f)),
        RemoteComposeKnobDeclaration("knobSide", RemoteNamedValue.DpValue(24f)),
      ),
      RemoteComposeController.declarations(),
    )
  }

  @Test
  fun `the wrappers do not bind to the rememberNamedRemote composables`() {
    val classFile =
      requireNotNull(
        RemoteOverridableStateTest::class
          .java
          .classLoader!!
          .getResourceAsStream("ee/schimke/composeai/daemon/RemoteOverridableStateKt.class"),
      )
    val constants = classFile.use { String(it.readBytes(), Charsets.ISO_8859_1) }
    assertFalse(
      "rememberNamedRemote* changed owner and signature between alpha23 and alpha24; bind to the " +
        "RemoteX.createNamedRemoteX companion factories, which did not",
      "rememberNamedRemote" in constants,
    )
  }
}
