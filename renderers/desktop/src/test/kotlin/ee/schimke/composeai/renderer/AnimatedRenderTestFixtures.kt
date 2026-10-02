package ee.schimke.composeai.renderer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Test fixture for [DesktopAnimatedRendererTest] — a white dot sweeping left-to-right across a
 * black field once per second, so successive captured frames are visually distinct at any frame
 * interval ≥ ~30ms. Top-level so the renderer can reflect it the way it reflects a consumer's
 * `@Preview` (`Class.forName("…AnimatedRenderTestFixturesKt")` + `getDeclaredComposableMethod`).
 */
@Composable
fun SweepingDot() {
  val transition = rememberInfiniteTransition(label = "sweep")
  val fraction by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
      label = "fraction",
    )
  Box(modifier = Modifier.size(64.dp).background(Color.Black)) {
    Box(
      modifier =
        Modifier.offset(x = 48.dp * fraction, y = 24.dp).size(16.dp).background(Color.White)
    )
  }
}

/** Width, in px, [LinearClockRuler]'s bar travels over one [LINEAR_CLOCK_PERIOD_MS] loop. */
const val LINEAR_CLOCK_TRAVEL_PX = 1000

/** One full sweep of [LinearClockRuler]: 1000 px in 1000 ms, so a pixel is a millisecond. */
const val LINEAR_CLOCK_PERIOD_MS = 1000

/**
 * Test fixture for [DesktopAnimatedFrameTimingTest] — a frame clock you can read off the pixels. A
 * white bar sweeps [LINEAR_CLOCK_TRAVEL_PX] px across a black strip, linearly, once per
 * [LINEAR_CLOCK_PERIOD_MS], restarting at the left edge. At `density = 1` its left edge sits at `x
 * = t mod 1000` px, so the distance it moves between two captured frames *is* the animation time
 * between them, in milliseconds — exactly what a GIF frame delay has to agree with.
 */
@Composable
fun LinearClockRuler() {
  val transition = rememberInfiniteTransition(label = "ruler")
  val fraction by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          tween(durationMillis = LINEAR_CLOCK_PERIOD_MS, easing = LinearEasing),
          RepeatMode.Restart,
        ),
      label = "fraction",
    )
  Box(modifier = Modifier.size((LINEAR_CLOCK_TRAVEL_PX + 8).dp, 4.dp).background(Color.Black)) {
    Box(
      modifier =
        Modifier.offset { IntOffset((LINEAR_CLOCK_TRAVEL_PX * fraction).roundToInt(), 0) }
          .size(8.dp, 4.dp)
          .background(Color.White)
    )
  }
}

/**
 * [LinearClockRuler] behind an indication-free `clickable`, so an `@InteractionPreview` script has
 * a node to aim at. The press changes nothing visible: the bar's position stays a pure read of the
 * animation clock while the interaction renderer steps it.
 */
@Composable
fun ClickableLinearClockRuler() {
  Box(modifier = Modifier.clickable(interactionSource = null, indication = null) {}) {
    LinearClockRuler()
  }
}
