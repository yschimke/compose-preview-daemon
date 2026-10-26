package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `overrides.wallpaper` reaching `dynamic*ColorScheme(LocalContext.current)` on the Android daemon
 * (issue #400): the planner registered in `RobolectricHost` serves the seed's system colours, a
 * render without the override plans nothing, and dropping the override leaves the platform defaults
 * in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperSystemColorsOverrideExtensionTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val override =
    WallpaperOverride(seedColor = "#00D775", paletteStyle = WallpaperPaletteStyle.VIBRANT)

  @Test
  fun plans_nothing_without_a_wallpaper_override() {
    val planner = WallpaperSystemColorsPreviewOverrideExtension()
    assertNull(planner.plan(PreviewOverrides()))
    assertNull(planner.plan(PreviewOverrides(wallpaper = WallpaperOverride(seedColor = "nope"))))
  }

  @Test
  fun dynamic_color_schemes_follow_the_seed_and_do_not_leak() {
    val hook =
      WallpaperSystemColorsPreviewOverrideExtension().plan(PreviewOverrides(wallpaper = override))
        as WallpaperSystemColorsOverrideExtension
    val seed = Color(0xFF00D775)
    val expectedLight =
      WallpaperColorScheme.from(seed, isDark = false, style = WallpaperPaletteStyle.VIBRANT)
    val expectedDark =
      WallpaperColorScheme.from(seed, isDark = true, style = WallpaperPaletteStyle.VIBRANT)

    var applied by mutableStateOf(true)
    var light = 0
    var dark = 0
    var accent = 0
    rule.setContent {
      val read: @androidx.compose.runtime.Composable () -> Unit = {
        val context = LocalContext.current
        light = dynamicLightColorScheme(context).primary.toArgb()
        dark = dynamicDarkColorScheme(context).primary.toArgb()
        accent = colorResource(android.R.color.system_accent1_500).toArgb()
      }
      if (applied) hook.AroundComposable(read) else read()
    }
    rule.waitForIdle()
    assertEquals(expectedLight.primary.toArgb(), light)
    assertEquals(expectedDark.primary.toArgb(), dark)
    assertEquals(WallpaperSystemColors.from(override)!!.getValue("system_accent1_500"), accent)

    val defaultLight = dynamicLightColorScheme(rule.activity).primary.toArgb()
    assertTrue(
      "the seed must actually differ from the platform default for this test to mean anything",
      defaultLight != expectedLight.primary.toArgb(),
    )

    applied = false
    rule.waitForIdle()
    assertEquals(defaultLight, light)
    assertNotEquals(expectedDark.primary.toArgb(), dark)
  }
}
