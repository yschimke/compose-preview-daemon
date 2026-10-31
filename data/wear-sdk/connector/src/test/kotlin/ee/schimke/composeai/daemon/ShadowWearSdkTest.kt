package ee.schimke.composeai.daemon

import android.os.OutcomeReceiver
import android.view.HapticFeedbackConstants
import com.google.wear.services.tiles.TileInstance
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Implements

class ShadowWearSdkTest {
  @Test
  fun `shadows target optional Wear SDK APIs by class name`() {
    val haptics =
      requireNotNull(
        ShadowWearHapticFeedbackConstants::class.java.getAnnotation(Implements::class.java)
      )
    val sdk = requireNotNull(ShadowWearSdk::class.java.getAnnotation(Implements::class.java))
    val tiles = requireNotNull(ShadowTilesManager::class.java.getAnnotation(Implements::class.java))

    assertEquals(
      "com.google.wear.input.WearHapticFeedbackConstants",
      haptics.className,
    )
    assertEquals("com.google.wear.Sdk", sdk.className)
    assertEquals(
      "com.google.wear.services.tiles.TilesManager",
      tiles.className,
    )
  }

  @Test
  fun `haptic calls map to stable framework identifiers`() {
    assertEquals(
      HapticFeedbackConstants.KEYBOARD_TAP,
      ShadowWearHapticFeedbackConstants.getScrollItemFocus(),
    )
    assertEquals(HapticFeedbackConstants.REJECT, ShadowWearHapticFeedbackConstants.getScrollLimit())
    assertEquals(
      HapticFeedbackConstants.CLOCK_TICK,
      ShadowWearHapticFeedbackConstants.getScrollTick(),
    )
  }

  @Test
  fun `tiles callback is asynchronous and starts empty`() {
    var scheduled = false
    var received: List<TileInstance>? = null
    val executor = Executor { runnable ->
      scheduled = true
      runnable.run()
    }

    ShadowTilesManager()
      .getActiveTiles(
        executor,
        object : OutcomeReceiver<List<TileInstance>, Exception> {
          override fun onResult(result: List<TileInstance>) {
            received = result
          }

          override fun onError(error: Exception) = throw AssertionError(error)
        },
      )

    assertTrue(scheduled)
    assertNotNull(received)
    assertTrue(received!!.isEmpty())
  }
}
