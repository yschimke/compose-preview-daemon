package ee.schimke.composeai.motion

/**
 * How long one APNG frame is shown: [numerator]/[denominator] seconds, exactly as the `fcTL`
 * chunk's `delay_num` / `delay_den` fields carry it. Both are unsigned 16-bit on the wire, so each
 * must fit in `0..65535`; [denominator] must be non-zero (the spec reads `0` as `100`, which no
 * caller here means, so it is rejected rather than silently reinterpreted).
 *
 * Unlike GIF's 1/100 s quantum, the rational is exact: a 16 ms frame is `2/125`, a 500 ms hold is
 * `1/2`, and 60 fps is `1/60` — see [ofMillis] and [apngDelayFor].
 */
data class ApngFrameDelay(val numerator: Int, val denominator: Int) {
  init {
    require(numerator in 0..MAX_FIELD) {
      "ApngFrameDelay: numerator $numerator outside 0..$MAX_FIELD"
    }
    require(denominator in 1..MAX_FIELD) {
      "ApngFrameDelay: denominator $denominator outside 1..$MAX_FIELD"
    }
  }

  /** The delay in (possibly fractional) milliseconds. */
  val millis: Double
    get() = numerator * 1000.0 / denominator

  companion object {
    private const val MAX_FIELD = 0xFFFF

    /**
     * [ms] milliseconds as `ms/1000` reduced to lowest terms (`500` → `1/2`, `16` → `2/125`, `1000`
     * → `1/1`). Exact for every `ms` in `0..65535`.
     */
    fun ofMillis(ms: Int): ApngFrameDelay {
      require(ms in 0..MAX_FIELD) { "ApngFrameDelay: $ms ms outside 0..$MAX_FIELD" }
      if (ms == 0) return ApngFrameDelay(0, 1)
      val g = gcd(ms, 1000)
      return ApngFrameDelay(ms / g, 1000 / g)
    }

    /**
     * A capture's frame interval as a delay, snapping the canonical frame rates (16/17 ms → `1/60`,
     * 33/34 ms → `1/30`, …) exactly as [apngDelayFor] does, so a uniform 60 fps capture keeps
     * playing at 60 fps rather than at 62.5.
     */
    fun ofFrameInterval(frameIntervalMs: Int): ApngFrameDelay {
      val (num, den) = apngDelayFor(frameIntervalMs)
      return ApngFrameDelay(num.toInt() and MAX_FIELD, den.toInt() and MAX_FIELD)
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
  }
}
