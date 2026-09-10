package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.devices.frameDpOverriddenBy
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.OverrideVariantSpec
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Test/harness-only [RobolectricHost] subclass that re-packs an inbound `previewId=<id>` payload
 * into a parseable `className=…;functionName=…` [RenderSpec] payload by looking the previewId up in
 * a JSON manifest provided by the harness.
 *
 * Mirrors `:daemon:desktop`'s
 * [PreviewManifestRouter][ee.schimke.composeai.daemon.PreviewManifestRouter] (the desktop version)
 * exactly — same JSON schema, same payload reshape rules, same activation sysprop. Lives in this
 * module's main source set so [DaemonMain] can mount it when spawned by `:daemon:harness`'s
 * `RealAndroidHarnessLauncher` (D-harness.v2). Without this routing the real Android daemon (driven
 * by `JsonRpcServer.handleRenderNow`, which only forwards `previewId=<id>` in the payload — see
 * `JsonRpcServer.kt` line ~352) would fall through to [RobolectricHost.SandboxRunner]'s
 * `renderStub` path, producing no PNG.
 *
 * **Activated only when** `-Dcomposeai.harness.previewsManifest=<path>` is set on the JVM —
 * production daemon launches don't pass it, so production behaviour is unchanged. **Pending** `B2.2
 * — IncrementalDiscovery` lands the daemon's own `previews.json` ownership and a typed `previewId`
 * field on `RenderRequest`, at which point this whole routing concept folds into `JsonRpcServer`
 * itself and this class goes away.
 *
 * **Why duplicated rather than promoted to `:daemon:core`.** Per DESIGN § 4 + § 7 the router
 * constructs target-specific `RenderSpec` payloads which are themselves duplicated per backend
 * (B1.4 decision). Promoting the router would force promoting `RenderSpec`, which would widen the
 * renderer-agnostic surface for a type slated for replacement. Two near-identical routers is the
 * documented trade-off.
 *
 * **Manifest schema** (`PreviewManifest`):
 * ```json
 * { "previews": [
 *     { "id": "red-square",
 *       "className": "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
 *       "functionName": "RedSquare",
 *       "widthPx": 64, "heightPx": 64, "density": 1.0 }
 * ] }
 * ```
 *
 * `id` and `className`/`functionName` are required; everything else falls back to [RenderSpec]'s
 * defaults.
 */
class PreviewManifestRouter(
  private val manifest: PreviewManifest,
  userClassloaderHolder: UserClassLoaderHolder? = null,
  sandboxCount: Int = 1,
  userClassloaderHolderFactory: ((sandboxClassLoader: ClassLoader) -> UserClassLoaderHolder)? =
    null,
  interactiveSessionListener: InteractiveSessionListener? = null,
) :
  RobolectricHost(
    userClassloaderHolder = userClassloaderHolder,
    sandboxCount = sandboxCount,
    userClassloaderHolderFactory = userClassloaderHolderFactory,
    previewSpecResolver = { previewId ->
      // Row-addressed ids (issue #3749) resolve here too, so `interactive/start` /
      // `recording/start` on `<baseId>_Dark` hold that row's composition rather than failing as an
      // unknown previewId (which surfaces as MethodNotFound and drops the panel to v1).
      val byId = manifest.previews.associateBy { it.id }
      byId[previewId]?.renderSpec()
        ?: PreviewRowAddress.split(previewId, isParameterized(byId))?.let { split ->
          byId.getValue(split.baseId).renderSpec().copy(previewParameterRow = split.row)
        }
    },
    interactiveSessionListener = interactiveSessionListener,
  ) {

  private val byId: Map<String, PreviewManifestEntry> = manifest.previews.associateBy { it.id }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    return super.submit(
      RenderRequest.Render(id = typed.id, target = routeTarget(typed.target)),
      timeoutMs,
    )
  }

  /**
   * Resolves an unresolved [RenderTarget.Preview] against the manifest into the [RenderTarget.Spec]
   * the parent host renders. Any already-resolved target passes through.
   *
   * The merge is [mergedWith], shared with the host's `renderNow` and held-session lanes. Three
   * things are this lane's own: the manifest lookup (including row addressing), the output stem,
   * and the explicit-`LIGHT` floor below.
   */
  internal fun routeTarget(target: RenderTarget): RenderTarget {
    val preview = target as? RenderTarget.Preview ?: return target
    val previewId = preview.previewId
    // Issue #3749 — an exact manifest hit is the ordinary case; a miss may still be a
    // `@PreviewParameter` **row** of a known base id (`<baseId>_Dark` / `<baseId>_PARAM_4`), which
    // discovery could not have enumerated. [rowAddressed] resolves that against the entries we
    // have.
    val addressed =
      rowAddressed(previewId)
        ?: error(
          "PreviewManifestRouter: no manifest entry for previewId='$previewId'. " +
            "Manifest knows: ${byId.keys}"
        )
    // An explicit inbound row wins over the one parsed out of the id, so a caller can render a row
    // of the bare base id without minting a row id for it.
    val row = preview.previewParameterRow?.takeIf { it.isNotBlank() } ?: addressed.row
    val base = addressed.entry.renderSpec()
    val merged = base.mergedWith(preview.overrides)
    val spec =
      merged.copy(
        // The *requested* id, not the base entry's — a row render is its own preview as far as
        // every downstream consumer keyed by previewId is concerned.
        previewId = previewId,
        renderMode = preview.renderMode?.takeIf { it.isNotBlank() },
        previewParameterRow = base.previewParameterProviderClassName?.let { row },
        // Unlike the desktop twin, an unset uiMode resolves to an explicit `LIGHT` rather than
        // staying null. Robolectric applies qualifiers incrementally (`setQualifiers("+…")` in
        // `RenderEngine.applyPreviewQualifiers`), so an absent one inherits the *previous* render's
        // `night` bit and the theme becomes render-order-dependent. An explicit `notnight` reset is
        // Studio's default for `uiMode = 0` previews. This is a fact about Robolectric, which is
        // why it lives here and not in the shared merge.
        uiMode = merged.uiMode ?: RenderSpec.SpecUiMode.LIGHT,
        // A row render writes its own artifact, keyed the way the fan-out renderer keys it
        // (`<stem>_<row>.png`), so rendering row 4 cannot clobber row 0's PNG or the data products
        // the file-backed registry resolves from it.
        outputBaseName = base.outputBaseName + (row?.let { "_$it" } ?: ""),
      )
    return RenderTarget.Spec(spec)
  }

  /**
   * Resolves [previewId] to the manifest entry that should render it, plus the `@PreviewParameter`
   * row it names (null for an ordinary preview).
   *
   * Exact hit first; on a miss, [PreviewRowAddress] splits `<baseId>_<row>` against the entries
   * that declare a provider. Returns null when neither resolves, which is the caller's "unknown
   * previewId" error. Wire-format twin of the desktop router's; keep both in lockstep.
   */
  internal fun rowAddressed(previewId: String): Addressed? {
    byId[previewId]?.let {
      return Addressed(it, null)
    }
    val split = PreviewRowAddress.split(previewId, isParameterized(byId)) ?: return null
    return Addressed(byId.getValue(split.baseId), split.row)
  }

  /** A previewId resolved against the manifest: the entry to render, and which row of it. */
  internal data class Addressed(val entry: PreviewManifestEntry, val row: String?)

  private fun parseInboundPayload(payload: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (entry in payload.split(';')) {
      val trimmed = entry.trim()
      if (trimmed.isEmpty()) continue
      val eq = trimmed.indexOf('=')
      if (eq <= 0) continue
      val k = trimmed.substring(0, eq).trim()
      val v = trimmed.substring(eq + 1).trim()
      if (v.isNotEmpty()) map[k] = v
    }
    return map
  }

  /** Layers a live request override over the synthetic preview's baked `@OverrideVariant` seed. */
  private fun overridesTokenFor(
    inboundToken: String?,
    baseOverrides: PreviewOverrides?,
  ): String? {
    if (baseOverrides == null) return inboundToken
    val inbound = inboundToken?.let {
      runCatching {
        json.decodeFromString(
          PreviewOverrides.serializer(),
          String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8),
        )
      }
        .getOrNull()
    }
    val merged = inbound.layeredOver(baseOverrides) ?: return inboundToken
    return java.util.Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        json.encodeToString(PreviewOverrides.serializer(), merged).toByteArray(Charsets.UTF_8)
      )
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads a [PreviewManifest] from [file]. Throws if the file does not exist or is malformed. */
    fun loadManifest(file: File, fileSystem: FileSystem = SystemFileSystem): PreviewManifest {
      require(file.isFile) { "PreviewManifestRouter: manifest '$file' does not exist" }
      return json.decodeFromString(
        PreviewManifest.serializer(),
        fileSystem.read(file.path.toPath()) { readUtf8() },
      )
    }

    /**
     * [PreviewRowAddress.split]'s predicate over a manifest: "is this base id an entry that
     * declares a `@PreviewParameter` provider?". Gating on the provider — not merely on the id
     * existing — is what stops an unrelated preview that happens to share a prefix from being read
     * as a row of its neighbour.
     */
    internal fun isParameterized(byId: Map<String, PreviewManifestEntry>): (String) -> Boolean = {
      byId[it]?.resolved()?.previewParameterProviderClassName?.isNotBlank() == true
    }
  }
}

internal fun PreviewManifestEntry.renderSpec(): RenderSpec {
  val resolved = resolved()
  return RenderSpec(
    previewId = id,
    className = className,
    functionName = functionName,
    widthPx = resolved.widthPx,
    heightPx = resolved.heightPx,
    density = resolved.density,
    showBackground = resolved.showBackground,
    backgroundColor = resolved.backgroundColor,
    device = resolved.device,
    outputBaseName = resolved.outputBaseName,
    overrides = resolved.overrides,
    kind = resolved.kind,
    previewName = resolved.name,
    wrapperClassName = resolved.wrapperClassName,
    previewParameterProviderClassName = resolved.previewParameterProviderClassName,
    previewParameterLimit = resolved.previewParameterLimit,
    wrapWidth = resolved.wrapWidth,
    wrapHeight = resolved.wrapHeight,
    // The manifest-declared night bit, so a held session composes the same theme the one-shot
    // render painted. Mirrors the desktop router's resolver.
    uiMode = if (uiModeIsNight(resolved.uiMode)) RenderSpec.SpecUiMode.DARK else null,
    // …and the other two axes a multi-annotation preview varies on, for the same reason.
    fontScale = resolved.fontScale,
    localeTag = resolved.locale,
    // `@CaptureGutter` (#4443). The desktop twin resolver carried this and Android's did not, so a
    // held session (interactive / recording / stream) of a guttered preview composed without the
    // gutter and the preview resized the moment the session took over — the exact failure the
    // desktop resolver's comment describes. The one-shot lane hid it by re-reading the gutter off
    // the manifest entry in the router; with one shared merge there is only the resolved spec, so
    // the gap became visible.
    gutterStartDp = resolved.captureGutter.start,
    gutterTopDp = resolved.captureGutter.top,
    gutterEndDp = resolved.captureGutter.end,
    gutterBottomDp = resolved.captureGutter.bottom,
  )
}

@Serializable data class PreviewManifest(val previews: List<PreviewManifestEntry>)

/**
 * On-the-wire entry the router reads from `previews.json`. Two shapes coexist:
 *
 * - **Flat** (used by harness tests in `:daemon:harness`): top-level `widthPx` / `heightPx` /
 *   `density` / `showBackground` / `device`. Hand-rolled JSON, easy to write inline.
 * - **Nested** (emitted by the gradle plugin's `DiscoverPreviewsTask`): the canonical
 *   [PreviewInfo][ee.schimke.composeai.plugin.PreviewInfo] schema with a `params` block carrying
 *   `widthDp` / `heightDp` / `density` / `device` / `showBackground` / `backgroundColor`. Pre-fix
 *   the daemon ignored the nested shape entirely (kotlinx.serialization with `ignoreUnknownKeys =
 *   true`), so production always rendered at the daemon's hardcoded defaults (320×320, density 2.0,
 *   no device, no wear-round crop) — diagnosed when the wear sample's circular crop went missing
 *   after the URL-ordering fix exposed otherwise-stale renders.
 *
 * [resolved] returns a [ResolvedRenderParams] that prefers flat fields when set and falls back to
 * the nested params, doing the dp→px conversion the plugin's schema requires.
 */
@Serializable
data class PreviewManifestEntry(
  val id: String,
  val className: String,
  val functionName: String,
  /**
   * Production manifests written by the gradle plugin nest these fields under `params`. Optional
   * because harness tests use the flat schema (see kdoc above); when null, the resolver consults
   * the flat fields below.
   */
  val params: PreviewParamsEntry? = null,
  /** Baked state seed for a synthetic `_VARIANT_` preview emitted by discovery. */
  @Serializable(with = CompatibleOverrideVariantSpecSerializer::class)
  val overrides: OverrideVariantSpec? = null,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val density: Float? = null,
  val showBackground: Boolean? = null,
  val backgroundColor: Long? = null,
  /**
   * Raw `@Preview(device = …)` string when the source preview has one set. Forwarded into the
   * `RenderSpec` payload so the render body applies the wear-round crop / `round` resource
   * qualifier for circular Wear devices.
   */
  val device: String? = null,
  val outputBaseName: String? = null,
  /** Flat-schema mirror of [PreviewParamsEntry.fontScale]. */
  val fontScale: Float? = null,
  /** Flat-schema mirror of [PreviewParamsEntry.locale]. */
  val locale: String? = null,
  /**
   * Flat-schema sibling of [PreviewParamsEntry.kind] — see kdoc there. Harness fixtures that use
   * the flat shape can supply this directly; the production gradle plugin writes it under
   * `params.kind` instead. [resolved] consults the flat field first, then the nested one.
   */
  val kind: String? = null,
  /**
   * Flat-schema mirror of `@Preview(uiMode = ...)` (Configuration bits). Optional; when null the
   * resolver consults the nested `params.uiMode`. Only the `UI_MODE_NIGHT_*` bits are consumed —
   * they select the `night`/`notnight` resource qualifier for the render. Mirrors the desktop
   * router.
   */
  val uiMode: Int? = null,
  /**
   * Flat-schema mirrors of [PreviewParamsEntry.previewParameterProviderClassName] /
   * [PreviewParamsEntry.previewParameterLimit] — see the kdoc there. The production gradle plugin
   * writes them under `params`; `:daemon:harness` scenario manifests use the flat shape, which is
   * what gives the `@PreviewParameter` render path (issue #3027) end-to-end cover in CI.
   */
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int? = null,
) {
  fun resolved(): ResolvedRenderParams {
    val p = params
    val device = device ?: p?.device
    val deviceDims = device?.takeIf { it.isNotBlank() }?.let { DeviceDimensions.resolve(it) }
    // The manifest's density wins (the plugin writes the device's own density there); the catalog's
    // is the fallback, so a bare `spec:…,dpi=160` entry resolves at 1.0 instead of the 2.0 default.
    val density = density ?: p?.density ?: deviceDims?.density ?: 2.0f
    val kind = kind ?: p?.kind
    val name = p?.name
    val uiMode = uiMode ?: p?.uiMode ?: 0
    // `1.0` is the annotation's own default, so it carries no information — treat it as unset and
    // let the render use its default rather than pinning a redundant qualifier.
    val fontScale = (fontScale ?: p?.fontScale)?.takeIf { it > 0f && it != 1.0f }
    val locale = (locale ?: p?.locale)?.takeIf { it.isNotBlank() }
    // A preview that declares an explicit size — or is pinned to a fixed frame by a device or a
    // non-Compose surface (tile / notification / Glance, whose render helpers consume the concrete
    // widthPx/heightPx) — keeps that frame. One that declares NONE renders wrap-content
    // (AS-parity):
    // the render measures the composable's intrinsic size within a generous sandbox bound and crops
    // to it, so the captured layout/semantics tree — and the figma-svg / wireframe derived from it
    // —
    // reflect the preview's natural size instead of the historical fixed 320² frame that clipped
    // wide content and reflowed tall content to zero height. Mirrors the desktop daemon's resolver
    // and the standalone renderer's wrap crop.
    val pinned = device != null || (kind != null && kind != "COMPOSE")
    // A device frame owns its geometry (#3113): the manifest's `widthDp`/`heightDp` are ignored and
    // the frame comes from the device catalog instead — the same source the inbound
    // `overrides.device` path in [PreviewManifestRouter.submit] resolves against, and the same one
    // the plugin's `resolveForRender` bakes with. Resolving it HERE is what makes a
    // manifest-declared `@Preview(device = …)` render at its device size: without it a device
    // preview has no explicit size AND doesn't wrap (it's pinned), so it fell through to the fixed
    // 320² default — a Wear preview rendered 320×320 instead of 384×384. The manifest's density is
    // kept (the plugin writes the device's own density there); only the dp extent comes from the
    // catalog, so an unknown device string still degrades to the catalog's documented default.
    // The dp→px conversion TRUNCATES, matching `RenderPreviewsTask`'s device-frame branch and the
    // inbound-`device` path in `submit` — a fractional product (id:pixel_5 = 393dp × 2.75 =
    // 1080.75) must land on the same 1080 the bake produces, or the live lane sits one pixel off
    // its own snapshot. Only the explicit-dp path below rounds half-up (#3113). Annotation dp still
    // displace the catalog when BOTH axes are set — [frameDpOverriddenBy] holds that precedence for
    // all four resolvers.
    val deviceFrameDp = deviceDims?.frameDpOverriddenBy(p?.widthDp, p?.heightDp)
    val explicitWidthPx =
      widthPx
        ?: deviceFrameDp?.let { (it.first * density).toInt().coerceAtLeast(1) }
        ?: p?.widthDp?.let { (it * density).roundHalfUpPx() }
    val explicitHeightPx =
      heightPx
        ?: deviceFrameDp?.let { (it.second * density).toInt().coerceAtLeast(1) }
        ?: p?.heightDp?.let { (it * density).roundHalfUpPx() }
    val wrapWidth = explicitWidthPx == null && !pinned
    val wrapHeight = explicitHeightPx == null && !pinned
    // The generous sandbox bound is only for a WRAPPING axis (measured + cropped). A pinned preview
    // with no explicit size (notification / tile / Glance — their render helpers consume the
    // concrete
    // px) keeps the historical fixed 320px frame, so this fix doesn't resize those surfaces.
    // A per-preview wrap sandbox narrows that bound WITHOUT fixing the axis — `wrapWidth` /
    // `wrapHeight` above are untouched, so the capture still crops to measured size. See
    // `discovery.PreviewParams.wrapSandboxWidthDp`.
    val sandboxWidthDp = p?.wrapSandboxWidthDp?.takeIf { it > 0 } ?: WRAP_SANDBOX_WIDTH_DP
    val sandboxHeightDp = p?.wrapSandboxHeightDp?.takeIf { it > 0 } ?: WRAP_SANDBOX_HEIGHT_DP
    val resolvedWidthPx =
      explicitWidthPx
        ?: if (wrapWidth) (sandboxWidthDp * density).roundHalfUpPx() else DEFAULT_FRAME_PX
    val resolvedHeightPx =
      explicitHeightPx
        ?: if (wrapHeight) (sandboxHeightDp * density).roundHalfUpPx() else DEFAULT_FRAME_PX
    val showBackground = showBackground ?: p?.showBackground ?: true
    val backgroundColor = backgroundColor ?: p?.backgroundColor ?: 0L
    val wrapperClassName = p?.wrapperClassName
    // Seeds AND the harness interaction, in one shared translation — see `toPreviewOverrides`.
    val bakedOverrides = overrides?.toPreviewOverrides()
    return ResolvedRenderParams(
      widthPx = resolvedWidthPx,
      heightPx = resolvedHeightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      device = device,
      outputBaseName = outputBaseName ?: id,
      kind = kind,
      name = name,
      wrapperClassName = wrapperClassName,
      overrides = bakedOverrides,
      captureGutter = p?.captureGutter ?: CaptureGutterDto(),
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      uiMode = uiMode,
      fontScale = fontScale,
      locale = locale,
      previewParameterProviderClassName =
        (previewParameterProviderClassName ?: p?.previewParameterProviderClassName)?.takeIf {
          it.isNotBlank()
        },
      previewParameterLimit = previewParameterLimit ?: p?.previewParameterLimit ?: Int.MAX_VALUE,
    )
  }

  companion object {
    /**
     * Sandbox bound (dp) for a no-size preview's wrap-content render — matches the standalone
     * renderer's 400×800 dp default and the desktop daemon's [WRAP_SANDBOX_WIDTH_DP]. The render
     * crops to the composable's intrinsic size within this bound; `fillMax*` / LazyColumn measure
     * against it.
     */
    const val WRAP_SANDBOX_WIDTH_DP: Int = 400
    const val WRAP_SANDBOX_HEIGHT_DP: Int = 800

    /**
     * Historical fixed frame (px) for a preview that declares no explicit size and doesn't wrap
     * (device / tile / notification / Glance surfaces). Matches [RenderSpec]'s 320px default —
     * preserving these surfaces' prior render exactly while wrap-content previews use the sandbox
     * bound above.
     */
    const val DEFAULT_FRAME_PX: Int = 320
  }
}

private fun Float.roundHalfUpPx(): Int = kotlin.math.floor(this + 0.5f).toInt().coerceAtLeast(1)

/**
 * Subset of the plugin's [PreviewParams][ee.schimke.composeai.plugin.PreviewParams] the daemon's
 * render path consumes. Any plugin-side fields the daemon doesn't yet care about (group, …) are
 * silently dropped via `ignoreUnknownKeys = true`. Add them here when the daemon grows the matching
 * render-path support.
 */
@Serializable
data class PreviewParamsEntry(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  /**
   * Bound a **wrapped** axis is measured against, replacing
   * [PreviewManifestEntry.Companion .WRAP_SANDBOX_WIDTH_DP] / `WRAP_SANDBOX_HEIGHT_DP`. Mirrors
   * `discovery.PreviewParams.wrapSandboxWidthDp`; unlike [widthDp] it does not fix the axis, so
   * [PreviewManifestEntry.resolved] still reports `wrapWidth = true` and the capture still crops to
   * measured size. This is what lets a Wear module's device-less previews measure against the 227dp
   * watch screen and still export as tight stickers.
   */
  val wrapSandboxWidthDp: Int? = null,
  /** See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
  val density: Float? = null,
  /**
   * `@Preview(fontScale = …)`. One of the three axes a multi-annotation preview varies on (with
   * `uiMode` and `device`), and the last one the daemon was still dropping: a large-font variant
   * rendered at 1.0 and produced data products — layout / semantics / figma-svg — byte-identical to
   * the default variant's (issue #2883). `1.0f` and null are interchangeable ("unset").
   */
  val fontScale: Float? = null,
  /**
   * `@Preview(locale = …)` as a BCP-47 tag. Same reasoning as [fontScale]: a locale variant that
   * doesn't reach the render composes the default locale's strings, so its captured trees repeat
   * the default variant's.
   */
  val locale: String? = null,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0L,
  /**
   * Raw Configuration bits from `@Preview(uiMode = ...)`. Only the `UI_MODE_NIGHT_*` bits are
   * consumed — they drive the `night`/`notnight` resource qualifier so a `_Dark` multipreview
   * variant actually renders dark (and its captured layout/semantics/figma-svg data products differ
   * from the `_Light` sibling's). Mirrors the desktop router.
   */
  val uiMode: Int = 0,
  /**
   * `"COMPOSE"` / `"TILE"` / `"NOTIFICATION"` / `"GLANCE_APPWIDGET"` — mirrors
   * `ee.schimke.composeai.discovery.PreviewKind`. Forwarded through the router so the daemon's
   * render path can dispatch tile / notification / Glance previews to their dedicated renderer
   * helpers instead of the Compose-method reflection path (which throws `NoSuchMethodException` on
   * non-composable entrypoints, and produces an unwrapped misrender for Glance entrypoints).
   */
  val kind: String? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Read at discovery time by `extractWrapperFqn` against the class-file
   * annotation tables — the upstream annotation has `AnnotationRetention.BINARY`, so
   * `Method.annotations` is empty for it at runtime. The daemon's render path consumes this field
   * to drive `InvokeWithOptionalWrapper`; without the manifest plumbing the daemon would see no
   * wrapper present and render the preview body directly (the original "Invalid applier" crash that
   * motivated `@PreviewWrapper` in the first place).
   */
  val wrapperClassName: String? = null,
  /**
   * FQN of the `PreviewParameterProvider` from `@PreviewParameter(SomeProvider::class)` when the
   * preview declares one, with its `limit`. Same story as [wrapperClassName]: the upstream
   * annotation has `AnnotationRetention.BINARY`, so discovery's class-file read is the only way the
   * render body can learn about it. Without this plumbing the daemon resolved the parameterless
   * overload of a parameterized preview and threw `NoSuchMethodException` out of
   * `getDeclaredComposableMethod` before composing anything (issue #3027).
   */
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /**
   * `@CaptureGutter` edges in dp, as `discovery.CaptureGutterDp` writes them into `previews.json`
   * (issue #4443). Null — every preview without the annotation — is no gutter. Forwarded onto the
   * render payload as a `captureGutter=` token so the live Robolectric lane grows the capture by
   * the same dp the batch lane does.
   */
  val captureGutter: CaptureGutterDto? = null,
)

/**
 * Output of [PreviewManifestEntry.resolved] — flat, fully-defaulted, ready to format into a
 * `RenderSpec` payload.
 */
data class ResolvedRenderParams(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val showBackground: Boolean,
  val backgroundColor: Long,
  val device: String?,
  val outputBaseName: String,
  val kind: String? = null,
  val name: String? = null,
  val wrapperClassName: String? = null,
  /** Baked state seed for a synthetic `_VARIANT_` preview. */
  val overrides: PreviewOverrides? = null,
  /**
   * AS-parity wrap-content flags (see [RenderSpec.wrapWidth]). Set when the preview declares no
   * explicit size / device / non-Compose surface, so [widthPx]/[heightPx] are a sandbox bound and
   * the render crops to the composable's intrinsic size.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  /**
   * Raw `@Preview(uiMode = ...)` Configuration bits; 0 = unset. See [PreviewParamsEntry.uiMode].
   */
  val uiMode: Int = 0,
  /** `@Preview(fontScale = ...)`, null when unset (or the redundant `1.0`). */
  val fontScale: Float? = null,
  /** `@Preview(locale = ...)` BCP-47 tag, null when unset. */
  val locale: String? = null,
  /**
   * `@PreviewParameter(SomeProvider::class, limit = N)` from discovery — see
   * [PreviewParamsEntry.previewParameterProviderClassName]. The render body loads the provider and
   * invokes the preview with its first value; null renders the parameterless overload.
   */
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /** `@CaptureGutter` edges in dp; all-zero (the default) is no gutter. See #4443. */
  val captureGutter: CaptureGutterDto = CaptureGutterDto(),
)
