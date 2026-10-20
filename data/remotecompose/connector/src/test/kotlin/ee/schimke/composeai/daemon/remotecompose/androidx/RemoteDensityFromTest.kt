@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensityBehavior
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.remoteDensityFrom
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [remoteDensityFrom] resolves `RemoteDensity.from(RemoteCreationDisplayInfo)` reflectively, so the
 * capture links against `remote-creation-compose` lines that declare it as a companion member and
 * lines that declare it as a top-level extension (compose-preview-daemon#341). This pins that the
 * resolution finds the form the line this module builds with declares.
 */
class RemoteDensityFromTest {
  @Test
  fun `resolves RemoteDensity from display info on this line`() {
    val displayInfo =
      RemoteCreationDisplayInfo(
        400,
        800,
        320,
        1.3f,
        densityBehavior = RemoteDensityBehavior.Legacy,
      )
    assertNotNull(remoteDensityFrom(displayInfo))
  }
}
