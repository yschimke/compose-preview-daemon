package ee.schimke.composeai.overrides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the JVM binding of the common [PreviewOverrideOption] to the *existing* core wire shape.
 *
 * The multiplatform split gives `commonMain` a name for the choice option; what it must not do is
 * give the JVM a second type. `actual typealias` is what keeps that true — every JVM consumer
 * already importing `ee.schimke.composeai.data.overrides.PreviewOverrideOption` (the connector, the
 * `compose/overrides` producer, a preview calling `previewOverrideChoice`) keeps compiling against
 * and linking to the same class. A stray `actual class` here would compile fine and silently break
 * all of them, so assert the identity rather than trust it.
 */
class PreviewOverrideOptionJvmTest {

  @Test
  fun `the common option is the core option`() {
    assertSame(
      ee.schimke.composeai.data.overrides.PreviewOverrideOption::class.java,
      PreviewOverrideOption::class.java,
    )
  }

  @Test
  fun `a core option is readable through the common name`() {
    val core = ee.schimke.composeai.data.overrides.PreviewOverrideOption("xs", "Extra small")
    val common: PreviewOverrideOption = core
    assertEquals("xs", common.value)
    assertEquals("Extra small", common.label)
  }

  @Test
  fun `the factory defaults label to value`() {
    val option = previewOverrideOption("round")
    assertEquals("round", option.value)
    assertEquals("round", option.label)
  }

  @Test
  fun `the JVM default host is the controller-backed one`() {
    assertSame(ControllerPreviewOverrideHost, DefaultPreviewOverrideHost)
  }
}
