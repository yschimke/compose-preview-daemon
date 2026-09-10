package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * Merge per-call [PreviewOverrides] onto a discovery-time [RenderSpec] — the **one** implementation
 * every lane uses.
 *
 * There were six. Each backend had three: the `renderNow` resolve
 * (`DesktopHost.specFromPreviewTarget` / `RobolectricHost.reshapeRenderTarget`), the held-session
 * resolve (`applyOverrides`, for `interactive/start`, `stream/start` and `recording/start`), and
 * the harness manifest router. They assigned overlapping but *unequal* subsets of the same fields —
 * 34/16/18 on desktop against 32/20/16 on Android — and PROTOCOL.md § 5 openly listed them,
 * promising they "cannot drift" because they shared one helper for one field (`orientedPx`).
 *
 * They had drifted. Desktop's held-session lane cleared a wrap flag when a `device` override pinned
 * that axis; Android's did not, so an interactive session of a no-size preview forced onto a device
 * kept wrap-contents and measured the wrong axis. Neither held lane carried `slotMode` or
 * `captureAdvanceMs`, so a viewer's slot-mode toggle reached a one-shot render but not a live one.
 * Every one of those is the same shape of bug: a field the protocol accepts, the merge applies, and
 * one lane silently drops.
 *
 * This is where that stops being possible. A lane now calls this and then `copy(…)`s **only** what
 * is genuinely its own — the output stem, the preview id, the render mode. There is no subset left
 * to get wrong.
 *
 * **Why it can live here.** `PreviewOverrideBaseSpec`'s KDoc explained itself as an adapter because
 * "concrete hosts keep backend-local `RenderSpec` types". That stopped being true when `RenderSpec`
 * moved into this module, so the merge is expressed directly on the spec — [mergePreviewOverrides]
 * now takes one, and the DTO and its `withCarriedOverrides` field-copy are gone.
 *
 * What this deliberately does **not** do is anything lane-specific:
 * - it never sets [RenderSpec.outputBaseName] — a recording names it after the session, a row
 *   render after the row, the Android bundle lane after the preview id;
 * - it never sets [RenderSpec.previewId] or [RenderSpec.renderMode] — those come off the request,
 *   not off the overrides;
 * - it applies no backend policy, such as Android's "an unset `uiMode` resolves to an explicit
 *   `LIGHT`" floor (Robolectric applies qualifiers incrementally, so an absent one inherits the
 *   previous render's `night` bit — a fact about Robolectric, not about overrides).
 */
public fun RenderSpec.mergedWith(overrides: PreviewOverrides?): RenderSpec {
  val merged = mergePreviewOverrides(base = this, overrides = overrides)

  // An override that pins an axis — explicit pixels, or a device that pins both — clears that
  // axis's wrap flag: the frame is no longer free to size itself there, and a stale wrap intent
  // makes the measure-and-crop pass size an axis the caller just fixed.
  //
  // This was desktop-only. Android's held lane kept the flag, so an interactive session of a
  // no-size preview forced onto a device wrapped an axis the device had pinned.
  val deviceSupplied = overrides?.device?.takeIf { it.isNotBlank() } != null
  val pinnedWrapWidth = wrapWidth && overrides?.widthPx == null && !deviceSupplied
  val pinnedWrapHeight = wrapHeight && overrides?.heightPx == null && !deviceSupplied

  return copy(
    widthPx = merged.widthPx,
    heightPx = merged.heightPx,
    // The wrap flags name an *axis*, so a rotated frame trades them (#3552 review). The decision
    // cannot be re-derived from the returned dimensions — they are already rotated, so asking "does
    // the orientation contradict this?" a second time always says no, which is exactly how the
    // hand-rolled copies of this went stale. [MergedPreviewOverrides.rotated] is that answer,
    // computed once, at the swap.
    //
    // The `@CaptureGutter` edges ride through untouched, deliberately (#4443): a wrap flag names an
    // axis of the frame, a gutter edge names a direction the component draws in, and swapping the
    // frame's width and height does not turn the component over or move where its shadow falls.
    wrapWidth = if (merged.rotated) pinnedWrapHeight else pinnedWrapWidth,
    wrapHeight = if (merged.rotated) pinnedWrapWidth else pinnedWrapHeight,
    density = merged.density,
    device = merged.device,
    localeTag = merged.localeTag,
    fontScale = merged.fontScale,
    uiMode =
      when (merged.uiMode) {
        UiMode.LIGHT -> RenderSpec.SpecUiMode.LIGHT
        UiMode.DARK -> RenderSpec.SpecUiMode.DARK
        null -> null
      },
    orientation =
      when (merged.orientation) {
        Orientation.PORTRAIT -> RenderSpec.SpecOrientation.PORTRAIT
        Orientation.LANDSCAPE -> RenderSpec.SpecOrientation.LANDSCAPE
        null -> null
      },
    inspectionMode = merged.inspectionMode,
    // The knobs below are not `mergePreviewOverrides` display-geometry fields, so they are carried
    // straight off the bag. Null preserves the discovery-time value in every case.
    //
    // `clearBackground` and `svgBackground` were carried by all four lanes already. `slotMode` and
    // `captureAdvanceMs` were carried by the one-shot lanes only, so a viewer toggling slot mode
    // saw
    // it apply to a still render and not to the live session beside it.
    clearBackground = overrides?.clearBackground ?: clearBackground,
    svgBackground = overrides?.svgBackground ?: svgBackground,
    slotMode = overrides?.slotMode ?: slotMode,
    captureAdvanceMs = overrides?.captureAdvanceMs ?: captureAdvanceMs,
    // `toExtensionOverrides()` drops `themeProvider` and the size bounds because they are
    // renderer-read rather than extension-consumed, but the renderer reads `spec.overrides`
    // directly — so without putting them back a live App-theme change would keep the default
    // wrapper, and a live size-mode change would drop to unbounded wrap.
    overrides =
      merged
        .toExtensionOverrides()
        .withThemeProvider(overrides?.themeProvider ?: this.overrides?.themeProvider)
        .withSizeBounds(overrides ?: this.overrides),
  )
}
