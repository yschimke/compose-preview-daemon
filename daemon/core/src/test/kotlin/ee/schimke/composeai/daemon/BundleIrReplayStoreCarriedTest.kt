package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BundleIrReplayStoreCarriedTest {

  private fun override(documentBase64: String?) =
    RemoteComposeOverride.Builder().also { it.documentBase64 = documentBase64 }.build()

  @Test
  fun aCarriedDocumentDecodesToARemoteComposeEntry() {
    val entry = BundleIrReplayStore.carried(override("AQIDBA=="))!!
    assertEquals(BundleIrReplayStore.FORMAT_REMOTECOMPOSE, entry.format)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), entry.bytes)
    assertNull(entry.resourcesBytes)
  }

  @Test
  fun noOverrideOrNoDocumentCarriesNothing() {
    assertNull(BundleIrReplayStore.carried(null))
    assertNull(BundleIrReplayStore.carried(override(null)))
  }

  @Test
  fun malformedBase64FailsRatherThanFallingBackToThePreviewsOwnDocument() {
    assertThrows(IllegalArgumentException::class.java) {
      BundleIrReplayStore.carried(override("not*b64"))
    }
  }
}
