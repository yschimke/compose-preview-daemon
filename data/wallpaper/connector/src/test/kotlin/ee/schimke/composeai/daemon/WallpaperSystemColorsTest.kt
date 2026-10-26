package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperSystemColorsTest {

  private val seed = Color(0xFF00D775)

  @Test
  fun shade_maps_to_framework_tone() {
    assertEquals(100, WallpaperSystemColors.toneForShade(0))
    assertEquals(99, WallpaperSystemColors.toneForShade(10))
    assertEquals(95, WallpaperSystemColors.toneForShade(50))
    assertEquals(50, WallpaperSystemColors.toneForShade(500))
    assertEquals(0, WallpaperSystemColors.toneForShade(1000))
  }

  @Test
  fun covers_every_tonal_palette_resource() {
    val colors = WallpaperSystemColors.from(seed)
    for (palette in listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")) {
      for (shade in WallpaperSystemColors.PALETTE_SHADES) {
        assertTrue("system_${palette}_$shade", "system_${palette}_$shade" in colors)
      }
    }
    // The ends of every palette are pure white and pure black, as on a device.
    assertEquals(0xFFFFFFFF.toInt(), colors.getValue("system_accent1_0"))
    assertEquals(0xFF000000.toInt(), colors.getValue("system_accent1_1000"))
    assertEquals(0xFFFFFFFF.toInt(), colors.getValue("system_neutral2_0"))
  }

  @Test
  fun role_colours_match_the_direct_material_theme_scheme() {
    val colors = WallpaperSystemColors.from(seed, WallpaperPaletteStyle.VIBRANT)
    val light =
      WallpaperColorScheme.from(seed, isDark = false, style = WallpaperPaletteStyle.VIBRANT)
    val dark = WallpaperColorScheme.from(seed, isDark = true, style = WallpaperPaletteStyle.VIBRANT)
    assertEquals(light.primary.toArgb(), colors.getValue("system_primary_light"))
    assertEquals(light.surfaceContainer.toArgb(), colors.getValue("system_surface_container_light"))
    assertEquals(dark.primary.toArgb(), colors.getValue("system_primary_dark"))
    assertEquals(dark.onSurface.toArgb(), colors.getValue("system_on_surface_dark"))
    assertEquals(dark.outlineVariant.toArgb(), colors.getValue("system_outline_variant_dark"))
    assertTrue("system_primary_fixed" in colors)
    assertTrue("system_on_tertiary_fixed_variant" in colors)
  }

  @Test
  fun different_seeds_produce_different_accents() {
    val green = WallpaperSystemColors.from(seed)
    val red = WallpaperSystemColors.from(Color(0xFFFF2020))
    assertNotEquals(green.getValue("system_accent1_500"), red.getValue("system_accent1_500"))
    assertNotEquals(green.getValue("system_primary_light"), red.getValue("system_primary_light"))
  }

  @Test
  fun override_resolves_seed_style_and_contrast() {
    val override =
      WallpaperOverride(
        seedColor = "#00D775",
        paletteStyle = WallpaperPaletteStyle.EXPRESSIVE,
        contrastLevel = 0.5,
      )
    assertEquals(
      WallpaperSystemColors.from(seed, WallpaperPaletteStyle.EXPRESSIVE, 0.5),
      WallpaperSystemColors.from(override),
    )
  }

  @Test
  fun unparseable_seed_yields_null() {
    assertNull(WallpaperSystemColors.from(WallpaperOverride(seedColor = "not-a-colour")))
  }
}
