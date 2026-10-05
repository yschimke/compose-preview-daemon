package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FigmaSvgBackgroundMode
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything a backend needs to produce one PNG: the preview to invoke, its frame, and the per-call
 * overrides. Shared by both backends; a field a backend does not model is inert there (as
 * advertised by `RenderHost.supportedOverrides`). Backend-specific projections, such as the gutter
 * in pixels, are extensions in the backend modules.
 *
 * `@Serializable` only for the sandbox classloader and worker-process crossings ([encode] /
 * [decode]); everywhere else it travels as an object.
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
   * The plugin's `PreviewKind` (`TILE`, `NOTIFICATION`, `LOTTIE`, …); `null` / `"COMPOSE"` is the
   * reflection path. A string so an unknown kind from a newer plugin degrades to that path rather
   * than failing the parse.
   */
  val kind: String? = null,
  /** For `kind="LOTTIE"`: the classpath-relative Lottie asset path. Desktop only. */
  val assetPath: String? = null,
  /** Discovery-time display name, used to label synthetic catalog sheets. Android only. */
  val previewName: String? = null,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  /**
   * Android Studio-style wrap-content: on a wrapped axis [widthPx]/[heightPx] is only an upper
   * bound and the root is sized to the content's intrinsic size, so content taller than the frame
   * is not squeezed to zero height. A rotation that swaps the frame swaps these too.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Forces a transparent background and provides `LocalPreviewBackgroundCleared = true` so a
   * composable can drop its own opaque fill.
   */
  val clearBackground: Boolean = false,
  /**
   * Background mode for the `compose/figma-svg` export only. Null uses `composeai.svg.background`
   * (default `NONE`: a baked-in fill is hard to remove, so [showBackground] alone does not add
   * one).
   */
  val svgBackground: FigmaSvgBackgroundMode? = null,
  /**
   * Raw `@Preview(device = …)` string (`id:wearos_small_round`, `spec:…,isRound=true`). Android
   * uses it for the round crop and `round` qualifier; desktop ignores it.
   */
  val device: String? = null,
  /**
   * `@Preview(showSystemUi = …)`. Desktop draws synthetic Android chrome (`SystemBarsFrame`) on
   * non-round devices.
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
  /** Font scale multiplier; null is the backend default (1.0). */
  val fontScale: Float? = null,
  /**
   * Light/dark override. Android maps it to the `notnight` / `night` qualifier; desktop provides
   * `LocalSystemTheme`, which is what Compose Desktop's `isSystemInDarkTheme()` reads.
   */
  val uiMode: SpecUiMode? = null,
  /**
   * Portrait/landscape override. Android maps it to the `port` / `land` qualifier. On desktop it is
   * a `widthPx ↔ heightPx` swap the caller must already have applied; the engine never reads it.
   */
  val orientation: SpecOrientation? = null,
  /** Paused-clock advance (ms) before capture; `<= 0` or null uses the default. Android only. */
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
   * Per-call overrides for the registered `PreviewOverrideExtension`s; the renderer itself only
   * applies the typed fields above.
   */
  val overrides: PreviewOverrides? = null,
  /**
   * FQN from `@PreviewWrapper`, read by discovery because the annotation has `BINARY` retention and
   * is invisible to runtime reflection. Null falls back to a best-effort reflective lookup.
   */
  val wrapperClassName: String? = null,
  /**
   * FQN from `@PreviewParameter`, from discovery like [wrapperClassName]. When set, one provider
   * value is bound: value 0 unless [previewParameterRow] names another.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation default. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /** `@PreviewParameter` row to bind (`Dark`, `PARAM_<idx>`); null binds value 0. */
  val previewParameterRow: String? = null,
  /**
   * The preview's own defaulted value parameters (reported only when every one has a default). A
   * `namedOverrides` seed naming one binds to that parameter; others go to `previewOverride*`.
   */
  val knobs: List<PreviewKnobDto> = emptyList(),
  /**
   * `@CaptureGutter` edges in dp; all zero without the annotation. Start/end follow layout
   * direction. Unlike the wrap flags these are *not* swapped on rotation: they describe where the
   * component draws (a shadow below it), not an axis of the frame.
   */
  val gutterStartDp: Int = 0,
  val gutterTopDp: Int = 0,
  val gutterEndDp: Int = 0,
  val gutterBottomDp: Int = 0,
  /**
   * Data-product kinds requested for this render; an on-demand post-capture processor
   * ([PostCaptureGate.ON_DEMAND_KINDS]) runs only when its kind is here. `null` runs them all.
   */
  val requestedDataKinds: Set<String>? = null,
) {

  /** True when any edge carries a gutter; otherwise the render takes the gutter-free path. */
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
     * `@Preview(uiMode = …)` Configuration bits for [mode]. LIGHT must be `0x10`, not `0`: `0`
     * means "ask the host", which renders dark on a dark-themed machine.
     */
    public fun uiModeBits(mode: SpecUiMode?): Int =
      when (mode) {
        SpecUiMode.DARK -> 0x20 // UI_MODE_NIGHT_YES
        SpecUiMode.LIGHT -> 0x10 // UI_MODE_NIGHT_NO
        null -> 0 // UI_MODE_NIGHT_UNDEFINED — defer to the host
      }

    /** Lenient so a newer daemon's spec decodes in an older worker; defaults omitted for size. */
    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /** For the classloader/process crossings only; elsewhere pass the object. */
    public fun encode(spec: RenderSpec): String = json.encodeToString(serializer(), spec)

    /** Inverse of [encode]. Throws on malformed input: a garbled boundary is a bug. */
    public fun decode(encoded: String): RenderSpec = json.decodeFromString(serializer(), encoded)
  }
}
