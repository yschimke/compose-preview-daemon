package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.annotation.Config

/** Pins the Wear SDK classpath instrumentation and daemon-only shadow registration. */
class SandboxHoldingRunnerWearSdkShadowTest {
  @Test
  fun registersWearSdkShadowsWhenConsumerLoaderCannotSeeDaemonOnlySignatures() {
    val originalLoader = Thread.currentThread().contextClassLoader
    try {
      Thread.currentThread().contextClassLoader =
        object : ClassLoader(originalLoader) {
          override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("com.google.wear.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
          }
        }
      val runner = ExposedRunner(DummyTest::class.java)
      val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
      val shadows = runner.exposedExtraShadows(method).map { it.name }

      assertTrue(shadows.contains(ShadowWearHapticFeedbackConstants::class.java.name))
      assertTrue(shadows.contains(ShadowWearSdk::class.java.name))
      assertTrue(shadows.contains(ShadowTilesManager::class.java.name))
    } finally {
      Thread.currentThread().contextClassLoader = originalLoader
    }
  }

  @Test
  fun `wear haptic stub is instrumented and shadowed in a real sandbox`() {
    val result = JUnitCore.runClasses(WearHapticFixture::class.java)
    assertTrue(result.failures.joinToString("\n") { it.trace }, result.wasSuccessful())
  }

  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)
  }

  class DummyTest {
    @Test fun stub() = Unit
  }

  @RunWith(SandboxHoldingRunner::class)
  @Config(sdk = [35])
  class WearHapticFixture {
    @Test
    fun callsAreHandledByTheSimulatorShadow() {
      val haptic = Class.forName("com.google.wear.input.WearHapticFeedbackConstants")
      assertTrue((haptic.getMethod("getScrollItemFocus").invoke(null) as Int) >= 0)
    }
  }
}
