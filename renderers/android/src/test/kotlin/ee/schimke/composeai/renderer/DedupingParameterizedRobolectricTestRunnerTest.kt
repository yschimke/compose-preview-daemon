package ee.schimke.composeai.renderer

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.renderer.lvtfixture.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The batch renderer's runner defines a class whose instrumented constructor carries duplicate
 * local-variable rows (compose-ai-tools#6230).
 *
 * Under the stock [ParameterizedRobolectricTestRunner] this class does not get as far as a test:
 * recomputing the parameters inside the sandbox loads the instrumented [Badge] and fails with
 * `ClassFormatError: Duplicated LocalVariableTable attribute entry for '$i$f$getDp'`, as every
 * preview of the reporter's module did. The parameters are [Badge] values on purpose — that is the
 * reported shape, a `PreviewParameterProvider` over such an enum.
 */
@RunWith(DedupingParameterizedRobolectricTestRunner::class)
@Config(sdk = [33], instrumentedPackages = ["ee.schimke.composeai.renderer.lvtfixture"])
class DedupingParameterizedRobolectricTestRunnerTest(private val badge: Badge) {

  @Test
  fun `the instrumented enum loads and keeps its defaults`() {
    assertTrue(
      "Badge must be instrumented, or this test proves nothing",
      Badge::class.java.declaredFields.any { it.name == "__robo_data__" },
    )
    // A takes the default the constructor inlines `.dp` for; B and C pass their own.
    val expected =
      when (badge) {
        Badge.A -> DpRect(0.dp, 0.dp, 24.dp, 24.dp)
        Badge.B -> DpRect((-1.4).dp, (-0.6).dp, 46.dp, 24.4.dp)
        Badge.C -> DpRect((-4).dp, (-3.5).dp, 28.dp, 28.5.dp)
      }
    assertEquals(expected, badge.art)
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun badges(): List<Array<Any>> = Badge.entries.map { arrayOf(it) }
  }
}
