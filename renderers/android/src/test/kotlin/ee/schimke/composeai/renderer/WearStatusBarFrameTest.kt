package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearStatusBarFrameTest {

  @Test
  fun `paints curved clock at 12 o'clock over black watch canvas`() {
    WearStatusBarSupport.installSystemStatusBar()
    try {
      val size = 227
      val bitmap =
        renderToBitmap(size = size) {
          WearStatusBarFrame(clockText = "9:30") {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
          }
        }

      // Centre of watch remains black.
      val center = bitmap.getPixel(size / 2, size / 2)
      assertEquals(0, AndroidColor.red(center))
      assertEquals(0, AndroidColor.green(center))
      assertEquals(0, AndroidColor.blue(center))

      // Top 12 o'clock arc band has bright white curved clock pixels.
      val topWhitePixels = countBrightPixels(bitmap, xRange = 60..167, yRange = 4..34)
      assertTrue(
        "expected curved status bar text pixels near 12 o'clock, got $topWhitePixels",
        topWhitePixels > 20,
      )
    } finally {
      WearStatusBarSupport.clearSystemStatusBar()
    }
  }

  @Test
  fun `hides curved status bar when ambient is true`() {
    WearStatusBarSupport.installSystemStatusBar()
    try {
      val size = 227
      val bitmap =
        renderToBitmap(size = size) {
          WearStatusBarFrame(clockText = "9:30", ambient = true) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
          }
        }

      val topWhitePixels = countBrightPixels(bitmap, xRange = 60..167, yRange = 4..34)
      assertEquals("ambient mode must suppress Wear GSB", 0, topWhitePixels)
    } finally {
      WearStatusBarSupport.clearSystemStatusBar()
    }
  }

  @Test
  fun `hides status bar when WindowInsetsController hides statusBars`() {
    assertTrue(
      "Robolectric SystemUi status bar should install cleanly",
      WearStatusBarSupport.installSystemStatusBar(),
    )
    try {
      val size = 227
      val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
      val activity = controller.get()
      activity.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
          WearStatusBarFrame(clockText = "9:30") {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
          }
        }
      }
      val decor = activity.window.decorView as ViewGroup
      val before = drawDecor(decor, size)
      assertTrue(
        "expected status bar visible before hide()",
        countBrightPixels(before, xRange = 60..167, yRange = 4..34) > 20,
      )

      decor.windowInsetsController?.hide(WindowInsets.Type.statusBars())
      assertFalse(
        "WearStatusBarSupport should report hidden after WindowInsetsController.hide()",
        WearStatusBarSupport.isStatusBarVisible(decor),
      )

      // Pump a pre-draw + recompose pass so WearStatusBarFrame observes the hidden state.
      decor.viewTreeObserver.dispatchOnPreDraw()
      val afterHide = drawDecor(decor, size)
      assertEquals(
        "status bar pixels should disappear after WindowInsetsController.hide()",
        0,
        countBrightPixels(afterHide, xRange = 60..167, yRange = 4..34),
      )

      decor.windowInsetsController?.show(WindowInsets.Type.statusBars())
      assertTrue(
        "WearStatusBarSupport should report visible after WindowInsetsController.show()",
        WearStatusBarSupport.isStatusBarVisible(decor),
      )
      decor.viewTreeObserver.dispatchOnPreDraw()
      val afterShow = drawDecor(decor, size)
      assertTrue(
        "status bar pixels should reappear after WindowInsetsController.show()",
        countBrightPixels(afterShow, xRange = 60..167, yRange = 4..34) > 20,
      )
    } finally {
      WearStatusBarSupport.clearSystemStatusBar()
    }
  }

  @Test
  fun `indicator changes top arc pixels compared to plain clock`() {
    WearStatusBarSupport.installSystemStatusBar()
    try {
      val size = 227
      val plain =
        renderToBitmap(size = size) {
          WearStatusBarFrame(clockText = "9:30", indicator = null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
          }
        }
      val withMic =
        renderToBitmap(size = size) {
          WearStatusBarFrame(clockText = "9:30", indicator = WearStatusBarIndicator.MICROPHONE) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
          }
        }

      var greenPixels = 0
      for (y in 4..40) {
        for (x in 50..177) {
          val p = withMic.getPixel(x, y)
          if (AndroidColor.green(p) > 150 && AndroidColor.green(p) > AndroidColor.red(p) + 30) {
            greenPixels++
          }
        }
      }
      assertTrue("expected green microphone indicator pixels, got $greenPixels", greenPixels > 5)
      assertNotEquals(
        countBrightPixels(plain, 50..177, 4..40),
        countBrightPixels(withMic, 50..177, 4..40),
      )
    } finally {
      WearStatusBarSupport.clearSystemStatusBar()
    }
  }

  @Test
  fun `resolve honours explicit property, device shape, and tile exclusion`() {
    val nonRound =
      WearStatusBarSupport.resolve(
        isRound = false,
        showSystemUi = true,
        isTileOrWidget = false,
        classLoader = null,
        propertyLookup = { if (it == WearStatusBarSupport.PROP_ENABLED) "true" else null },
      )
    assertFalse("phone preview must never activate Wear GSB", nonRound.active)

    val tile =
      WearStatusBarSupport.resolve(
        isRound = true,
        showSystemUi = true,
        isTileOrWidget = true,
        classLoader = null,
        propertyLookup = { if (it == WearStatusBarSupport.PROP_ENABLED) "true" else null },
      )
    assertFalse("tile preview must never activate Wear GSB", tile.active)

    val forcedOn =
      WearStatusBarSupport.resolve(
        isRound = true,
        showSystemUi = false,
        isTileOrWidget = false,
        classLoader = null,
        clockEpochMillis = 9L * 3600_000L + 30L * 60_000L,
        propertyLookup = {
          when (it) {
            WearStatusBarSupport.PROP_ENABLED -> "true"
            WearStatusBarSupport.PROP_INDICATOR -> "dnd"
            else -> null
          }
        },
      )
    assertTrue(forcedOn.active)
    assertEquals("9:30", forcedOn.clockText)
    assertEquals(WearStatusBarIndicator.DND, forcedOn.indicator)

    val forcedOff =
      WearStatusBarSupport.resolve(
        isRound = true,
        showSystemUi = true,
        isTileOrWidget = false,
        classLoader = null,
        propertyLookup = { if (it == WearStatusBarSupport.PROP_ENABLED) "false" else null },
      )
    assertFalse(forcedOff.active)
  }

  private fun renderToBitmap(size: Int, content: @Composable () -> Unit): Bitmap {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    val activity = controller.get()
    activity.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) { content() }
    }
    val decor = activity.window.decorView as ViewGroup
    return drawDecor(decor, size)
  }

  private fun drawDecor(decor: ViewGroup, size: Int): Bitmap {
    val wSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    val hSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    decor.measure(wSpec, hSpec)
    decor.layout(0, 0, size, size)
    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    decor.measure(wSpec, hSpec)
    decor.layout(0, 0, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    decor.draw(Canvas(bitmap))
    return bitmap
  }

  private fun countBrightPixels(bitmap: Bitmap, xRange: IntRange, yRange: IntRange): Int {
    var count = 0
    for (y in yRange) {
      for (x in xRange) {
        val p = bitmap.getPixel(x, y)
        if (
          AndroidColor.red(p) > 150 || AndroidColor.green(p) > 150 || AndroidColor.blue(p) > 150
        ) {
          count++
        }
      }
    }
    return count
  }
}
