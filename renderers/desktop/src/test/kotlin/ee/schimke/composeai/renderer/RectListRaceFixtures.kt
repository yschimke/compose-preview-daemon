package ee.schimke.composeai.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Fixtures for [DesktopUiThreadRectListRaceTest]: the shape that makes Compose Desktop's
 * `RectManager` race visible — a large layout tree that adds and removes nodes every frame, with a
 * layout pass long enough to outlast the 16 ms debounce `RectManager` schedules on the EDT.
 *
 * * [STATIC_NODES] leaves that never recompose keep the `RectList` large, so the EDT's
 *   `defragment()` takes long enough to overlap the render thread's inserts.
 * * Half of [CHURN_NODES] are re-keyed every frame: their removal fragments the list (which is what
 *   arms `defragment()`), and their placement inserts fresh entries all through the pass.
 * * Placement spins [PLACE_SPIN_NANOS] per child, so a pass takes ~20 ms — what a big editor tree
 *   costs on a loaded CI runner, made deterministic.
 */
internal const val STATIC_NODES = 20_000
internal const val CHURN_NODES = 2_000
internal const val CHURN_FRAMES = 12
internal const val PLACE_SPIN_NANOS = 10_000L

/** Churns for [CHURN_FRAMES] frames, then holds still so a settled capture terminates. */
@Suppress("unused") // invoked reflectively by the renderer
@Composable
fun RectListChurnPreview() {
  var frame by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    repeat(CHURN_FRAMES) {
      withFrameNanos {}
      frame++
    }
  }
  RectListChurn(frame)
}

/** The tree itself, for tests that drive the frame counter directly. */
@Composable
fun RectListChurn(frame: Int) {
  Box(Modifier.fillMaxSize()) {
    StaticLeaves(STATIC_NODES)
    ChurningLeaves(frame)
  }
}

@Composable
private fun StaticLeaves(count: Int) {
  Layout(content = { repeat(count) { Box(Modifier.size(1.dp)) } }) { measurables, _ ->
    val placeables = measurables.map { it.measure(Constraints.fixed(1, 1)) }
    layout(256, 256) { placeables.forEachIndexed { i, p -> p.place(i % 256, i / 256 % 256) } }
  }
}

@Composable
private fun ChurningLeaves(frame: Int) {
  Layout(
    content = {
      for (i in 0 until CHURN_NODES) {
        // Odd frames replace the odd children, even frames the even ones: every frame removes
        // CHURN_NODES / 2 nodes and inserts as many fresh ones, interleaved with the survivors.
        key(if (i % 2 == frame % 2) -(frame * CHURN_NODES + i) - 1 else i) {
          Box(Modifier.size(1.dp))
        }
      }
    }
  ) { measurables, _ ->
    val placeables = measurables.map { it.measure(Constraints.fixed(1, 1)) }
    layout(256, 256) {
      placeables.forEachIndexed { i, p ->
        p.placeRelative((i + frame) % 256, (i + frame) / 256 % 256)
        val until = System.nanoTime() + PLACE_SPIN_NANOS
        while (System.nanoTime() < until) {
          // A slow layout pass, deliberately: the race needs placement to outlast the debounce.
        }
      }
    }
  }
}
