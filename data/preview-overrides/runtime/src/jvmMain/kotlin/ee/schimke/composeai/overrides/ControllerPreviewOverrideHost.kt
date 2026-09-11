package ee.schimke.composeai.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType

/**
 * Default [PreviewOverrideHost] backed by the process-static [PreviewOverrideController]. Each read
 * (a) resolves the controller's seeded value for the (key, index) — falling back to [default] when
 * none is bound or the bound value's type doesn't match — and (b) records the declaration (with its
 * resolved `current`) into the controller so the `compose/overrides` producer can surface it.
 */
object ControllerPreviewOverrideHost : PreviewOverrideHost {

  @Composable
  override fun string(key: String, default: String, index: Int?): String {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.StringValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.STRING,
      PreviewOverrideValue.StringValue(default),
      PreviewOverrideValue.StringValue(effective),
    )
    return effective
  }

  @Composable
  override fun int(key: String, default: Int, index: Int?): Int {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.IntValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.INT,
      PreviewOverrideValue.IntValue(default),
      PreviewOverrideValue.IntValue(effective),
    )
    return effective
  }

  @Composable
  override fun float(key: String, default: Float, index: Int?): Float {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.FloatValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.FLOAT,
      PreviewOverrideValue.FloatValue(default),
      PreviewOverrideValue.FloatValue(effective),
    )
    return effective
  }

  @Composable
  override fun boolean(key: String, default: Boolean, index: Int?): Boolean {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.BooleanValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.BOOL,
      PreviewOverrideValue.BooleanValue(default),
      PreviewOverrideValue.BooleanValue(effective),
    )
    return effective
  }

  @Composable
  override fun color(key: String, default: Color, index: Int?): Color {
    val seeded by PreviewOverrideController.seededValues
    val seededArgb = (seeded[seedKey(key, index)] as? PreviewOverrideValue.ColorValue)?.argb
    val effective = seededArgb?.let(::parseColorOrNull) ?: default
    declare(
      key,
      index,
      PreviewOverrideType.COLOR,
      PreviewOverrideValue.ColorValue(default.toArgbHex()),
      PreviewOverrideValue.ColorValue(effective.toArgbHex()),
    )
    return effective
  }

  @Composable
  override fun dp(key: String, default: Dp, index: Int?): Dp {
    // Dp carried as a plain float (its `.value`); no Remote-style dp wrapper.
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.FloatValue)?.value ?: default.value
    declare(
      key,
      index,
      PreviewOverrideType.FLOAT,
      PreviewOverrideValue.FloatValue(default.value),
      PreviewOverrideValue.FloatValue(effective),
    )
    return effective.dp
  }

  @Composable
  override fun font(
    key: String,
    default: String,
    index: Int?,
    suggestions: List<String>,
    googleFonts: Boolean,
  ): String {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.StringValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.STRING,
      PreviewOverrideValue.StringValue(default),
      PreviewOverrideValue.StringValue(effective),
      suggestions = suggestions,
      googleFonts = googleFonts,
    )
    return effective
  }

  @Composable
  override fun choice(
    key: String,
    default: String,
    index: Int?,
    options: List<PreviewOverrideOption>,
  ): String {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.StringValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.STRING,
      PreviewOverrideValue.StringValue(default),
      PreviewOverrideValue.StringValue(effective),
      options = options,
      // A choice knob's set is closed by construction — that is what distinguishes it from the
      // `suggestions` a font knob offers over a field that stays free-text.
      optionsExhaustive = true,
    )
    return effective
  }

  @Composable
  private fun declare(
    key: String,
    index: Int?,
    type: String,
    default: PreviewOverrideValue,
    current: PreviewOverrideValue,
    suggestions: List<String> = emptyList(),
    googleFonts: Boolean = false,
    options: List<PreviewOverrideOption> = emptyList(),
    optionsExhaustive: Boolean = false,
  ) {
    val declaration =
      PreviewOverrideDeclaration(
        key = key,
        type = type,
        label = key,
        default = default,
        current = current,
        index = index,
        suggestions = suggestions,
        googleFonts = googleFonts,
        options = options,
        optionsExhaustive = optionsExhaustive,
      )
    // Record on commit, not mid-composition: keeps the controller mutation a side effect of a
    // successful composition (idempotent — the controller de-dupes by seedKey).
    SideEffect { PreviewOverrideController.record(declaration) }
  }
}

private fun seedKey(key: String, index: Int?): String = if (index == null) key else "$key[$index]"

/** `#AARRGGBB` for a Compose [Color] (converted to sRGB by [toArgb]). */
internal fun Color.toArgbHex(): String = "#%08X".format(toArgb())

/** Parse `#AARRGGBB` / `#RRGGBB` (or without `#`) to a [Color], or null when malformed. */
internal fun parseColorOrNull(hex: String): Color? {
  val raw = hex.removePrefix("#")
  val v = raw.toLongOrNull(16) ?: return null
  return when (raw.length) {
    8 -> Color((v and 0xFFFFFFFF).toInt())
    6 -> Color((0xFF000000 or v).toInt())
    else -> null
  }
}
