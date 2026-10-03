package ee.schimke.composeai.daemon.remotecompose.rcplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeClock
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayerBackend
import ee.schimke.composeai.data.remotecompose.NamedValueSeedTarget
import ee.schimke.composeai.data.remotecompose.reseed
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import java.time.Instant
import java.time.ZoneOffset

/**
 * The CMP player — `rc-player-compose`, the player rc-players publishes as its supported API — on
 * Android. The same codebase the `cmp-jvm` and `cmp-wasm` lanes run on the desktop JVM and in the
 * browser, so the three `cmp-` lanes are one implementation on three hosts.
 *
 * Not the vendored AndroidX embedded player: that is `androidx-embedded`, a different codebase that
 * `cmp-android` used to be a misleading name for.
 */
internal class CmpAndroidPlayerBackend : RemoteComposePlayerBackend {
  override val id: String = ID

  override val linkedPackages: List<String> =
    listOf("ee.schimke.composeai.daemon.remotecompose.rcplayer")

  @Composable
  override fun Play(
    document: RemoteComposeDocumentSource,
    namedValues: Map<String, RemoteNamedValue>,
    modifier: Modifier,
  ) {
    val values = remember(document) { mutableStateMapOf<String, RcNamedValue>() }
    val target = remember(values) { SnapshotStateMapSeedTarget(values) }
    val seeded = remember(target) { mutableSetOf<String>() }
    // Seeded during composition, not in a SideEffect: the player reads these on its first frame.
    remember(target, namedValues) {
      val applied = target.reseed(previous = seeded, overrides = namedValues)
      seeded.apply {
        clear()
        addAll(applied)
      }
    }
    val player: @Composable () -> Unit = {
      RcComposePlayer(bytes = document.bytes, modifier = modifier, namedValues = values)
    }
    when (document.clock) {
      RemoteComposeClock.SYSTEM -> player()
      // The clock the AndroidX backends get (`RobolectricRemoteClock`): wall time starts at the
      // epoch, in UTC, when the document is first composed and advances only with Robolectric's
      // uptime. Without it this player read the host's real wall clock, so anything a document
      // derives from `CONTINUOUS_SEC` and its siblings — remote-m3's indeterminate progress sweep
      // — was captured at an arbitrary phase and never matched the AndroidX lanes.
      RemoteComposeClock.ROBOLECTRIC_UPTIME -> {
        val clock = remember(document) { RobolectricRcTimeSource() }
        CompositionLocalProvider(LocalRcTimeSource provides clock) { player() }
      }
    }
  }

  companion object {
    const val ID: String = "cmp-android"
  }
}

/**
 * [RcTimeSource] counterpart of the AndroidX backends' `RobolectricRemoteClock`: epoch milliseconds
 * are the Robolectric uptime elapsed since construction, and calendar fields are read in UTC.
 */
internal class RobolectricRcTimeSource(
  private val startUptimeMillis: Long = android.os.SystemClock.uptimeMillis(),
  private val uptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
) : RcTimeSource {
  override fun currentTimeMillis(): Long = (uptimeMillis() - startUptimeMillis).coerceAtLeast(0L)

  override fun snapshot(epochMillis: Long): RcTimeSnapshot {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
    return RcTimeSnapshot(
      epochMillis = epochMillis,
      year = time.year,
      month = time.monthValue,
      dayOfMonth = time.dayOfMonth,
      dayOfYear = time.dayOfYear,
      hour = time.hour,
      minute = time.minute,
      second = time.second,
      isoDayOfWeek = time.dayOfWeek.value,
      offsetSeconds = 0,
    )
  }
}

/** The CMP player's named values — one snapshot map — as a [NamedValueSeedTarget]. */
internal class SnapshotStateMapSeedTarget(
  private val values: SnapshotStateMap<String, RcNamedValue>
) : NamedValueSeedTarget {
  override fun setString(name: String, value: String) {
    values[name] = RcNamedValue.Text(value)
  }

  override fun setFloat(name: String, value: Float) {
    values[name] = RcNamedValue.FloatValue(value)
  }

  override fun setInt(name: String, value: Int) {
    values[name] = RcNamedValue.Integer(value)
  }

  override fun setBoolean(name: String, value: Boolean) {
    values[name] = RcNamedValue.BooleanValue(value)
  }

  override fun setColor(name: String, argb: Int) {
    values[name] = RcNamedValue.Color(argb)
  }

  override fun clear(name: String) {
    values.remove(name)
  }
}
