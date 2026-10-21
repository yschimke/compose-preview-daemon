@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A wrap-content Remote Compose preview — no `device`, `widthDp` or `heightDp` — measures to its
 * content in every player, not to the sandbox canvas the document was captured on (#342), while a
 * sized preview keeps the size it asked for.
 *
 * The box below stands in for the daemon's wrap box: it measures its child with a zero minimum on a
 * wrapped axis, and tight on a sized one. At xhdpi a 40dp document is 80px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteWrapContentSizeTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `a wrap-content preview measures to its content in every player`() {
    for (kind in RemoteComposePlayerKind.entries) {
      assertEquals("$kind", IntSize(80, 80), render(kind, Modifier.wrapContentSize()))
    }
  }

  @Test
  fun `a sized preview keeps its size in the view player`() {
    assertEquals(
      IntSize(240, 120),
      render(RemoteComposePlayerKind.VIEW, Modifier.requiredSize(120.dp, 60.dp)),
    )
  }

  private fun render(kind: RemoteComposePlayerKind, box: Modifier): IntSize {
    val tag = "preview-$kind"
    rule.setContent {
      Box(box.testTag(tag), propagateMinConstraints = true) {
        RemoteOverridablePreview(profile = RcPlatformProfiles.ANDROIDX, player = kind) {
          RemoteBox(RemoteModifier.size(40.rdp).background(Color.Red.rc))
        }
      }
    }
    // The view player lays the document out on its first draw and resizes on the next frame.
    repeat(3) {
      rule.mainClock.advanceTimeByFrame()
      rule.waitForIdle()
    }
    val size = rule.onNodeWithTag(tag).fetchSemanticsNode().size
    rule.activityRule.scenario.recreate()
    return size
  }
}
