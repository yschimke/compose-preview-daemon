package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod

/** Pins the Wear SDK classpath instrumentation and daemon-only shadow registration. */
class SandboxHoldingRunnerWearSdkShadowTest {
  @Test
  fun registersOptionalWearSdkShadowsWhenSignatureSurfaceIsBundled() {
    val runner = ExposedRunner(DummyTest::class.java)
    val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
    val shadows = runner.exposedExtraShadows(method).map { it.name }

    assertTrue(isWearSdkStubsAvailable(javaClass.classLoader))
    assertTrue(shadows.contains(ShadowWearHapticFeedbackConstants::class.java.name))
    assertTrue(shadows.contains(ShadowWearSdk::class.java.name))
    assertTrue(shadows.contains(ShadowTilesManager::class.java.name))
  }

  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)
  }

  class DummyTest {
    @Test fun stub() = Unit
  }
}
