package ee.schimke.composeai.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.jvm.JvmName

/**
 * The [PreviewOverrideHost] a render gets when nothing provides [LocalPreviewOverrideHost] — the
 * platform's default binding.
 *
 * On the JVM this is `ControllerPreviewOverrideHost`, backed by the process-static
 * `PreviewOverrideController` that the daemon connector seeds and the `compose/overrides` producer
 * reads. Platforms with no controller (wasmJs) bind a host that resolves every lookup to its author
 * default and records nothing — so a `previewOverride*` call is still a legal, behaviour-preserving
 * read of the default there, which is what makes the helpers callable from `commonMain`.
 */
expect val DefaultPreviewOverrideHost: PreviewOverrideHost

/**
 * A closed-value-set entry for [previewOverrideChoice]: the `value` a preview reads, and the
 * `label` a viewer shows for it (defaulting to the value).
 *
 * `expect` rather than a plain common class so the JVM keeps the *existing*
 * `ee.schimke.composeai.data.overrides.PreviewOverrideOption` — the serializable wire shape from
 * `:data-preview-overrides-core`, which is JVM-only — as its `actual typealias`. That makes the
 * multiplatform split source- and binary-compatible for every JVM consumer already importing the
 * core type, while `commonMain` gets a name it can compile against.
 */
expect class PreviewOverrideOption {
  val value: String
  val label: String
}

/**
 * Builds a [PreviewOverrideOption]; `label` defaults to `value`.
 *
 * A factory rather than a constructor on the `expect` class: an `actual typealias` may not
 * actualize an `expect` constructor whose target declares default argument values
 * (`DEFAULT_ARGUMENTS_IN_EXPECT_WITH_ACTUAL_TYPEALIAS`), and declaring the constructor without the
 * default then makes the two incompatible. Leaving the constructor off the `expect` altogether
 * costs common code one function name and costs JVM code nothing at all — through the typealias a
 * JVM consumer still sees the core class and calls its constructor exactly as before.
 */
expect fun previewOverrideOption(value: String, label: String = value): PreviewOverrideOption

/**
 * Composition local exposing the live named-override surface to a preview. Wired to
 * [DefaultPreviewOverrideHost] by default — on the JVM the process-static override controller — so
 * the `previewOverride*` lookups work in a plain Gradle render with no daemon (every lookup returns
 * its author default and records its declaration). The connector's around-composable
 * (`:data-preview-overrides-connector`) seeds the same controller before the preview composes, so
 * the lookups then return the daemon-supplied values. Tests can provide a fake host.
 */
val LocalPreviewOverrideHost: ProvidableCompositionLocal<PreviewOverrideHost> = compositionLocalOf {
  DefaultPreviewOverrideHost
}

/**
 * Resolves an author-declared, keyed editable knob to its effective value, recording the
 * declaration so a producer can enumerate "what is editable" on this preview. The
 * `previewOverride*` top-level helpers delegate here.
 */
interface PreviewOverrideHost {
  @Composable fun string(key: String, default: String, index: Int?): String

  @Composable fun int(key: String, default: Int, index: Int?): Int

  @Composable fun float(key: String, default: Float, index: Int?): Float

  @Composable fun boolean(key: String, default: Boolean, index: Int?): Boolean

  @Composable fun color(key: String, default: Color, index: Int?): Color

  @Composable fun dp(key: String, default: Dp, index: Int?): Dp

  /**
   * Declare and resolve a **font-family** string knob — a [string] knob a viewer renders as an
   * autocomplete over [suggestions] (typically the declared `@TypographyCatalog` names), optionally
   * splicing the full Google Fonts family list when [googleFonts]. The default implementation
   * ignores the extra metadata and behaves exactly like [string]; the controller-backed host
   * overrides it to record the richer declaration. Having a default body keeps existing
   * [PreviewOverrideHost] implementations (test fakes) source-compatible.
   */
  @Composable
  fun font(
    key: String,
    default: String,
    index: Int?,
    suggestions: List<String>,
    googleFonts: Boolean,
  ): String = string(key, default, index)

  /**
   * Declare and resolve a **closed-value-set** string knob — a [string] knob whose value must be
   * one of [options], which a viewer renders as a picker rather than a text box. Like [font], the
   * default body ignores the extra metadata and behaves exactly like [string], so existing
   * [PreviewOverrideHost] implementations (test fakes) stay source-compatible.
   */
  @Composable
  fun choice(
    key: String,
    default: String,
    index: Int?,
    options: List<PreviewOverrideOption>,
  ): String = string(key, default, index)
}

// --- Public opt-in lookups
// -------------------------------------------------------------------------

/**
 * Declare and resolve an editable **string** knob keyed by [key]. In a daemon-backed render the
 * daemon's `renderNow.overrides.namedOverrides` entry for [key] (or the bracket-indexed key when
 * [index] is set) replaces [default]; otherwise [default] is returned. Either way the knob is
 * recorded so a viewer can present an editable control. Opt-in: only previews that call this expose
 * the knob.
 *
 * Example (a list whose length and per-row label are both editable):
 * ```
 * val rows = previewOverrideInt("rowCount", 3)
 * Column { repeat(rows) { i -> Text(previewOverrideString("rowLabel", "Item ${i + 1}", index = i)) } }
 * ```
 */
@Composable
fun previewOverrideString(key: String, default: String, index: Int? = null): String =
  LocalPreviewOverrideHost.current.string(key, default, index)

/**
 * Editable **int** knob. The natural type for a list length / item count. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideInt(key: String, default: Int, index: Int? = null): Int =
  LocalPreviewOverrideHost.current.int(key, default, index)

/** Editable **float** knob. See [previewOverrideString]. */
@Composable
fun previewOverrideFloat(key: String, default: Float, index: Int? = null): Float =
  LocalPreviewOverrideHost.current.float(key, default, index)

/** Editable **boolean** knob. See [previewOverrideString]. */
@Composable
fun previewOverrideBoolean(key: String, default: Boolean, index: Int? = null): Boolean =
  LocalPreviewOverrideHost.current.boolean(key, default, index)

/** Editable **color** knob, carried on the wire as `#AARRGGBB`. See [previewOverrideString]. */
@Composable
fun previewOverrideColor(key: String, default: Color, index: Int? = null): Color =
  LocalPreviewOverrideHost.current.color(key, default, index)

/**
 * Editable **Dp** knob (e.g. a component size), carried on the wire as a float. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideDp(key: String, default: Dp, index: Int? = null): Dp =
  LocalPreviewOverrideHost.current.dp(key, default, index)

/**
 * Editable **font-family** knob: a string knob a viewer renders as an autocompleting text field
 * seeded with [suggestions] (typically the declared `@TypographyCatalog` names, shown first) and —
 * when [googleFonts] (the default) — the full fonts.google.com family list, while staying free-text
 * so any typed family resolves. Resolves like [previewOverrideString] (the daemon-seeded value, or
 * [default]); the extra metadata only shapes the control a viewer offers. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideFont(
  key: String,
  default: String,
  suggestions: List<String> = emptyList(),
  googleFonts: Boolean = true,
  index: Int? = null,
): String = LocalPreviewOverrideHost.current.font(key, default, index, suggestions, googleFonts)

/**
 * Editable **choice** knob: a string knob whose value set is closed, so a viewer offers a picker
 * over [options] instead of a text field the visitor has to already know the vocabulary for.
 *
 * This is the knob for an axis with a fixed alphabet — a size (`xs`/`s`/`m`/`l`/`xl`), a shape
 * (`round`/`square`), a state (`enabled`/`disabled`). Declared as a plain [previewOverrideString]
 * those render as an empty-looking text box: the value is visible but the *alternatives* are not,
 * so `xl` is only reachable by someone who has read the source.
 *
 * Each option may carry a [PreviewOverrideOption.label], which is what the picker shows — the wire
 * value stays the slug the preview reads, so seeding and `@OverrideVariant` are unaffected:
 * ```
 * val size = previewOverrideChoice(
 *   "size",
 *   default = "s",
 *   options = listOf(PreviewOverrideOption("xs", "Extra small"), PreviewOverrideOption("s", "Small")),
 * )
 * ```
 *
 * Resolves exactly like [previewOverrideString] — the daemon-seeded value, or [default]. Nothing
 * *enforces* the set at render time: a seed outside it still reaches the composable, which keeps a
 * stale link or a hand-written URL rendering rather than failing. The set shapes the control, and
 * the control is what makes the axis discoverable.
 */
@Composable
fun previewOverrideChoice(
  key: String,
  default: String,
  options: List<PreviewOverrideOption>,
  index: Int? = null,
): String = LocalPreviewOverrideHost.current.choice(key, default, index, options)

/**
 * [previewOverrideChoice] over values that are already their own labels — the common case for an
 * axis whose slugs read as words (`round` / `square`, `enabled` / `disabled`).
 */
@JvmName("previewOverrideChoiceValues")
@Composable
fun previewOverrideChoice(
  key: String,
  default: String,
  values: List<String>,
  index: Int? = null,
): String = previewOverrideChoice(key, default, values.map { previewOverrideOption(it) }, index)
