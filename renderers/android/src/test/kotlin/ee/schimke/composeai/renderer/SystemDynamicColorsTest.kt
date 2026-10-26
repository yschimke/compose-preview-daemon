package ee.schimke.composeai.renderer

import android.provider.Settings
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme as wearDynamicColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SystemDynamicColors] against the real readers: Material 3's `dynamic*ColorScheme(context)` and
 * Wear Material 3's `dynamicColorScheme(context)` on Robolectric SDK 35. Wear Material 3 is on this
 * module's test classpath, so the reflective Wear `MaterialTheme` wrap is exercised too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemDynamicColorsTest {

  @get:Rule val rule = createComposeRule()

  // A deliberately odd table: every value a colour no AOSP default resource has.
  private val colors: Map<String, Int> = buildMap {
    for (palette in listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")) {
      for ((i, shade) in
        listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000).withIndex()) {
        put("system_${palette}_$shade", 0xFF000000.toInt() or (0x10 * i shl 8) or 0x37)
      }
    }
    for (role in WEAR_AND_M3_ROLES) {
      put("system_${role}_light", 0xFF123456.toInt())
      put("system_${role}_dark", 0xFF654321.toInt())
    }
    for (fixed in FIXED_ROLES) put("system_$fixed", 0xFF0A0B0C.toInt())
  }

  @Test
  fun resolves_entry_names_to_platform_ids() {
    val ids =
      SystemDynamicColors.resolveIds(mapOf("system_accent1_500" to 1, "nope_not_a_color" to 2))
    assertEquals(mapOf(android.R.color.system_accent1_500 to 1), ids)
  }

  @Test
  fun material3_dynamic_schemes_follow_the_table_and_nothing_leaks_after() {
    var provided by mutableStateOf(true)
    var light: Int? = null
    var dark: Int? = null
    rule.setContent {
      if (provided) {
        SystemDynamicColors.Provide(colors, javaClass.classLoader) {
          val context = LocalContext.current
          light = dynamicLightColorScheme(context).primary.toArgb()
          dark = dynamicDarkColorScheme(context).primary.toArgb()
        }
      } else {
        val context = LocalContext.current
        light = dynamicLightColorScheme(context).primary.toArgb()
        dark = dynamicDarkColorScheme(context).primary.toArgb()
      }
    }
    rule.waitForIdle()
    assertEquals(0xFF123456.toInt(), light)
    assertEquals(0xFF654321.toInt(), dark)

    provided = false
    rule.waitForIdle()
    val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    assertEquals(dynamicLightColorScheme(app).primary.toArgb(), light)
    assertNotEquals(0xFF123456.toInt(), light)
    assertNotEquals(0xFF654321.toInt(), dark)
  }

  @Test
  fun wear_material3_is_themed_from_the_table_and_the_setting_is_restored() {
    val app = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settingBefore =
      Settings.Global.getString(app.contentResolver, SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING)
    var provided by mutableStateOf(true)
    var themed: androidx.wear.compose.material3.ColorScheme? = null
    var direct: androidx.wear.compose.material3.ColorScheme? = null
    rule.setContent {
      if (provided) {
        SystemDynamicColors.Provide(colors, javaClass.classLoader) {
          themed = WearMaterialTheme.colorScheme
          direct = wearDynamicColorScheme(LocalContext.current)
        }
      } else {
        themed = WearMaterialTheme.colorScheme
        direct = wearDynamicColorScheme(LocalContext.current)
      }
    }
    rule.waitForIdle()
    assertNotNull("Wear dynamic colour must be on under the override", direct)
    val scheme = direct!!
    assertEquals(scheme.primary, themed!!.primary)
    assertNotEquals(androidx.wear.compose.material3.ColorScheme().primary, themed!!.primary)
    assertEquals(scheme.surfaceContainer, themed!!.surfaceContainer)

    provided = false
    rule.waitForIdle()
    // The setting is restored when the override's composition is forgotten, which is after the
    // recomposition that drops it, so read the post-render state rather than that pass's capture.
    // A daemon render is its own composition, so the next render starts from this state.
    assertNull(
      "Wear dynamic colour must be off again once the override leaves",
      wearDynamicColorScheme(app),
    )
    assertEquals(androidx.wear.compose.material3.ColorScheme().primary, themed!!.primary)
    assertEquals(
      settingBefore,
      Settings.Global.getString(
        app.contentResolver,
        SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING,
      ),
    )
  }

  private companion object {
    val WEAR_AND_M3_ROLES =
      listOf(
        "primary",
        "on_primary",
        "primary_container",
        "on_primary_container",
        "secondary",
        "on_secondary",
        "secondary_container",
        "on_secondary_container",
        "tertiary",
        "on_tertiary",
        "tertiary_container",
        "on_tertiary_container",
        "error",
        "on_error",
        "error_container",
        "on_error_container",
        "background",
        "on_background",
        "surface",
        "on_surface",
        "surface_variant",
        "on_surface_variant",
        "surface_dim",
        "surface_bright",
        "surface_container_lowest",
        "surface_container_low",
        "surface_container",
        "surface_container_high",
        "surface_container_highest",
        "outline",
        "outline_variant",
      )
    val FIXED_ROLES =
      listOf(
        "primary_fixed",
        "primary_fixed_dim",
        "on_primary_fixed",
        "on_primary_fixed_variant",
        "secondary_fixed",
        "secondary_fixed_dim",
        "on_secondary_fixed",
        "on_secondary_fixed_variant",
        "tertiary_fixed",
        "tertiary_fixed_dim",
        "on_tertiary_fixed",
        "on_tertiary_fixed_variant",
      )
  }
}
