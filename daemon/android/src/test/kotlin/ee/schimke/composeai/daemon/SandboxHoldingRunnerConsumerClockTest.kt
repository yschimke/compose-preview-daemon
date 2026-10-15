package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ConsumerClock
import ee.schimke.composeai.renderer.ShadowConsumerClock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * Locks [SandboxHoldingRunner]'s two halves of consumer clock pinning (compose-ai-tools#5945): with
 * `composeai.render.clockPackages` set, the consumer's packages and exactly
 * `org.robolectric.shadows.ShadowSystem` are instrumented and [ShadowConsumerClock] is registered;
 * unset, none of the three is, so an unconfigured daemon's sandbox is unchanged.
 *
 * Same exposer pattern as [SandboxHoldingRunnerFontShadowTest]: nothing here boots a sandbox.
 */
class SandboxHoldingRunnerConsumerClockTest {

  @After
  fun restoreGlobals() {
    System.clearProperty(ConsumerClock.PROPERTY)
  }

  @Test
  fun `unset registers nothing`() {
    val (shadows, config) = resolve()

    assertFalse(shadows.contains(ShadowConsumerClock::class.java.name))
    assertFalse(config.instrumentedPackages.contains(CONSUMER))
    assertFalse(config.instrumentedClasses.contains(ConsumerClock.SHADOW_SYSTEM))
  }

  @Test
  fun `set instruments the consumer and ShadowSystem and registers the shadow`() {
    System.setProperty(ConsumerClock.PROPERTY, "$CONSUMER,java.")
    val (shadows, config) = resolve()

    assertTrue(shadows.contains(ShadowConsumerClock::class.java.name))
    assertTrue(config.instrumentedPackages.contains(CONSUMER))
    // The exact class: a `ShadowSystem` package prefix would also instrument `ShadowSystemClock`.
    assertTrue(config.instrumentedClasses.contains(ConsumerClock.SHADOW_SYSTEM))
    assertFalse(config.instrumentedPackages.any { it.startsWith("org.robolectric.shadows") })
    // Protected roots never reach the instrumentation config.
    assertFalse(config.instrumentedPackages.contains("java."))
  }

  private fun resolve(): Pair<List<String>, InstrumentationConfiguration.Builder> {
    val runner = ExposedRunner(DummyTest::class.java)
    val method = runner.firstChild()
    val shadows = runner.exposedExtraShadows(method).map { it.name }
    val config = InstrumentationConfiguration.Builder(runner.exposedClassLoaderConfig(method))
    return shadows to config
  }

  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun firstChild(): FrameworkMethod = children.first()

    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)

    fun exposedClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
      createClassLoaderConfig(method)
  }

  class DummyTest {
    @org.junit.Test fun stub() = Unit
  }

  private companion object {
    const val CONSUMER = "com.example.consumer."
  }
}
