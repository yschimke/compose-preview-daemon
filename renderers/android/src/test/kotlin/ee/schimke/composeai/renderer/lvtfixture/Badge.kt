package ee.schimke.composeai.renderer.lvtfixture

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp

/**
 * compose-ai-tools#6230's reproducer, verbatim: a default-arguments constructor that inlines `.dp`
 * twice before delegating to `this(...)`. Valid as compiled; once Robolectric's stock instrumentor
 * has moved that code into a new `<init>`, the two `$i$f$getDp` rows it leaves behind are identical
 * and HotSpot refuses to define the class.
 *
 * Its own package so [DedupingParameterizedRobolectricTestRunnerTest] can instrument it and nothing
 * else.
 */
enum class Badge(val width: Dp, val art: DpRect = DpRect(0.dp, 0.dp, width, 24.dp)) {
  A(24.dp),
  B(46.dp, DpRect((-1.4).dp, (-0.6).dp, 46.dp, 24.4.dp)),
  C(24.dp, DpRect((-4).dp, (-3.5).dp, 28.dp, 28.5.dp)),
}
