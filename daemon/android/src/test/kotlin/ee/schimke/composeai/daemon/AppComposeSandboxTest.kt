package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue compose-ai-tools#6016, the fix: with a newer Compose on the user classpath (the Bazel and
 * bundle lanes) and `composeai.daemon.appCompose` at its `auto` default, the sandbox defines the
 * app's Compose — for the renderer and the preview alike — and a preview still renders.
 */
class AppComposeSandboxTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  @Test
  fun newerAppComposeRunsInTheSandbox() {
    System.clearProperty(ee.schimke.composeai.daemon.config.DaemonProperties.Names.APP_COMPOSE)
    val observed = AppComposeTestSupport.bootRenderAndProbe(temp.newFolder("out"))

    val decision = RobolectricHost.appComposeDecision
    assertTrue(decision.logLine(), decision.preferApp)
    assertEquals("app-newer", decision.reason)
    assertEquals(AppComposeTestSupport.APP_VERSION, decision.appVersion)

    // Composer's bytecode was read from the app's jar, not the daemon's.
    assertTrue(
      "Composer should come from ${observed.appJar.name}; got ${observed.composerSource}",
      observed.composerSource.contains(observed.appJar.name),
    )
    // A class only the app's Compose carries is defined by the sandbox loader — the one loader
    // every Compose class lives in — not by the child loader.
    assertEquals(
      "org.robolectric.internal.AndroidSandbox\$SdkSandboxClassLoader",
      observed.markerLoaderType,
    )
    assertTrue(
      "RedSquare should render on the app's Compose; red=${observed.pngMostlyRed}",
      observed.pngMostlyRed >= 0.95,
    )
  }
}
