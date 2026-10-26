package ee.schimke.composeai.renderer

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Serves Android 12+ **system dynamic colour resources** (`android.R.color.system_accent1_*` …
 * `system_neutral2_*`, and the Android 14+ `system_primary_light` … role colours) from a supplied
 * table for the duration of one composition, so a preview theming itself from them —
 * `androidx.compose.material3.dynamicLightColorScheme(LocalContext.current)`, or Wear's
 * `androidx.wear.compose.material3.dynamicColorScheme(LocalContext.current)` — picks the table up.
 *
 * On a device those resources are overlaid by the wallpaper picker. Robolectric ships the AOSP
 * defaults and has no overlay mechanism, so the table is served from a wrapped `Resources` behind a
 * wrapped `LocalContext` instead — the same route the pseudolocale connector takes for strings, and
 * deliberately not a Robolectric shadow: `Resources` / `ResourcesImpl` can only carry one shadow,
 * and a shadow would change the colours for every context in the sandbox (including the next
 * render's), where a wrapped context is scoped to this composition by construction.
 *
 * What it does not reach: a lookup that does not go through `LocalContext` (e.g. an XML theme
 * attribute already resolved against the activity's own `Resources`), and code that casts
 * `LocalContext.current` to an `Activity` (use `LocalActivity` instead) — the same caveat as the
 * pseudolocale override.
 *
 * **Wear Material 3.** When `androidx.wear.compose.material3` is loadable from [loader], two more
 * things happen:
 * - Wear's `dynamicColorScheme(context)` returns null unless the Wear OS 5 global setting
 *   `dynamic_color_theme_enabled` is `1`, so it is set for the life of this composition and the
 *   previous value is restored when it leaves (no leak into the next render). The setting is
 *   process-global under Robolectric, so the restore lands when the composition forgets this call:
 *   a sibling composed in the very recomposition that removes it still sees `1` for that pass.
 * - Content is wrapped in Wear's `MaterialTheme(colorScheme = dynamicColorScheme(context))`,
 *   invoked by reflection (the renderer never links wear-compose). A preview that does not pass its
 *   own `colorScheme` inherits it; one that passes an explicit `ColorScheme` keeps it — the
 *   override cannot reach a scheme the preview built by hand.
 */
object SystemDynamicColors {

  /** Wear OS's global switch read by Wear Material 3's `dynamicColorScheme(context)`. */
  const val WEAR_DYNAMIC_COLOR_SETTING: String = "dynamic_color_theme_enabled"

  private const val WEAR_MATERIAL_THEME_KT = "androidx.wear.compose.material3.MaterialThemeKt"
  private const val WEAR_DYNAMIC_COLOR_SCHEME_KT =
    "androidx.wear.compose.material3.DynamicColorSchemeKt"
  private const val WEAR_COLOR_SCHEME = "androidx.wear.compose.material3.ColorScheme"

  /**
   * Resolve a `system_*` entry-name → ARGB table to resource id → ARGB against the running
   * platform. Names the platform does not define (e.g. `system_error_*` before Android 16, or the
   * role colours before 14) are dropped.
   */
  fun resolveIds(colorsByName: Map<String, Int>): Map<Int, Int> {
    val out = HashMap<Int, Int>(colorsByName.size)
    for ((name, argb) in colorsByName) {
      val id =
        runCatching { android.R.color::class.java.getField(name).getInt(null) }.getOrNull()
          ?: Resources.getSystem().getIdentifier(name, "color", "android").takeIf { it != 0 }
          ?: continue
      out[id] = argb
    }
    return out
  }

  /** [base] with its `Resources` answering [colorsById] for those ids. */
  fun wrap(base: Context, colorsById: Map<Int, Int>): Context =
    SystemDynamicColorContext(base, SystemDynamicColorResources(base.resources, colorsById))

  /**
   * Provide a `LocalContext` serving [colorsByName] and, on a Wear Material 3 classpath, a Wear
   * `MaterialTheme` built from it. See the object KDoc.
   */
  @Composable
  fun Provide(
    colorsByName: Map<String, Int>,
    loader: ClassLoader?,
    content: @Composable () -> Unit,
  ) {
    val base = LocalContext.current
    val wrapped = remember(base, colorsByName) { wrap(base, resolveIds(colorsByName)) }
    val wearLoader =
      remember(loader) {
        (loader ?: SystemDynamicColors::class.java.classLoader)?.takeIf {
          runCatching { Class.forName(WEAR_MATERIAL_THEME_KT, false, it) }.isSuccess
        }
      }
    CompositionLocalProvider(LocalContext provides wrapped) {
      if (wearLoader == null) {
        content()
      } else {
        // Enabled during composition (not in an effect) because the scheme below, and any
        // `dynamicColorScheme(LocalContext.current)` the preview makes itself, is computed in
        // this same composition pass. Restored when this call leaves the composition.
        remember(wrapped) { WearDynamicColorSetting(wrapped.contentResolver) }
        val scheme = remember(wrapped, wearLoader) { wearDynamicColorScheme(wrapped, wearLoader) }
        if (scheme == null) content() else WearMaterialTheme(scheme, wearLoader, content)
      }
    }
  }

  /** Wear's `dynamicColorScheme(context)`, by reflection; null when unavailable. */
  internal fun wearDynamicColorScheme(context: Context, loader: ClassLoader): Any? = runCatching {
    Class.forName(WEAR_DYNAMIC_COLOR_SCHEME_KT, true, loader)
      .getMethod("dynamicColorScheme", Context::class.java)
      .invoke(null, context)
  }
    .getOrNull()

  /**
   * `androidx.wear.compose.material3.MaterialTheme(colorScheme = scheme, content = content)` by
   * reflection. The JVM signature is `(ColorScheme, Typography, Shapes, MotionScheme, Function2,
   * Composer, $changed, $default)` on current releases; every parameter between the scheme and the
   * content is left to its default via the `$default` mask, so a release that adds one more still
   * matches. Falls back to plain [content] if no such overload exists.
   */
  @Composable
  private fun WearMaterialTheme(scheme: Any, loader: ClassLoader, content: @Composable () -> Unit) {
    val composer = currentComposer
    val method =
      remember(loader) {
        runCatching {
          val schemeClass = Class.forName(WEAR_COLOR_SCHEME, false, loader)
          Class.forName(WEAR_MATERIAL_THEME_KT, true, loader).methods.firstOrNull { m ->
            val p = m.parameterTypes
            m.name == "MaterialTheme" &&
              p.size >= 5 &&
              p[0] == schemeClass &&
              p[p.size - 3].name == "androidx.compose.runtime.Composer" &&
              p[p.size - 2] == Int::class.javaPrimitiveType &&
              p[p.size - 1] == Int::class.javaPrimitiveType &&
              p[p.size - 4].name == "kotlin.jvm.functions.Function2"
          }
        }
          .getOrNull()
      }
    if (method == null) {
      content()
      return
    }
    val realParams = method.parameterCount - 3
    val args = arrayOfNulls<Any>(method.parameterCount)
    args[0] = scheme
    args[realParams - 1] = content
    args[realParams] = composer
    args[realParams + 1] = 0
    // Default every real parameter except the scheme (bit 0) and the content (last).
    var defaults = 0
    for (i in 1 until realParams - 1) defaults = defaults or (1 shl i)
    args[realParams + 2] = defaults
    method.invoke(null, *args)
  }
}

/**
 * Sets [SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING] to `1` on construction and puts the
 * previous value back when the owning composition forgets it.
 */
private class WearDynamicColorSetting(private val resolver: ContentResolver) : RememberObserver {
  private val previous: String? = runCatching {
    Settings.Global.getString(resolver, SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING)
  }
    .getOrNull()
  private var restored = false

  init {
    runCatching {
      Settings.Global.putInt(resolver, SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING, 1)
    }
  }

  override fun onRemembered() {}

  override fun onForgotten() = restore()

  override fun onAbandoned() = restore()

  private fun restore() {
    if (restored) return
    restored = true
    runCatching {
      Settings.Global.putString(resolver, SystemDynamicColors.WEAR_DYNAMIC_COLOR_SETTING, previous)
    }
  }
}

private class SystemDynamicColorContext(base: Context, private val res: Resources) :
  ContextWrapper(base) {
  override fun getResources(): Resources = res
}

/**
 * `Resources` answering a fixed set of colour ids from a table and forwarding **everything else to
 * [base]**, never to `super`: the daemon stacks resource wrappers (the placeholder fallback, the
 * `resources/used` recorder, pseudolocale), and `super` resolves against the raw table, stepping
 * over whichever wrapper this one was installed on. `PseudolocaleResources` documents the same
 * rule.
 */
@Suppress("DEPRECATION")
internal class SystemDynamicColorResources(
  private val base: Resources,
  private val colors: Map<Int, Int>,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

  override fun getColor(id: Int): Int = colors[id] ?: base.getColor(id)

  override fun getColor(id: Int, theme: Theme?): Int = colors[id] ?: base.getColor(id, theme)

  override fun getColorStateList(id: Int): ColorStateList =
    colors[id]?.let(ColorStateList::valueOf) ?: base.getColorStateList(id)

  override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
    colors[id]?.let(ColorStateList::valueOf) ?: base.getColorStateList(id, theme)

  override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) {
    val argb = colors[id]
    if (argb == null) {
      base.getValue(id, outValue, resolveRefs)
      return
    }
    outValue.type = TypedValue.TYPE_INT_COLOR_ARGB8
    outValue.data = argb
    outValue.resourceId = id
    outValue.string = null
    outValue.changingConfigurations = 0
  }

  override fun getText(id: Int): CharSequence = base.getText(id)

  override fun getText(id: Int, def: CharSequence): CharSequence = base.getText(id, def)

  override fun getQuantityText(id: Int, quantity: Int): CharSequence =
    base.getQuantityText(id, quantity)

  override fun getTextArray(id: Int): Array<CharSequence> = base.getTextArray(id)

  override fun getStringArray(id: Int): Array<String> = base.getStringArray(id)

  override fun getIntArray(id: Int): IntArray = base.getIntArray(id)

  override fun getInteger(id: Int): Int = base.getInteger(id)

  override fun getBoolean(id: Int): Boolean = base.getBoolean(id)

  override fun getDimension(id: Int): Float = base.getDimension(id)

  override fun getDimensionPixelOffset(id: Int): Int = base.getDimensionPixelOffset(id)

  override fun getDimensionPixelSize(id: Int): Int = base.getDimensionPixelSize(id)

  override fun getDrawable(id: Int): Drawable = base.getDrawable(id)

  override fun getDrawable(id: Int, theme: Theme?): Drawable = base.getDrawable(id, theme)

  override fun getDrawableForDensity(id: Int, density: Int): Drawable? =
    base.getDrawableForDensity(id, density)

  override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
    base.getDrawableForDensity(id, density, theme)
}
