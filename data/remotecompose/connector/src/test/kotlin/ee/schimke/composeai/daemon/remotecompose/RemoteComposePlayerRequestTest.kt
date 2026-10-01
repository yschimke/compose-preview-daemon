package ee.schimke.composeai.daemon.remotecompose

import ee.schimke.composeai.daemon.RemoteComposeController
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A render's player request, from the wire's `RemoteComposeOverride` to the backend that draws:
 * `playerId` before `player` before the build-wide default, and an unknown id refused by name.
 */
class RemoteComposePlayerRequestTest {
  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `an id reaches a registered player, by canonical name or alias`() {
    assertEquals("fixture-player", RemoteComposePlayers.forId("fixture-player")?.id)
    assertEquals("fixture-player", RemoteComposePlayers.forId(" Fixture ")?.id)
  }

  @Test
  fun `an id outranks the enum, which outranks the default`() {
    assertEquals(
      "fixture-player",
      RemoteComposePlayers.resolve(
          playerId = "fixture",
          kind = RemoteComposePlayerKind.VIEW,
          default = RemoteComposePlayerKind.EMBEDDED,
        )
        .id,
    )
    assertEquals(
      "androidx-view",
      RemoteComposePlayers.resolve(
          null,
          RemoteComposePlayerKind.VIEW,
          RemoteComposePlayerKind.EMBEDDED,
        )
        .id,
    )
    assertEquals(
      "androidx-embedded",
      RemoteComposePlayers.resolve("  ", null, RemoteComposePlayerKind.EMBEDDED).id,
    )
    // A built-in's historical spelling is an id like any other.
    assertEquals(
      "androidx-view",
      RemoteComposePlayers.resolve("java", null, RemoteComposePlayerKind.EMBEDDED).id,
    )
  }

  @Test
  fun `an id nothing answers to is refused, naming what would have answered`() {
    val error =
      assertThrows(RemoteComposeUnknownPlayerException::class.java) {
        RemoteComposePlayers.resolve("rcplayer-wasm", null, RemoteComposePlayerKind.EMBEDDED)
      }
    assertEquals("rcplayer-wasm", error.playerId)
    assertTrue(
      error.knownNames.toString(),
      error.knownNames.containsAll(listOf("androidx-embedded", "androidx-view", "fixture-player")),
    )
  }

  @Test
  fun `the controller carries the wire's playerId and clears it with the seed`() {
    val override =
      RemoteComposeOverride.Builder()
        .also {
          it.player = RemoteComposePlayerKind.VIEW
          it.playerId = "fixture"
        }
        .build()
    RemoteComposeController.set(override)
    assertEquals("fixture", RemoteComposeController.playerId.value)
    assertEquals(RemoteComposePlayerKind.VIEW, RemoteComposeController.player.value)

    RemoteComposeController.clearSeed(override)
    assertNull(RemoteComposeController.playerId.value)
  }

  @Test
  fun `a blank id on the wire is no id`() {
    RemoteComposeController.set(RemoteComposeOverride.Builder().also { it.playerId = " " }.build())
    assertNull(RemoteComposeController.playerId.value)
  }

  @Test
  fun `toggling the built-in player drops an id that would otherwise outrank it`() {
    RemoteComposeController.setPlayerId("fixture")
    RemoteComposeController.setPlayer(RemoteComposePlayerKind.VIEW)
    assertNull(RemoteComposeController.playerId.value)
    assertEquals(RemoteComposePlayerKind.VIEW, RemoteComposeController.player.value)
  }
}
