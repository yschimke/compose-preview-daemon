package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import ee.schimke.composeai.daemon.protocol.DataExtensionId
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.renderer.SystemDynamicColors

/**
 * The Android half of `renderNow.overrides.wallpaper` (issue #400).
 *
 * [WallpaperOverrideExtension] (JVM, shared with desktop) wraps the preview in an
 * `androidx.compose.material3.MaterialTheme` built from the seed. That reaches only previews that
 * inherit that theme. This one makes the *platform* agree with it: the `android.R.color.system_*`
 * dynamic colour resources are served from [WallpaperSystemColors] for this render through a
 * wrapped `LocalContext` ([SystemDynamicColors]), so `dynamicLightColorScheme(context)` /
 * `dynamicDarkColorScheme(context)` and Wear's `dynamicColorScheme(context)` follow the seed, and
 * on a Wear Material 3 classpath the preview is wrapped in Wear's `MaterialTheme` with that scheme.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the wrapped context is in scope before the
 * user-environment wrappers (theme provider, the Material 3 wallpaper wrap) compose.
 *
 * The override's value object crosses into the sandbox like every other override (it is the
 * planner's input), so the 5×13 tones need no channel of their own: they are derived here, inside
 * the sandbox, from the same [ee.schimke.composeai.daemon.protocol.WallpaperOverride].
 */
class WallpaperSystemColorsOverrideExtension(private val colorsByName: Map<String, Int>) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    // The render installs the preview's own child classloader as the context loader (RenderEngine
    // `render`), so this is the loader wear-compose resolves from when the app has it.
    val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
    SystemDynamicColors.Provide(colorsByName, loader, content)
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("compose/wallpaper-system-colors")
  }
}

/** Plans [WallpaperSystemColorsOverrideExtension] when `overrides.wallpaper` carries a seed. */
class WallpaperSystemColorsPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = WallpaperSystemColorsOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.wallpaper
      ?.let(WallpaperSystemColors::from)
      ?.let(::WallpaperSystemColorsOverrideExtension)
}
