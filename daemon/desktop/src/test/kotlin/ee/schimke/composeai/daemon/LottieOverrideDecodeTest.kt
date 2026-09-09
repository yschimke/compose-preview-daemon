package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage (no Skiko render) for the Lottie timeline override's wire path: the
 * `overrides.lottie.progress` set by a `renderNow` survives [RenderSpec.encode] /
 * [RenderSpec.decode] — the boundary hop into a sandbox worker — so the desktop `RenderEngine` can
 * provide it as `LocalLottieProgress`. Also pins the "null is a no-op" contract: an absent override
 * leaves `spec.overrides` (and hence the authored progress) untouched.
 *
 * This used to encode the override into a base64 `overrides=` token appended to a `;`-delimited
 * payload string, because that was the only way it reached the renderer. The override now rides the
 * spec as the object it is, and the round-trip under test is plain serialization.
 */
class LottieOverrideDecodeTest {

  private fun specWith(overrides: PreviewOverrides) =
    RenderSpec(
      className = "",
      functionName = "spin.json",
      kind = "LOTTIE",
      assetPath = "lottie/spin.json",
      overrides = overrides,
    )

  private fun roundTrip(spec: RenderSpec) = RenderSpec.decode(RenderSpec.encode(spec))

  @Test
  fun progressOverrideSurvivesRoundTrip() {
    val spec = roundTrip(specWith(PreviewOverrides(lottie = LottieOverride(0.42f))))
    assertEquals(0.42f, spec.overrides?.lottie?.progress)
  }

  @Test
  fun absentLottieOverrideDecodesToNull() {
    // An overrides bag carrying only an unrelated field must leave `lottie` null — the no-op case
    // that keeps the composable's authored progress.
    val spec = roundTrip(specWith(PreviewOverrides(fontScale = 1.3f)))
    assertNull(spec.overrides?.lottie)
  }

  @Test
  fun noOverridesLeavesOverridesNull() {
    val spec =
      roundTrip(
        RenderSpec(
          className = "",
          functionName = "spin.json",
          kind = "LOTTIE",
          assetPath = "lottie/spin.json",
        )
      )
    assertNull(spec.overrides?.lottie)
  }
}
