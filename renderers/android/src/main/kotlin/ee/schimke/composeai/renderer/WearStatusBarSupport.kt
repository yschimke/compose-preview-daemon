package ee.schimke.composeai.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.ProvidableCompositionLocal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Coordinates Wear OS Global Status Bar (GSB) support for normal `@Preview` captures and the render
 * daemon (both one-shot `renderNow` and live interactive sessions).
 *
 * Enablement rules mirror Wear OS 7.1 Canary + Compose for Wear OS 1.8.0-alpha02+:
 * - Only applies to round Wear previews (`isRoundDevice(device)`) that are not tiles and not Wear
 *   widgets.
 * - Explicit override via `-Dcomposeai.wear.globalStatusBar=true|false`:
 *     - `"false"` disables GSB unconditionally.
 *     - `"true"` enables GSB on round non-tile/non-widget Wear previews (providing
 *       `LocalStatusBarEnabled = true` whenever Wear Compose Material 3 1.8+ is on the classpath).
 * - When the property is unset (default): auto-enables when `showSystemUi == true`, Wear Compose
 *   Material 3 1.8+ exposes `LocalStatusBarEnabled` on the consumer classpath, and the app's
 *   manifest has not explicitly opted out with `com.google.wear.ENABLE_GLOBAL_STATUS_BAR = false`.
 *   Existing Wear Compose <= 1.7 previews are unaffected so legacy `TimeText` is never duplicated.
 *
 * When active, [installSystemStatusBar] enables Robolectric's `SystemUi` `STANDARD_STATUS_BAR` so
 * `WindowInsets.statusBarsIgnoringVisibility` supplies real top status bar padding to
 * `ScreenScaffold` and `WindowInsetsController.hide/show(WindowInsets.Type.statusBars())` drives
 * [WearStatusBarFrame]'s visibility dynamically (honouring `Modifier.suppressStatusBar()`,
 * `AlertDialog`, `TimePicker`, `DatePicker`, `Stepper`, custom `TimeText`, and scroll auto-hide).
 */
object WearStatusBarSupport {
  const val PROP_ENABLED: String = "composeai.wear.globalStatusBar"
  const val PROP_INDICATOR: String = "composeai.wear.globalStatusBar.indicator"
  const val META_DATA_ENABLE_GLOBAL_STATUS_BAR: String = "com.google.wear.ENABLE_GLOBAL_STATUS_BAR"
  const val DEFAULT_CLOCK_TEXT: String = "10:08"

  private val CLOCK_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("H:mm", Locale.US).withZone(ZoneOffset.UTC)

  data class Resolved(
    val active: Boolean,
    val localStatusBarEnabled: ProvidableCompositionLocal<Boolean>?,
    val clockText: String,
    val indicator: WearStatusBarIndicator?,
    val ambient: Boolean,
  ) {
    companion object {
      val INACTIVE =
        Resolved(
          active = false,
          localStatusBarEnabled = null,
          clockText = DEFAULT_CLOCK_TEXT,
          indicator = null,
          ambient = false,
        )
    }
  }

  /**
   * Resolves whether Wear OS GSB should be active for a preview render.
   *
   * @param isRound Whether the preview targets a round Wear device.
   * @param showSystemUi `@Preview(showSystemUi = ...)` flag.
   * @param isTileOrWidget True for Wear Tiles and Wear Widgets, where the system status bar never
   *   overlays the surface.
   * @param classLoader Consumer classloader used to probe `WearStatusBarLocal`.
   * @param context Optional Android context used to check manifest opt-out meta-data.
   * @param clockEpochMillis Optional deterministic preview clock override (formatted in UTC).
   * @param ambient True when rendering in Wear ambient mode (suppresses the GSB overlay).
   * @param propertyLookup Seam for tests; defaults to [System.getProperty].
   */
  fun resolve(
    isRound: Boolean,
    showSystemUi: Boolean,
    isTileOrWidget: Boolean,
    classLoader: ClassLoader?,
    context: Context? = null,
    clockEpochMillis: Long? = null,
    ambient: Boolean = false,
    propertyLookup: (String) -> String? = System::getProperty,
  ): Resolved {
    if (!isRound || isTileOrWidget) return Resolved.INACTIVE

    val rawProp = propertyLookup(PROP_ENABLED)?.trim()
    val explicitFalse = rawProp.equals("false", ignoreCase = true)
    if (explicitFalse) return Resolved.INACTIVE

    val explicitTrue = rawProp.equals("true", ignoreCase = true)
    val local = WearStatusBarLocal.get(classLoader)

    val active =
      if (explicitTrue) {
        true
      } else {
        showSystemUi && local != null && !isManifestOptedOut(context)
      }
    if (!active) return Resolved.INACTIVE

    val indicator = WearStatusBarIndicator.parse(propertyLookup(PROP_INDICATOR))
    val clockText = formatClock(clockEpochMillis)
    return Resolved(
      active = true,
      localStatusBarEnabled = local,
      clockText = clockText,
      indicator = indicator,
      ambient = ambient,
    )
  }

  /** Formats [clockEpochMillis] as `H:mm` (UTC), or [DEFAULT_CLOCK_TEXT] when null. */
  fun formatClock(clockEpochMillis: Long?): String {
    if (clockEpochMillis == null) return DEFAULT_CLOCK_TEXT
    return runCatching { CLOCK_FORMATTER.format(Instant.ofEpochMilli(clockEpochMillis)) }
      .getOrDefault(DEFAULT_CLOCK_TEXT)
  }

  /**
   * Returns `true` only when the app manifest explicitly declares `<meta-data
   * android:name="com.google.wear.ENABLE_GLOBAL_STATUS_BAR" android:value="false"/>`.
   */
  fun isManifestOptedOut(context: Context?): Boolean {
    if (context == null) return false
    return runCatching {
        val pm = context.packageManager ?: return false
        @Suppress("DEPRECATION")
        val appInfo = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val meta = appInfo.metaData ?: return false
        meta.containsKey(META_DATA_ENABLE_GLOBAL_STATUS_BAR) &&
          !meta.getBoolean(META_DATA_ENABLE_GLOBAL_STATUS_BAR, true)
      }
      .getOrDefault(false)
  }

  /**
   * Activates Robolectric's `SystemUi` `STANDARD_STATUS_BAR` on the default display so
   * `WindowInsets.statusBarsIgnoringVisibility` provides real top status-bar insets and
   * `WindowInsetsController.hide/show(WindowInsets.Type.statusBars())` toggles visibility.
   *
   * Safe no-op when running outside Robolectric or on an older Robolectric without `SystemUi`.
   */
  fun installSystemStatusBar(): Boolean = setRobolectricStatusBarBehavior("STANDARD_STATUS_BAR")

  /**
   * Resets Robolectric's `SystemUi` status bar behavior to `NONE` so a reused daemon sandbox does
   * not leak Wear status bar insets into subsequent non-GSB previews.
   */
  fun clearSystemStatusBar(): Boolean = setRobolectricStatusBarBehavior("NO_STATUS_BAR")

  /**
   * Returns whether the system status bar is currently visible for [view]. Reads Robolectric's
   * `SystemUi.StatusBar.isVisible()` first (which tracks `WindowInsetsController.hide/show`
   * immediately even before a fresh insets pass), falling back to
   * `view.rootWindowInsets.isVisible(WindowInsets.Type.statusBars())` on API 30+.
   */
  fun isStatusBarVisible(view: View?): Boolean {
    val robolectricVisible = readRobolectricStatusBarVisible()
    if (robolectricVisible != null) return robolectricVisible
    if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val insets = view.rootWindowInsets
      if (insets != null) {
        return insets.isVisible(WindowInsets.Type.statusBars())
      }
    }
    return true
  }

  private fun setRobolectricStatusBarBehavior(fieldName: String): Boolean = runCatching {
    val loader = WearStatusBarSupport::class.java.classLoader
    val systemUiClass = Class.forName("org.robolectric.shadows.SystemUi", true, loader)
    val behaviorClass =
      Class.forName("org.robolectric.shadows.SystemUi\$StatusBarBehavior", true, loader)
    val systemUi =
      systemUiClass
        .getDeclaredMethod("systemUiForDefaultDisplay")
        .apply { isAccessible = true }
        .invoke(null)
    val behaviorField = systemUiClass.getDeclaredField(fieldName).apply { isAccessible = true }
    val behaviorValue = behaviorField.get(null)
    val setBehavior =
      systemUiClass.getDeclaredMethod("setStatusBarBehavior", behaviorClass).apply {
        isAccessible = true
      }
    setBehavior.invoke(systemUi, behaviorValue)
    if (fieldName == "STANDARD_STATUS_BAR") {
      val getStatusBar =
        systemUiClass.getDeclaredMethod("getStatusBar").apply { isAccessible = true }
      val statusBar = getStatusBar.invoke(systemUi)
      if (statusBar != null) {
        val setVisible =
          statusBar.javaClass
            .getDeclaredMethod("setVisible", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        setVisible.invoke(statusBar, true)
      }
    }
    true
  }
    .getOrDefault(false)

  private fun readRobolectricStatusBarVisible(): Boolean? = runCatching {
    val loader = WearStatusBarSupport::class.java.classLoader
    val systemUiClass = Class.forName("org.robolectric.shadows.SystemUi", true, loader)
    val systemUi =
      systemUiClass
        .getDeclaredMethod("systemUiForDefaultDisplay")
        .apply { isAccessible = true }
        .invoke(null)
    val getStatusBar = systemUiClass.getDeclaredMethod("getStatusBar").apply { isAccessible = true }
    val statusBar = getStatusBar.invoke(systemUi) ?: return null
    val isVisible = statusBar.javaClass.getDeclaredMethod("isVisible").apply { isAccessible = true }
    isVisible.invoke(statusBar) as? Boolean
  }
    .getOrNull()
}
