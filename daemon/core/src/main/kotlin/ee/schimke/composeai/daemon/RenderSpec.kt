package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FigmaSvgBackgroundMode
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything a backend needs to produce one PNG: the preview to invoke, the frame to invoke it in,
 * and the per-call overrides that reshape both.
 *
 * **One type, both backends.** This used to be two `RenderSpec` declarations — one inside
 * `:daemon:android`'s `RenderEngine.kt`, one inside `:daemon:desktop`'s — each carrying a comment
 * claiming it was "field-for-field identical to its twin so one payload string drives either
 * backend". They were not: `assetPath`, `showSystemUi` and `slotMode` existed only on desktop, and
 * `captureAdvanceMs` and `previewName` only on Android. Nothing could have caught that, because the
 * only thing tying the two together was a `;`-delimited `key=value` string that each side parsed
 * with its own hand-written reader. A field one side forgot decoded to its default on the other and
 * the render silently came back wrong.
 *
 * So the type lives here, in the renderer-agnostic module both backends already depend on, and the
 * union of the two field sets is what every backend sees. A field a backend does not model is inert
 * there rather than absent — the same contract `RenderHost.supportedOverrides` already advertises
 * to clients (PROTOCOL.md § 3, `capabilities.supportedOverrides`).
 *
 * **Backend-specific projections are extensions, not members.** `captureGutterPx()` on desktop and
 * `gutterHorizontalPx()` / `gutterVerticalPx()` on Android resolve these dp edges through their own
 * renderer's helpers, which live in modules this one cannot see. They are extension functions in
 * `:daemon:desktop` / `:daemon:android` respectively, so the data stays here and the projection
 * stays where the renderer it projects onto is.
 *
 * **Serialization is for boundaries, not for transport.** `RenderSpec` is a Kotlin object and rides
 * as one everywhere the sender and receiver share a classloader — which on desktop is the whole
 * path from `JsonRpcServer` to `RenderEngine`. It is `@Serializable` for the two places that
 * genuinely cannot pass an object:
 * 1. the Robolectric sandbox classloader crossing (`DaemonHostBridge`, whose package doc restricts
 *    crossings to `java.*` types), and
 * 2. the sandbox **worker process** hop (SANDBOX-POOL.md).
 *
 * Both use [encode] / [decode]. Neither invents a format.
 */
@Serializable
public data class RenderSpec(
  /** Discovery-time preview id this spec was resolved from, when it came from a manifest. */
  val previewId: String? = null,
  /** Optional data-product render mode, e.g. `a11y` or `theme` for fetch-driven re-renders. */
  val renderMode: String? = null,
  /** Fully-qualified name of the class containing the `@Preview` function. */
  val className: String,
  /** Method name of the `@Preview` function. */
  val functionName: String,
  /**
   * Preview flavour, mirroring the plugin's `PreviewKind` as a string.
   *
   * `null` / `"COMPOSE"` take the normal class-reflection path. Android routes `"TILE"`,
   * `"NOTIFICATION"` and `"GLANCE_APPWIDGET"` through their dedicated renderer helpers and renders
   * `"THEME_CATALOG"` / `"WEAR_THEME_CATALOG"` as synthetic specimens; desktop routes `"LOTTIE"`
   * past reflection entirely and inflates [assetPath] via Compottie.
   *
   * String-typed rather than an enum on purpose, for the same reason [PreviewKnobDto.type] is: a
   * newer plugin may name a kind this daemon cannot render, and an unknown name must degrade to the
   * normal path rather than fail the manifest parse.
   */
  val kind: String? = null,
  /** For `kind="LOTTIE"`: the classpath-relative Lottie asset path. Desktop only. */
  val assetPath: String? = null,
  /** Discovery-time display name, used to label synthetic catalog sheets. Android only. */
  val previewName: String? = null,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  /**
   * AS-parity wrap-content flags. When set, [widthPx]/[heightPx] are a *sandbox bound* rather than
   * a fixed frame: the composition root is measured with a relaxed (min = 0) constraint on the
   * wrapped axis and sized to the composable's intrinsic size, and the background paints on that
   * intrinsic box — so the captured layout/semantics tree (and the `compose/figma-svg` + wireframe
   * derived from it) reflect the preview's *natural* size.
   *
   * Without this a no-height preview rendered into the historical 320px frame and any content
   * taller than it reflowed to zero height: a `Column` hands each child the *remaining* height, so
   * once the budget is spent the overflow children measure to 0 lines — collapsed text fields and
   * buttons in the export. Off ⇒ the composition fills the frame.
   *
   * A wrap flag names an **axis of the frame**, so a rotation that swaps [widthPx] and [heightPx]
   * trades these too. Contrast the gutter edges below, which name a direction the *component* draws
   * in and survive rotation verbatim.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Per-render cleared-background toggle ("crisp outline"). When `true` the harness background is
   * forced transparent (overriding [showBackground] / [backgroundColor]) and
   * `LocalPreviewBackgroundCleared = true` is provided around the preview, so a composable that
   * paints its own opaque fill can drop it. Default preserves the discovery-time background.
   */
  val clearBackground: Boolean = false,
  /**
   * Per-render background mode for the `compose/figma-svg` export
   * (`PreviewOverrides.svgBackground`). Only that export reads it; it changes nothing about the
   * rendered PNG.
   *
   * Null means the caller said nothing and the daemon-wide `composeai.svg.background` default
   * applies, which is itself `NONE`: the export is background-free unless asked, because an
   * injected fill is an opaque shape spanning the canvas — hard to remove once baked, easy to add
   * back — so a *declared* [showBackground] is not enough to earn one.
   */
  val svgBackground: FigmaSvgBackgroundMode? = null,
  /**
   * Raw `@Preview(device = …)` string when known — `id:pixel_5`, `id:wearos_small_round`,
   * `spec:width=…,isRound=true`.
   *
   * Android's render body reads it to detect round Wear devices and apply the circular crop and
   * `round` resource qualifier. Desktop's render path is shape-agnostic (the circular crop is a
   * Robolectric-only mechanism) but carries the field so a spec resolved for one backend describes
   * the same render on the other.
   */
  val device: String? = null,
  /**
   * `@Preview(showSystemUi = …)`. On a phone-shape capture the desktop render body wraps the
   * composition in `:renderer-desktop`'s `SystemBarsFrame` — a synthetic status bar plus
   * gesture-nav pill simulating Android chrome on a non-Android backend, so one design reference
   * matches either candidate. Skipped for round/Wear [device]s; dark chrome follows [uiMode].
   */
  val showSystemUi: Boolean = false,
  /** Stem for the output PNG filename (`"preview-A"` → `<outputDir>/preview-A.png`). */
  val outputBaseName: String = "${className.substringAfterLast('.')}-$functionName",
  /**
   * BCP-47 locale tag override. Android threads it through the `b+lang+region` qualifier prefix
   * (Robolectric grammar); desktop scopes it through Compose UI's providable locale list when the
   * runtime exposes one.
   */
  val localeTag: String? = null,
  /**
   * Font scale multiplier. Null means "whatever the backend defaults to" (1.0). Android routes it
   * through `RuntimeEnvironment.setFontScale`; desktop threads it through `Density(density,
   * fontScale)`, applied to `ImageComposeScene` and re-provided as `LocalDensity` so a composition
   * reading `LocalDensity` directly sees the same value.
   */
  val fontScale: Float? = null,
  /**
   * Light/dark override. Android maps it to the `notnight` / `night` qualifier; desktop provides
   * `LocalSystemTheme`, which is what Compose Desktop's `isSystemInDarkTheme()` reads.
   */
  val uiMode: SpecUiMode? = null,
  /**
   * Portrait/landscape override. Android maps it to the `port` / `land` qualifier, outranking the
   * size-derived guess. Desktop has no display-rotation concept on `ImageComposeScene`, so it is
   * reduced to a `widthPx ↔ heightPx` swap applied **before** the spec reaches the engine — the
   * engine reads the resolved dimensions straight off this spec and never re-interprets this field,
   * so any swap must already be baked in by the caller.
   */
  val orientation: SpecOrientation? = null,
  /**
   * Paused-clock advance (ms) before capture. Null uses the backend default; values `<= 0` are
   * treated as null. Lets an animation-heavy preview request a longer settle window without editing
   * the render body. Android only — `ImageComposeScene` has no paused-clock concept.
   */
  val captureAdvanceMs: Long? = null,
  /**
   * Per-render `LocalInspectionMode` override. Null preserves preview semantics (`true`); held
   * interactive and recording sessions pass their own runtime-like `false`, and Android's a11y mode
   * forces `false` so accessibility semantics are populated.
   */
  val inspectionMode: Boolean? = null,
  /**
   * Per-render slot mode. When `true` the renderer provides `LocalSlotMode = true`, so a
   * `PreviewSlot` marker renders a labelled placeholder instead of its content. Desktop only.
   */
  val slotMode: Boolean? = null,
  /**
   * Per-call overrides bag, threaded through every registered `PreviewOverrideExtension`. The
   * renderer does not read individual fields off it — registered planners decide what to apply.
   *
   * Direct-applied overrides (size, density, locale, font scale) have typed fields above because
   * the renderer applies them itself; theme/wallpaper-style overrides ride along here so adding a
   * new override-driven feature stays a connector concern.
   */
  val overrides: PreviewOverrides? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)`, when the
   * source preview is annotated. Sourced from discovery's JSON, which reads it off the class-file
   * annotation tables — the upstream annotation is `AnnotationRetention.BINARY` and is invisible to
   * `Method.annotations` at runtime (issue #1440). Null falls back to the best-effort
   * runtime-reflection lookup for direct callers that bypass the manifest.
   */
  val wrapperClassName: String? = null,
  /**
   * FQN of the `PreviewParameterProvider` from `@PreviewParameter`, when discovery recorded one —
   * same annotation-retention provenance as [wrapperClassName].
   *
   * When set, the render body resolves the preview's `(<T>, Composer, int)` overload and invokes it
   * with one of the provider's values: the daemon renders one frame per preview id, so the bare id
   * binds value 0 unless [previewParameterRow] names another, and the per-value fan-out stays with
   * the standalone renderer. Null resolves the parameterless overload.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation default. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /**
   * Which `@PreviewParameter` row to bind — a fan-out suffix (`Dark`) or `PARAM_<idx>`. Set when
   * the inbound previewId was row-addressed as `<baseId>_<row>` (issue #3749). Null keeps the
   * "render value 0 under the bare id" contract.
   */
  val previewParameterRow: String? = null,
  /**
   * The **parameter knobs** this preview declares — its own defaulted value parameters, the
   * secondary override format beside `previewOverride*`.
   *
   * Empty for every preview that declares none, which is the overwhelming majority: discovery only
   * reports knobs when **every** value parameter has a default, so a preview cannot acquire one by
   * accident. A seed in `overrides.namedOverrides` naming one of these binds to the parameter's
   * position; a seed naming anything else is left to the `previewOverride*` controller.
   */
  val knobs: List<PreviewKnobDto> = emptyList(),
  /**
   * `@CaptureGutter` edges in **dp** (issue #4443), as four flat Ints. All-zero — the default — is
   * every preview that does not declare the annotation.
   *
   * Edges are start/end (leading/trailing, resolved against the render's layout direction), not
   * left/right. **Rotation leaves them alone, deliberately.** A wrap flag names an axis of the
   * frame and is traded when the frame rotates; a gutter edge is a statement about the component —
   * "my shadow falls this far below me" — and swapping the frame's width and height does not turn
   * the component upside down or move where its shadow lands. Rotating `bottom` onto `end` would
   * put the deep edge beside a component whose shadow still falls downward, cropping exactly where
   * it matters and padding an edge that needed nothing.
   */
  val gutterStartDp: Int = 0,
  val gutterTopDp: Int = 0,
  val gutterEndDp: Int = 0,
  val gutterBottomDp: Int = 0,
) {

  /** True when no edge carries a gutter — the render then keeps its pre-gutter path verbatim. */
  public fun hasCaptureGutter(): Boolean =
    gutterStartDp != 0 || gutterTopDp != 0 || gutterEndDp != 0 || gutterBottomDp != 0

  public enum class SpecUiMode {
    LIGHT,
    DARK,
  }

  public enum class SpecOrientation {
    PORTRAIT,
    LANDSCAPE,
  }

  public companion object {

    /**
     * The `@Preview(uiMode = …)` **Configuration bits** for a [SpecUiMode], for renderer entry
     * points that take the raw int rather than the enum.
     *
     * All three states are distinct and the distinction matters: `systemThemeFromUiMode` maps
     * `0x20` → dark, `0x10` → light and anything else → `Unknown`, and `Unknown` deliberately
     * leaves `isSystemInDarkTheme()` to the JVM's own theme probe. So collapsing [SpecUiMode.LIGHT]
     * to `0` does not mean "light", it means "ask the host" — and a request that explicitly asked
     * for light would render dark on a dark-themed machine.
     *
     * Easy to get wrong by copying the `if (DARK) 0x20 else 0` shape used for `SystemBarsFrame`,
     * which is correct there only because that consumer inspects the night-YES bit alone and so has
     * two states rather than three.
     */
    public fun uiModeBits(mode: SpecUiMode?): Int =
      when (mode) {
        SpecUiMode.DARK -> 0x20 // UI_MODE_NIGHT_YES
        SpecUiMode.LIGHT -> 0x10 // UI_MODE_NIGHT_NO
        null -> 0 // UI_MODE_NIGHT_UNDEFINED — defer to the host
      }

    /**
     * Lenient by construction: [ignoreUnknownKeys] so a spec written by a newer daemon still
     * decodes in an older sandbox worker, and defaults omitted so the common spec stays small on a
     * hop that happens once per render.
     */
    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /**
     * Encode for one of the two classloader/process boundaries documented on this class. Everywhere
     * else, pass the object.
     */
    public fun encode(spec: RenderSpec): String = json.encodeToString(serializer(), spec)

    /**
     * Inverse of [encode]. Throws on malformed input — a boundary that garbles is a bug, not a
     * degraded mode to render through.
     */
    public fun decode(encoded: String): RenderSpec = json.decodeFromString(serializer(), encoded)
  }
}
