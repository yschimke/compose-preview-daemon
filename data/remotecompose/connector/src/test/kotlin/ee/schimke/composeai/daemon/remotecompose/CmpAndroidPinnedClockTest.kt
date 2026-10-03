package ee.schimke.composeai.daemon.remotecompose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `cmp-android` honours [RemoteComposeClock.ROBOLECTRIC_UPTIME], as the AndroidX backends do.
 *
 * The fixture is remote-m3's indeterminate circular progress indicator, whose sweep is a float
 * expression over the player-supplied `CONTINUOUS_SEC`. With the pinned clock both players see the
 * same instant, so they draw the same phase; with the host's wall clock the CMP player drew
 * whatever phase the render happened to reach, which is the ~10% difference the remote-m3 parity
 * sweep reported against the baked captures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w192dp-h192dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CmpAndroidPinnedClockTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `cmp-android draws the pinned clock's phase, as androidx-embedded does`() {
    val document = RemoteComposeDocumentSource(fixture(), RemoteComposeClock.ROBOLECTRIC_UPTIME)
    var playerId by mutableStateOf("androidx-embedded")
    rule.mainClock.autoAdvance = false
    rule.setContent {
      key(playerId) {
        val backend = checkNotNull(RemoteComposePlayers.forId(playerId))
        Box(Modifier.fillMaxSize()) { backend.Play(document, emptyMap(), Modifier.fillMaxSize()) }
      }
    }
    rule.mainClock.advanceTimeBy(32)
    val embedded = rule.onRoot().captureToImage().asAndroidBitmap()
    playerId = "cmp-android"
    rule.mainClock.advanceTimeBy(32)
    val cmp = rule.onRoot().captureToImage().asAndroidBitmap()
    val differing = differingFraction(embedded, cmp)
    assertTrue(
      "cmp-android differs from androidx-embedded in $differing of pixels",
      differing < 0.03,
    )
  }

  private fun differingFraction(a: Bitmap, b: Bitmap): Double {
    var differing = 0
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val p = a.getPixel(x, y)
        val q = b.getPixel(x, y)
        val delta =
          maxOf(
            kotlin.math.abs((p shr 16 and 0xff) - (q shr 16 and 0xff)),
            kotlin.math.abs((p shr 8 and 0xff) - (q shr 8 and 0xff)),
            kotlin.math.abs((p and 0xff) - (q and 0xff)),
          )
        if (delta > 24) differing++
      }
    }
    return differing.toDouble() / (a.width * a.height)
  }

  private fun fixture(): ByteArray =
    checkNotNull(
        javaClass.getResourceAsStream("/rc-fixtures/circularprogress-indeterminate-192dp.rc")
      ) {
        "missing fixture"
      }
      .use { it.readBytes() }
}
