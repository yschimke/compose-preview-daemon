package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.Color
import com.materialkolor.ktx.toDynamicScheme
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import ee.schimke.composeai.data.render.extensions.compose.ComposeColorSpec

/**
 * Wallpaper seed → Android 12+ **system dynamic colour resources** (`android.R.color.system_*`).
 *
 * On a device, the wallpaper picker computes a Material Color Utilities `DynamicScheme` from the
 * wallpaper seed and overlays the framework's `system_accent1_*` … `system_neutral2_*` tonal
 * palettes (Android 12+) and the `system_primary_light` … role colours (Android 14+) with it. Those
 * resources are what `androidx.compose.material3.dynamicLightColorScheme(context)` /
 * `dynamicDarkColorScheme(context)` and
 * `androidx.wear.compose.material3.dynamicColorScheme(context)` read. Robolectric ships the AOSP
 * defaults, so without this a preview theming itself from them ignores `overrides.wallpaper`
 * entirely.
 *
 * This object is the pure half: the resource **entry name** → ARGB table, computed with the same
 * `material-kolor` algorithm [WallpaperColorScheme] uses, so the dynamic-resource path and the
 * direct `MaterialTheme` wrap agree. The Android daemon resolves the names to ids in the sandbox
 * and serves them from a wrapped `Resources` for the duration of the render. Keyed by name rather
 * than id because the JVM connector cannot see `android.R`, and ids are only stable per platform
 * build.
 */
object WallpaperSystemColors {
  /**
   * The framework's tonal-palette shade suffixes, `system_accent1_0` … `system_accent1_1000`. Shade
   * `n` is HCT tone `100 - n / 10` (`0` → white, `10` → tone 99, `500` → tone 50, `1000` → black).
   */
  val PALETTE_SHADES: List<Int> =
    listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

  /** Palette resource-name prefixes, in framework order. */
  val PALETTE_NAMES: List<String> =
    listOf("accent1", "accent2", "accent3", "neutral1", "neutral2", "error")

  /** HCT tone for a framework palette shade — see [PALETTE_SHADES]. */
  fun toneForShade(shade: Int): Int {
    require(shade in 0..1000) { "shade out of range: $shade" }
    return 100 - shade / 10
  }

  /**
   * Entry-name → ARGB for every `system_*` colour resource the wallpaper override drives, for the
   * resolved [override]. Null when the seed colour does not parse.
   */
  fun from(override: WallpaperOverride): Map<String, Int>? {
    val seed =
      runCatching { ComposeColorSpec.resolve(override.seedColor) }.getOrNull() ?: return null
    return from(
      seed = seed,
      style = override.paletteStyle ?: WallpaperPaletteStyle.TONAL_SPOT,
      contrastLevel = override.contrastLevel ?: 0.0,
    )
  }

  /**
   * Entry-name → ARGB for a seed. Both the `_light` and `_dark` role colours are produced — the
   * framework publishes both regardless of the current night mode, and the Compose readers pick one
   * by the API they call — so [WallpaperOverride.isDark] does not change the table.
   */
  fun from(
    seed: Color,
    style: WallpaperPaletteStyle = WallpaperPaletteStyle.TONAL_SPOT,
    contrastLevel: Double = 0.0,
  ): Map<String, Int> {
    val kolorStyle = style.toMaterialKolor()
    val light =
      seed.toDynamicScheme(isDark = false, style = kolorStyle, contrastLevel = contrastLevel)
    val dark =
      seed.toDynamicScheme(isDark = true, style = kolorStyle, contrastLevel = contrastLevel)
    val out = LinkedHashMap<String, Int>()
    val palettes =
      listOf(
        light.primaryPalette,
        light.secondaryPalette,
        light.tertiaryPalette,
        light.neutralPalette,
        light.neutralVariantPalette,
        light.errorPalette,
      )
    PALETTE_NAMES.zip(palettes).forEach { (name, palette) -> out.putPalette(name, palette) }
    out.putRoles(light, "_light")
    out.putRoles(dark, "_dark")
    // Fixed roles are brightness-independent by definition, so they carry no suffix.
    out["system_primary_fixed"] = light.primaryFixed
    out["system_primary_fixed_dim"] = light.primaryFixedDim
    out["system_on_primary_fixed"] = light.onPrimaryFixed
    out["system_on_primary_fixed_variant"] = light.onPrimaryFixedVariant
    out["system_secondary_fixed"] = light.secondaryFixed
    out["system_secondary_fixed_dim"] = light.secondaryFixedDim
    out["system_on_secondary_fixed"] = light.onSecondaryFixed
    out["system_on_secondary_fixed_variant"] = light.onSecondaryFixedVariant
    out["system_tertiary_fixed"] = light.tertiaryFixed
    out["system_tertiary_fixed_dim"] = light.tertiaryFixedDim
    out["system_on_tertiary_fixed"] = light.onTertiaryFixed
    out["system_on_tertiary_fixed_variant"] = light.onTertiaryFixedVariant
    return out
  }

  private fun MutableMap<String, Int>.putPalette(name: String, palette: TonalPalette) {
    for (shade in PALETTE_SHADES) put("system_${name}_$shade", palette.tone(toneForShade(shade)))
  }

  private fun MutableMap<String, Int>.putRoles(scheme: DynamicScheme, suffix: String) {
    fun role(name: String, argb: Int) = put("system_$name$suffix", argb)
    role("primary", scheme.primary)
    role("on_primary", scheme.onPrimary)
    role("primary_container", scheme.primaryContainer)
    role("on_primary_container", scheme.onPrimaryContainer)
    role("secondary", scheme.secondary)
    role("on_secondary", scheme.onSecondary)
    role("secondary_container", scheme.secondaryContainer)
    role("on_secondary_container", scheme.onSecondaryContainer)
    role("tertiary", scheme.tertiary)
    role("on_tertiary", scheme.onTertiary)
    role("tertiary_container", scheme.tertiaryContainer)
    role("on_tertiary_container", scheme.onTertiaryContainer)
    role("error", scheme.error)
    role("on_error", scheme.onError)
    role("error_container", scheme.errorContainer)
    role("on_error_container", scheme.onErrorContainer)
    role("background", scheme.background)
    role("on_background", scheme.onBackground)
    role("surface", scheme.surface)
    role("on_surface", scheme.onSurface)
    role("surface_variant", scheme.surfaceVariant)
    role("on_surface_variant", scheme.onSurfaceVariant)
    role("surface_dim", scheme.surfaceDim)
    role("surface_bright", scheme.surfaceBright)
    role("surface_container_lowest", scheme.surfaceContainerLowest)
    role("surface_container_low", scheme.surfaceContainerLow)
    role("surface_container", scheme.surfaceContainer)
    role("surface_container_high", scheme.surfaceContainerHigh)
    role("surface_container_highest", scheme.surfaceContainerHighest)
    role("outline", scheme.outline)
    role("outline_variant", scheme.outlineVariant)
    role("control_activated", scheme.controlActivated)
    role("control_normal", scheme.controlNormal)
    role("control_highlight", scheme.controlHighlight)
    role("text_primary_inverse", scheme.textPrimaryInverse)
    role("text_secondary_and_tertiary_inverse", scheme.textSecondaryAndTertiaryInverse)
    role("text_primary_inverse_disable_only", scheme.textPrimaryInverseDisableOnly)
    role(
      "text_secondary_and_tertiary_inverse_disabled",
      scheme.textSecondaryAndTertiaryInverseDisabled,
    )
    role("text_hint_inverse", scheme.textHintInverse)
    role("palette_key_color_primary", scheme.primaryPaletteKeyColor)
    role("palette_key_color_secondary", scheme.secondaryPaletteKeyColor)
    role("palette_key_color_tertiary", scheme.tertiaryPaletteKeyColor)
  }
}
