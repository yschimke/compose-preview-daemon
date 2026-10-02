package ee.schimke.composeai.daemon.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.AndroidxRemoteCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player seam against the Remote Compose line this module is built with.
 *
 * The linkage cases are the build-time half of the runtime check: a dependency bump that moves an
 * API a backend or the capture calls fails here, naming the reference, instead of reaching a
 * consumer as a refused render. `classes > 0` keeps them honest — a check that found nothing to
 * read would pass vacuously, which is what a package rename would otherwise do silently.
 */
class RemoteComposePlayersTest {
  @Test
  fun `every built-in player links against the line this module builds with`() {
    for (kind in RemoteComposePlayerKind.entries) {
      val report = RemoteComposePlayers.linkage(kind)
      assertTrue("$kind read no classes", report.classes > 0)
      assertTrue("$kind made no library references", report.references > 0)
      assertTrue("$kind: $report", report.isLinked)
    }
  }

  @Test
  fun `the CMP player links against the line this module builds with`() {
    val backend = checkNotNull(RemoteComposePlayers.forId("cmp-android"))
    val report = RemoteComposePlayers.linkage(backend)
    assertTrue("cmp-android read no classes", report.classes > 0)
    assertTrue("cmp-android made no library references", report.references > 0)
    assertTrue("cmp-android: $report", report.isLinked)
  }

  @Test
  fun `the capture links against the line this module builds with`() {
    val report = AndroidxRemoteCapture.linkage
    assertTrue("capture read no classes", report.classes > 0)
    assertTrue("capture: $report", report.isLinked)
  }

  @Test
  fun `each built-in answers to its canonical name and every historical spelling`() {
    val expected =
      mapOf(
        "androidx-embedded" to listOf("androidx-embedded", "embedded"),
        "androidx-view" to listOf("androidx-view", "java", "view", "  VIEW "),
        "cmp-android" to listOf("cmp-android", "CMP-Android"),
      )
    for ((id, names) in expected) {
      for (name in names) assertEquals(name, id, RemoteComposePlayers.forId(name)?.id)
    }
    // The bare `cmp` used to be the embedded player's short name; it is ambiguous now (three hosts
    // run the CMP player), so it names nothing.
    assertNull(RemoteComposePlayers.forId("cmp"))
    assertNull(RemoteComposePlayers.forId("cmp-wasm"))
  }

  @Test
  fun `a capture records the player by its canonical id`() {
    assertEquals(
      "androidx-embedded",
      RemoteComposePlayers.forKind(RemoteComposePlayerKind.EMBEDDED).capturePlayerName,
    )
    assertEquals(
      "androidx-view",
      RemoteComposePlayers.forKind(RemoteComposePlayerKind.VIEW).capturePlayerName,
    )
  }
}
