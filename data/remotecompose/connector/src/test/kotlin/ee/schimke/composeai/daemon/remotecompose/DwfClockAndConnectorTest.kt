package ee.schimke.composeai.daemon.remotecompose

import ee.schimke.composeai.daemon.RemoteComposeController
import ee.schimke.composeai.daemon.RemoteComposeOverrideExtension
import ee.schimke.composeai.daemon.RemoteComposePreviewOverrideExtension
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.remotecompose.rcplayer.RobolectricRcTimeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwfClockAndConnectorTest {

  @After
  fun resetController() {
    RemoteComposeController.resetForNewSession()
  }

  @Test
  fun `RobolectricRcTimeSource starts at baseEpochMillis in UTC and advances with uptime`() {
    var fakeUptime = 5_000L
    // 2025-11-18T10:10:30Z (Tuesday)
    val baseEpochMillis = 1_763_460_630_000L
    val clock =
      RobolectricRcTimeSource(
        baseEpochMillis = baseEpochMillis,
        startUptimeMillis = 5_000L,
        uptimeMillis = { fakeUptime },
      )

    assertEquals(baseEpochMillis, clock.currentTimeMillis())
    val initial = clock.snapshot(clock.currentTimeMillis())
    assertEquals(2025, initial.year)
    assertEquals(11, initial.month)
    assertEquals(18, initial.dayOfMonth)
    assertEquals(10, initial.hour)
    assertEquals(10, initial.minute)
    assertEquals(30, initial.second)
    assertEquals(2, initial.isoDayOfWeek)
    assertEquals(0, initial.offsetSeconds)

    fakeUptime += 15_000L
    assertEquals(baseEpochMillis + 15_000L, clock.currentTimeMillis())
    val advanced = clock.snapshot(clock.currentTimeMillis())
    assertEquals(10, advanced.hour)
    assertEquals(10, advanced.minute)
    assertEquals(45, advanced.second)
  }

  @Test
  fun `controller and planner carry clockEpochMillis alongside RemoteComposeOverride`() {
    val override =
      RemoteComposeOverride.Builder()
        .also {
          it.playerId = "cmp-android"
          it.namedValues = mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("8500"))
        }
        .build()
    val epochMs = 1_763_460_630_000L
    val planned =
      RemoteComposePreviewOverrideExtension()
        .plan(PreviewOverrides(remoteCompose = override, clockEpochMillis = epochMs))
    assertTrue(planned is RemoteComposeOverrideExtension)

    RemoteComposeController.set(override, epochMs)
    assertEquals("cmp-android", RemoteComposeController.playerId.value)
    assertEquals(epochMs, RemoteComposeController.clockEpochMillis.value)
    assertEquals(
      mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("8500")),
      RemoteComposeController.namedValues.value,
    )

    RemoteComposeController.setNamedValue(
      "dwf.is_ambient",
      RemoteNamedValue.BooleanValue(true),
    )
    assertEquals(epochMs, RemoteComposeController.clockEpochMillis.value)
    assertEquals(
      RemoteNamedValue.BooleanValue(true),
      RemoteComposeController.namedValues.value["dwf.is_ambient"],
    )

    RemoteComposeController.clearSeed(override, epochMs)
    assertNull(RemoteComposeController.clockEpochMillis.value)
  }
}
