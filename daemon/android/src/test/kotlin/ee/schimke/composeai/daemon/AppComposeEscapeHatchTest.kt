package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue compose-ai-tools#6016, the behaviour before the fix, kept reachable as
 * `composeai.daemon.appCompose=daemon`: the same newer app Compose on the user classpath is ignored
 * for every class the daemon also bundles, and a class only the app carries is defined by the
 * *child* loader, linked against the daemon's Compose — the split the default now avoids.
 */
class AppComposeEscapeHatchTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  @Test
  fun escapeHatchRunsTheDaemonsCompose() {
    System.setProperty(DaemonProperties.Names.APP_COMPOSE, "daemon")
    val observed =
      try {
        AppComposeTestSupport.bootRenderAndProbe(temp.newFolder("out"))
      } finally {
        System.clearProperty(DaemonProperties.Names.APP_COMPOSE)
      }

    val decision = RobolectricHost.appComposeDecision
    assertFalse(decision.logLine(), decision.preferApp)
    assertEquals("escape-hatch", decision.reason)

    assertFalse(
      "Composer should come from the daemon's jar; got ${observed.composerSource}",
      observed.composerSource.contains(observed.appJar.name),
    )
    assertEquals(
      "ee.schimke.composeai.daemon.UserClassLoaderHolder\$ChildFirstURLClassLoader",
      observed.markerLoaderType,
    )
    assertTrue(
      "RedSquare should render on the daemon's Compose; red=${observed.pngMostlyRed}",
      observed.pngMostlyRed >= 0.95,
    )
  }
}
