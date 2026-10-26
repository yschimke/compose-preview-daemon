package ee.schimke.composeai.renderer

/**
 * Keeps the `VSYNC` that [ShadowPausedClockHardwareRenderer] hands native hwui strictly increasing
 * across Robolectric's between-test clock resets (compose-ai-tools#6229,
 * compose-preview-daemon#410).
 *
 * The state lives for the sandbox, like the hwui it mirrors: Robolectric reuses one sandbox (and so
 * this object) for every `@Test` of a batch render, while it resets `ShadowPausedSystemClock` to
 * 100 ms before each one. A raw `VSYNC` lower than the previous frame's is that reset — the paused
 * clock never moves backwards within a test — and the base offset then grows so the frame lands one
 * [FRAME_INTERVAL_NANOS] after the last one drawn. It never shrinks, so every later frame of the
 * test keeps its simulated-clock spacing.
 */
internal object MonotonicFrameVsync {

  /** `android.graphics.FrameInfo.VSYNC`. */
  const val VSYNC: Int = 3

  /** One 60 Hz frame — the gap a reset frame is placed after the last frame drawn. */
  const val FRAME_INTERVAL_NANOS: Long = 16_666_667L

  /**
   * The `FrameInfo` slots Robolectric's own `ShadowNativeHardwareRenderer.adjustFrameInfoTimes`
   * translates (4.17): `INTENDED_VSYNC`, `VSYNC`, then `HANDLE_INPUT_START` through
   * `FRAME_START_TIME`. Slot 4 is `INPUT_EVENT_ID`, an id rather than a time, and is left alone.
   */
  private val TIME_SLOTS = intArrayOf(2, 3, 5, 6, 7, 8, 9, 10)

  private var baseNanos = 0L
  private var lastRawVsync = Long.MIN_VALUE
  private var lastEmittedVsync = Long.MIN_VALUE

  /**
   * The offset to add to [frameInfo]'s time slots before drawing it, updating the tracked state.
   * `0` for a frame without a usable `VSYNC`, and for every frame until the first clock reset.
   */
  @Synchronized
  fun offsetFor(frameInfo: LongArray): Long {
    if (frameInfo.size <= VSYNC) return baseNanos
    val raw = frameInfo[VSYNC]
    if (!isTime(raw)) return baseNanos
    if (raw < lastRawVsync) {
      baseNanos = maxOf(baseNanos, lastEmittedVsync + FRAME_INTERVAL_NANOS - raw)
    }
    lastRawVsync = raw
    lastEmittedVsync = raw + baseNanos
    return baseNanos
  }

  /** Adds [offset] to every populated time slot of [frameInfo], as Robolectric's shadow does. */
  fun adjust(frameInfo: LongArray, offset: Long) {
    for (slot in TIME_SLOTS) {
      if (slot < frameInfo.size && isTime(frameInfo[slot])) frameInfo[slot] += offset
    }
  }

  /** Forgets every frame seen. For tests. */
  @Synchronized
  internal fun resetForTest() {
    baseNanos = 0L
    lastRawVsync = Long.MIN_VALUE
    lastEmittedVsync = Long.MIN_VALUE
  }

  private fun isTime(value: Long): Boolean = value > 0 && value < Long.MAX_VALUE
}
