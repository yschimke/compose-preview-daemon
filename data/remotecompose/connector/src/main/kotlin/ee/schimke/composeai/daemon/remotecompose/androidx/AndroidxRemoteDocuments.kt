@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx

import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.SystemClock
import androidx.compose.remote.player.core.RemoteDocument
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeClock
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import java.time.Clock
import java.time.ZoneId

/**
 * Shared by both AndroidX backends: the `RemoteDocument` a [RemoteComposeDocumentSource] parses to.
 * One of the packages each of them lists in `linkedPackages`.
 *
 * Under [RemoteComposeClock.ROBOLECTRIC_UPTIME] the document's time source is controllable by
 * Robolectric's paused Android looper. Remote Compose's default [SystemClock] reads
 * `java.time.Clock` / `System.nanoTime()`, so advancing Compose's test clock (or Robolectric's
 * shadow looper) cannot move it and an animated preview is captured at whatever real-time phase the
 * render happens to reach.
 */
internal fun RemoteComposeDocumentSource.toAndroidxDocument(): RemoteDocument =
  when (clock) {
    RemoteComposeClock.ROBOLECTRIC_UPTIME ->
      RemoteDocument(bytes.inputStream(), RobolectricRemoteClock())
    RemoteComposeClock.SYSTEM -> RemoteDocument(bytes)
  }

/**
 * Elapsed time starts at zero when the document is created and advances only with
 * `android.os.SystemClock.uptimeMillis()`. The animated renderer advances that shadow clock
 * alongside Compose's `mainClock`, giving both the View-backed and Compose-backed players the same
 * deterministic frame cadence.
 */
internal class RobolectricRemoteClock(
  private val startUptimeMillis: Long = android.os.SystemClock.uptimeMillis(),
  private val uptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
  private val clockZoneId: ZoneId = ZoneId.of("UTC"),
) : RemoteClock {
  private val snapshotClock = SystemClock(Clock.fixed(java.time.Instant.EPOCH, clockZoneId))

  private fun elapsedMillis(): Long = (uptimeMillis() - startUptimeMillis).coerceAtLeast(0L)

  override fun millis(): Long = elapsedMillis()

  override fun nanoTime(): Long = elapsedMillis() * NANOS_PER_MILLISECOND

  override fun getZoneId(): String = clockZoneId.id

  override fun snapshot(epochMillis: Long?): RemoteClock.TimeSnapshot =
    snapshotClock.snapshot(epochMillis ?: millis())

  private companion object {
    const val NANOS_PER_MILLISECOND = 1_000_000L
  }
}
