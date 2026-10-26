package ee.schimke.composeai.renderer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The offset arithmetic behind [ShadowPausedClockHardwareRenderer]'s cross-test `VSYNC` guard
 * (compose-ai-tools#6229). Plain JVM; [SuccessiveTestFramesSdk37Test] is the pixel check.
 */
class MonotonicFrameVsyncTest {

  @Before fun setUp() = MonotonicFrameVsync.resetForTest()

  @After fun tearDown() = MonotonicFrameVsync.resetForTest()

  @Test
  fun `a clock that only advances is never shifted`() {
    // The daemon's held sandbox: one test, a clock that only moves forward.
    for (ms in listOf(100L, 100L, 116L, 133L, 400L, 10_000L)) {
      assertEquals(0L, MonotonicFrameVsync.offsetFor(frame(ms)))
    }
  }

  @Test
  fun `a clock reset puts the next frame one interval after the last one drawn`() {
    listOf(100L, 115L, 130L).forEach { MonotonicFrameVsync.offsetFor(frame(it)) }

    // Robolectric resets the paused clock to 100 ms for the next @Test.
    val offset = MonotonicFrameVsync.offsetFor(frame(100L))
    assertEquals(ms(130L) + MonotonicFrameVsync.FRAME_INTERVAL_NANOS - ms(100L), offset)

    // Later frames of that test keep their simulated spacing on the same offset.
    assertEquals(offset, MonotonicFrameVsync.offsetFor(frame(116L)))
    assertEquals(offset, MonotonicFrameVsync.offsetFor(frame(132L)))
  }

  @Test
  fun `emitted vsync is strictly increasing across repeated resets`() {
    var lastEmitted = Long.MIN_VALUE
    repeat(5) {
      for (ms in listOf(100L, 116L, 133L)) {
        val raw = ms(ms)
        val emitted = raw + MonotonicFrameVsync.offsetFor(frame(ms))
        assertTrue("$emitted must follow $lastEmitted", emitted > lastEmitted)
        lastEmitted = emitted
      }
    }
  }

  @Test
  fun `adjust shifts time slots only and round-trips`() {
    val info = LongArray(12) { it.toLong() * 1_000 }
    info[0] = 1L // FLAGS
    info[4] = 42L // INPUT_EVENT_ID
    info[9] = 0L // unset slot
    info[10] = Long.MAX_VALUE // sentinel
    val original = info.copyOf()

    MonotonicFrameVsync.adjust(info, 7L)
    assertEquals(original[0], info[0])
    assertEquals(original[1], info[1])
    assertEquals(original[2] + 7, info[2])
    assertEquals(original[3] + 7, info[3])
    assertEquals(42L, info[4])
    assertEquals(original[8] + 7, info[8])
    assertEquals(0L, info[9])
    assertEquals(Long.MAX_VALUE, info[10])
    assertEquals(original[11], info[11])

    MonotonicFrameVsync.adjust(info, -7L)
    assertArrayEquals(original, info)
  }

  private fun ms(value: Long) = value * 1_000_000L

  private fun frame(vsyncMs: Long) =
    LongArray(12).also {
      it[2] = ms(vsyncMs)
      it[MonotonicFrameVsync.VSYNC] = ms(vsyncMs)
    }
}
