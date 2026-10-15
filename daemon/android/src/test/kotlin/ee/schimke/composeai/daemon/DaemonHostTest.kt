package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * B1.3 DoD: submit 10 dummy renders to a single host instance; assert all complete and the sandbox
 * classloader is reused across all of them.
 *
 * Reuse is the load-bearing property — without it the daemon's value proposition collapses (every
 * render would re-bootstrap the sandbox, which is the multi-second cost we are trying to amortise;
 * see DESIGN.md § 13). If this test ever flips and starts seeing distinct classloader hash codes
 * per render, treat it as a sandbox-reuse regression.
 */
class RobolectricHostTest {

  @Test
  fun advertisesDefaultAndroidSdk() {
    assertEquals(35, RobolectricHost().androidSdk)
  }

  @Test
  fun sandboxSdkFollowsTheRequestOnJdk21AndHoldsAt35Below() {
    assertEquals(37, RobolectricHost.resolvedSandboxSdk(requested = 37, javaFeature = 21))
    assertEquals(35, RobolectricHost.resolvedSandboxSdk(requested = 37, javaFeature = 17))
    assertEquals(35, RobolectricHost.resolvedSandboxSdk(requested = 33, javaFeature = 21))
    assertEquals(37, RobolectricHost.resolvedSandboxSdk(requested = 99, javaFeature = 21))
  }

  @Test
  fun eachSandboxSdkHasARunnerPinnedToIt() {
    for (sdk in RobolectricHost.ANDROID_SDK..RobolectricHost.MAX_SANDBOX_SDK) {
      val config = RobolectricHost.sandboxRunnerClass(sdk).getAnnotation(Config::class.java)
      assertEquals(listOf(sdk), config.sdk.toList())
    }
  }

  @Test
  fun tenRendersShareOneSandboxClassloader() {
    val host = RobolectricHost()
    host.start()
    try {
      val results =
        (1..10).map { i ->
          host.submit(RenderRequest.Render(target = RenderTarget.Stub("render-$i")))
        }

      // Sanity: 10 distinct results came back.
      assertEquals(10, results.size)
      assertEquals(10, results.map { it.id }.toSet().size)

      // The load-bearing assertion: every render observed the same
      // contextClassLoader identity. Use System.identityHashCode (captured
      // into the result) so we are comparing object identity rather than
      // any classloader's overridden hashCode().
      val classLoaderHashes = results.map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "expected exactly one sandbox classloader across 10 renders, saw $classLoaderHashes " +
          "(per-render names: ${results.map { it.classLoaderName }.toSet()})",
        1,
        classLoaderHashes.size,
      )

      // Also assert the sandbox classloader is *not* the host classloader —
      // proves we are running inside Robolectric's instrumenting loader,
      // not just on the test JVM's app classloader.
      val hostClHash = System.identityHashCode(RobolectricHost::class.java.classLoader)
      val sandboxClHash = classLoaderHashes.single()
      assertNotEquals(
        "sandbox classloader equals host classloader — Robolectric did not bootstrap a sandbox",
        hostClHash,
        sandboxClHash,
      )

      // The classloader name should mention Robolectric's instrumenting loader.
      val name = results.first().classLoaderName
      assertNotNull(name)
      assertTrue(
        "expected an instrumenting/sandbox classloader, got $name",
        name.contains("Instrument") || name.contains("Sandbox") || name.contains("Robolectric"),
      )
    } finally {
      host.shutdown()
    }
  }
}
