package ee.schimke.composeai.overrides

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * wasmJs binding of [PreviewOverrideOption].
 *
 * A plain data class rather than the JVM's serializable core shape: `:data-preview-overrides-core`
 * is a JVM artifact, and nothing on this platform puts an option on the wire — the daemon seeds and
 * the `compose/overrides` producer both run JVM-side. What wasmJs needs is the *authoring* type, so
 * a `commonMain` preview can declare its choices and read its defaults back.
 */
actual class PreviewOverrideOption(value: String, label: String) {
  actual val value: String = value
  actual val label: String = label
}

actual fun previewOverrideOption(value: String, label: String): PreviewOverrideOption =
  PreviewOverrideOption(value, label)

/**
 * wasmJs default host: resolves every lookup to the author default and records nothing.
 *
 * There is no override controller on this platform — no daemon seeds one and no producer reads one
 * — so the honest binding is the identity one. That keeps a `previewOverride*` call in `commonMain`
 * legal and behaviour-preserving in a browser tier, exactly as it is in a plain Gradle render with
 * no daemon; what it does not do there is surface the knob to a viewer.
 */
actual val DefaultPreviewOverrideHost: PreviewOverrideHost = DefaultingPreviewOverrideHost

private object DefaultingPreviewOverrideHost : PreviewOverrideHost {
  @Composable override fun string(key: String, default: String, index: Int?): String = default

  @Composable override fun int(key: String, default: Int, index: Int?): Int = default

  @Composable override fun float(key: String, default: Float, index: Int?): Float = default

  @Composable override fun boolean(key: String, default: Boolean, index: Int?): Boolean = default

  @Composable override fun color(key: String, default: Color, index: Int?): Color = default

  @Composable override fun dp(key: String, default: Dp, index: Int?): Dp = default
}
