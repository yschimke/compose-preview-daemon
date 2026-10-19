package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runners.model.FrameworkMethod

/**
 * Locks the daemon sandbox's registration of [ShadowRemoteContentPreview] through
 * [SandboxHoldingRunner.getExtraShadows] to the classpath probe that also gates its instrumentation
 * (compose-ai-tools#6116).
 *
 * The two must agree: a shadow registered without its target instrumented is inert, and one
 * registered on a consumer without `remote-tooling-preview` would hand Robolectric a target it
 * cannot resolve. The daemon's test classpath ships no `remote-tooling-preview`, so here this pins
 * the plain-Android direction (no shadow); the positive direction — the shadow intercepting a
 * custom wrapper — is `RemoteContentPreviewShadowTest` in `:data-remotecompose-connector`.
 *
 * Same exposer pattern as [SandboxHoldingRunnerFontShadowTest].
 */
class SandboxHoldingRunnerRemoteContentPreviewShadowTest {

  @Test
  fun registersRemoteContentPreviewShadowExactlyWhenTheTargetIsLoadable() {
    val runner = ExposedRunner(DummyTest::class.java)
    val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
    val shadows = runner.exposedExtraShadows(method).map { it.name }
    assertEquals(
      "ShadowRemoteContentPreview must be registered iff RemoteContentPreviewKt is loadable; " +
        "got $shadows",
      isRemoteContentPreviewAvailable(SandboxHoldingRunner::class.java.classLoader),
      shadows.contains(ShadowRemoteContentPreview::class.java.name),
    )
  }

  /** Exposes the `protected` [org.robolectric.RobolectricTestRunner.getExtraShadows]. */
  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)
  }

  // See SandboxHoldingRunnerFontShadowTest.DummyTest: read back only, never bootstrapped.
  class DummyTest {
    @Test fun stub() = Unit
  }
}
