package ee.schimke.composeai.daemon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A recomposition must not replay the gesture hint.
 *
 * The framework re-evaluates the hint a second after a gesture target registers, and calls
 * `onGestureAvailable` whenever the system says to show it. With the shadow armed the system always
 * says yes (the "Always" hint setting), so a target that re-registered on each recomposition played
 * its hint on every live-data update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSdkGestureInputManager::class])
class ReportedGestureRecompositionTest {

  @get:Rule val compose = createComposeRule()

  @After
  fun resetController() {
    GestureStateController.resetForNewSession()
  }

  @Test
  fun `recomposing the target does not replay its hint`() {
    GestureStateController.armDetection(true)
    var hints = 0
    var tick by mutableIntStateOf(0)
    compose.setContent {
      val config = rememberOneHandedGestureConfiguration(OneHandedGestureAction.Primary)
      val interactions = remember { MutableInteractionSource() }
      // Read the tick here so every change recomposes the caller, as a live value does.
      val size = (40 + tick % 2).dp
      Box(
        Modifier.size(size).reportedOneHandedGesture(
          type = GestureType.SCROLL,
          label = "Next card",
          gestureConfiguration = config,
          showIndicator = { hints++ },
          interactionSource = interactions,
        ) {}
      )
    }
    settle()
    assertEquals("the hint shows once when the target appears", 1, hints)

    repeat(3) {
      tick++
      settle()
    }
    assertEquals("recompositions leave the hint alone", 1, hints)
  }

  /** Runs past the framework's one-second hint delay, which waits on a real-time clock. */
  private fun settle() {
    val until = System.currentTimeMillis() + 1_500
    while (System.currentTimeMillis() < until) {
      compose.waitForIdle()
      ShadowLooper.idleMainLooper()
      Thread.sleep(50)
    }
    compose.waitForIdle()
  }
}
