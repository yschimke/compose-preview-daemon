package ee.schimke.composeai.daemon.rcplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.CarriedDocumentClock
import ee.schimke.composeai.daemon.CarriedDocumentPlayer
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
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
 * `cmp-jvm`: the CMP Remote Compose player (`rc-player-compose`, JVM target) drawing a carried
 * document inside the desktop daemon's scene — the same codebase `cmp-android` runs under
 * Robolectric, on its desktop host.
 *
 * Everything in this package calls into the player library, and nothing outside it does: the daemon
 * reaches this class by name (see `CarriedRemoteCompose`), after checking this package's bytecode
 * against the classpath. It mirrors the connector's `CmpAndroidPlayerBackend`, with a frame-clock
 * time source in place of the Robolectric one.
 *
 * Input needs nothing here: the player's click areas and touch expressions are ordinary pointer
 * input modifiers, so the events the held session sends into its `ImageComposeScene` reach them.
 */
internal class CmpJvmPlayerBinding : CarriedDocumentPlayer {
  override val id: String = "cmp-jvm"

  @Composable
  override fun Play(
    bytes: ByteArray,
    namedValues: Map<String, RemoteNamedValue>,
    clock: CarriedDocumentClock,
    modifier: Modifier,
  ) {
    val values = remember(bytes) { mutableStateMapOf<String, RcNamedValue>() }
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
    val timeSource = remember(clock) { FrameClockRcTimeSource(clock) }
    CompositionLocalProvider(LocalRcTimeSource provides timeSource) {
      RcComposePlayer(bytes = bytes, modifier = modifier, namedValues = values)
    }
  }
}

/** [RcTimeSource] over a [CarriedDocumentClock]: epoch millis are frame time, calendar is UTC. */
internal class FrameClockRcTimeSource(private val clock: CarriedDocumentClock) : RcTimeSource {
  override fun currentTimeMillis(): Long = clock.elapsedMillis()

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
