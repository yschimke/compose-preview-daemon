package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [HeldRemoteCompose] carries a document's whole override across the sandbox, and nothing else. */
class HeldRemoteComposeTest {

  @Test
  fun aCarriedDocumentRoundTripsWithItsPlayerAndSeeds() {
    val override =
      RemoteComposeOverride.Builder()
        .also {
          it.player = RemoteComposePlayerKind.entries.first()
          it.playerId = "androidx-embedded"
          it.documentBase64 = "AQIDBA=="
          it.namedValues = mapOf("count" to RemoteNamedValue.IntValue(3))
          it.acceptedHostActions = listOf("tap")
        }
        .build()

    assertEquals(override, HeldRemoteCompose.decode(HeldRemoteCompose.encode(override)))
  }

  @Test
  fun anOverrideWithoutADocumentDoesNotCross() {
    val override = RemoteComposeOverride.Builder().also { it.playerId = "androidx-view" }.build()

    assertNull(HeldRemoteCompose.encode(override))
    assertNull(HeldRemoteCompose.encode(null))
    assertNull(HeldRemoteCompose.decode(null))
  }
}
