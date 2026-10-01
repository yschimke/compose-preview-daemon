@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeClock
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeDocumentSource
import ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayers
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.AndroidxRemoteCapture
import ee.schimke.composeai.daemon.remotecompose.androidx.capture.captureAndroidxRemoteDocument
import ee.schimke.composeai.data.render.IrSidecarChannel
import kotlinx.coroutines.runBlocking

/**
 * `PreviewWrapperProvider` that bridges `renderNow.overrides.remoteCompose.namedValues` into the
 * player replaying the captured document. Applied as `@PreviewWrapper(
 * RemoteOverridablePreviewWrapper::class)` on a `@Preview`-annotated composable so the body stays
 * authoring-shaped (`Container { MyRemoteComponent() }`) — no `RemoteOverridablePreview(...)` /
 * `RemotePreview(...)` call inside the body. This is the canonical shape: preview authors swap one
 * annotation, not the function body.
 *
 * Hard-codes [RcPlatformProfiles.ANDROIDX] for symmetry with the local `RemotePreviewWrapper` the
 * sample shipped before this connector existed. Consumers that want a different profile subclass
 * and override [profile] — the tooling instantiates the wrapper via its no-arg ctor, so per-call
 * overrides aren't a thing the wrapper API supports today.
 *
 * See [RemoteOverridablePreview] for the underlying composable; the wrapper just forwards.
 */
open class RemoteOverridablePreviewWrapper : PreviewWrapperProvider {
  /** Remote-compose platform profile to capture the document against. Defaults to ANDROIDX. */
  protected open val profile: Profile = RcPlatformProfiles.ANDROIDX

  /**
   * Player used to replay the captured document — the build-wide
   * [RemoteComposePlayerSelection.configured], which is the vendored AndroidX `RcPlayer`
   * ([RemoteComposePlayerKind.EMBEDDED]) unless `-PcomposePreview.rcPlayer=view` says otherwise.
   * See [RemoteComposePlayerSelection] for what outranks what, and [RemoteViewPreviewWrapper] for
   * pinning the old lane on one preview regardless of the build setting.
   *
   * Read per instance rather than captured once, so a host that sets the property before the first
   * render is honoured; the resolution behind it is memoised per JVM.
   */
  protected open val player: RemoteComposePlayerKind
    get() = RemoteComposePlayerSelection.configured

  /**
   * Whether the capture folds density and font scale in as constants or defers them to the player's
   * own variables — the build-wide [RemoteDensitySelection.configured], which is
   * [RemoteCaptureDensity.FIXED] unless `-PcomposePreview.rcDensity=host` says otherwise.
   *
   * Read per instance for the same reason [player] is: a host that sets the property before the
   * first render is honoured, and the resolution behind it is memoised per JVM.
   */
  protected open val captureDensity: RemoteCaptureDensity
    get() = RemoteDensitySelection.configured

  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    RemoteOverridablePreview(
      profile = profile,
      player = player,
      captureDensity = captureDensity,
      content = content,
    )
  }
}

/**
 * Preview wrapper that replays the captured document with the embedded Compose Remote player.
 * Consumers must supply `:third-party-rc-embedded-player` (or its upstream equivalent) at runtime;
 * when it is absent this falls back to the standard View-backed player.
 *
 * Same lane as the plain [RemoteOverridablePreviewWrapper] now that embedded is the default. Kept
 * because it is a published annotation target and because saying which player draws is worth an
 * annotation even when it agrees with the default.
 */
class RemoteEmbeddedPreviewWrapper : RemoteOverridablePreviewWrapper() {
  override val player: RemoteComposePlayerKind = RemoteComposePlayerKind.EMBEDDED

  // The renderer resolves wrapper methods with getDeclaredMethod, so this must be declared on the
  // concrete wrapper rather than inherited from RemoteOverridablePreviewWrapper.
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    RemoteOverridablePreview(
      profile = profile,
      player = player,
      captureDensity = captureDensity,
      content = content,
    )
  }
}

/**
 * Preview wrapper that replays the captured document with the **View-backed** player
 * (`RemoteComposePlayer` in an `AndroidView`) — the lane that was the default before the embedded
 * player took it.
 *
 * It exists so that lane stays reachable by annotation rather than only by a `?rcPlayer=java`
 * query: a preview whose fidelity depends on the framework `Canvas` (glyph hinting is the usual
 * one) can pin itself here and keep baking through it.
 */
class RemoteViewPreviewWrapper : RemoteOverridablePreviewWrapper() {
  override val player: RemoteComposePlayerKind = RemoteComposePlayerKind.VIEW

  // Declared here for the same reflective-resolution reason as the sibling above.
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    RemoteOverridablePreview(
      profile = profile,
      player = player,
      captureDensity = captureDensity,
      content = content,
    )
  }
}

/**
 * Composable that captures [content] as a Remote Compose document and replays it through the
 * selected [player], seeding [RemoteComposeController.namedValues] over the authored defaults.
 * Prefer the annotation-only path through [RemoteOverridablePreviewWrapper]; this composable exists
 * for tooling/host code that needs to drive the bridge manually.
 *
 * Both halves bind to the consumer's Remote Compose libraries, and both are checked before they
 * run: the capture through `AndroidxRemoteCapture`, the player through
 * [ee.schimke.composeai.daemon.remotecompose.RemoteComposePlayers]. Either refuses with a
 * [ee.schimke.composeai.daemon.remotecompose.RemoteComposeLinkageException] naming what the
 * consumer's version lacks, rather than failing mid-composition.
 *
 * The "USER:" domain prefix that `rememberNamedRemoteString` (and the rest of the `rememberNamed*`
 * family) uses on the writer side is the same prefix the players' named-value overrides consume, so
 * a binding declared with `rememberNamedRemoteString("label", "Tap me")` is reachable by passing
 * the bare `"label"` (no manual prefix) into the override map. What each `RemoteNamedValue` kind
 * means to a player is fixed in one place for every player:
 * [ee.schimke.composeai.data.remotecompose.reseed].
 *
 * When the connector has no seeded overrides (the default in a vanilla `composePreviewRenderAll`
 * run) the loop is a no-op and the preview renders with each `rememberNamedRemote*`'s declared
 * default — same output as plain `RemotePreview`.
 */
@Composable
fun RemoteOverridablePreview(
  profile: Profile,
  modifier: Modifier = Modifier,
  player: RemoteComposePlayerKind = RemoteComposePlayerSelection.configured,
  captureDensity: RemoteCaptureDensity = RemoteDensitySelection.configured,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current

  val displayMetrics = context.resources.displayMetrics
  // The render's font scale, which `Density.fontScale` carries and `displayMetrics` does not.
  //
  // Load-bearing on BOTH lanes, for different reasons. `RemoteCreationDisplayInfo`'s `fontScale`
  // parameter defaults to `1f` — not to the host's — so the call below omitting it is what made a
  // `FIXED` capture bake `fontScale = 1` no matter what the render spec asked for. The Android
  // lane sets `Configuration.fontScale` per render spec and Compose surfaces it here, so reading
  // it is the difference between recording the requested scale and recording the number one.
  //
  // Under `HOST` the value does not reach the sp→px conversions (the player's `FONT_SIZE` does),
  // but it still describes the composition the capture ran in, so it is passed either way rather
  // than being made conditional on the lane.
  val fontScale = LocalDensity.current.fontScale
  // Refused by name, before the capture runs, when the consumer's creation library has moved
  // under the call below — see `AndroidxRemoteCapture`.
  AndroidxRemoteCapture.requireLinked()
  // Same capture pattern as upstream `RemotePreview` — `runBlocking` inside `remember` so the
  // document materialises once per (profile, content) pair without re-capturing across
  // recompositions. Collect the knobs the content declares during the capture (via the
  // `rememberOverridable*` wrappers) so they can be re-recorded on every render below.
  val captured =
    remember(profile, content) {
      RemoteComposeController.collectingDeclarations {
        runBlocking {
          val bytes =
            captureAndroidxRemoteDocument(
              context,
              displayMetrics.widthPixels,
              displayMetrics.heightPixels,
              displayMetrics.densityDpi,
              fontScale,
              captureDensity,
              profile,
              content,
            )
          // The `Dp` capture keeps size modifiers in dp but the alpha writer doesn't record the
          // generation density *value* (only DOC_WIDTH/HEIGHT in px and the density behavior).
          // Stamp
          // DOC_DENSITY_AT_GENERATION into the header so the rc-player can scale the dp modifiers
          // back to px; without it the fills/indicator render ~1/density too small. Best-effort and
          // idempotent — never fail the render over it.
          //
          // This is the one density LITERAL that survives a `HOST` capture, and it is meant to.
          // The header property describes the density the document was GENERATED at; it is what
          // the player scales the **dp-typed** dimension ops with (`heightIn` / `widthIn`, written
          // as dp by the creation library and left for core to resolve). Those ops never went
          // through `RemoteDensity` in the first place, so the setting does not reach them and the
          // stamp stays as necessary and as correct under `HOST` as under `FIXED`. What changes is
          // only that its neighbours — padding, gaps, clip radii — are now expressions over
          // `FLOAT_DENSITY` rather than numbers, so this value no longer describes the whole
          // document, just the dp-typed part of it. Read it as "generated at", not "renders at".
          val stamped = runCatching {
            stampGenerationDensity(bytes, displayMetrics.density)
          }
            .getOrDefault(bytes)
          // Offer the captured RC doc so a bundle can carry + replay it without this composable's
          // bytecode; the render harness drains it into the `renders/<stem>.rc` sidecar that
          // `BundlePreviewTask.resolvePreviewIr` packs. No-op outside a daemon/test render (no
          // current preview id). Best-effort — never fail the render over IR capture. See
          // IrSidecarChannel.
          runCatching { IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, stamped) }
          RemoteComposeDocumentSource(
            bytes,
            if (android.os.Build.FINGERPRINT == "robolectric") RemoteComposeClock.ROBOLECTRIC_UPTIME
            else RemoteComposeClock.SYSTEM,
          )
        }
      }
    }
  val document = captured.first
  val declaredKnobs = captured.second

  // Re-record the captured knobs on EVERY render. The memoized capture above records them only once
  // (during the outer composition phase); on the daemon path `RemoteComposeOverrideExtension`'s
  // render-start `clearDeclarations()` runs afterwards (from a `DisposableEffect`, the apply
  // phase),
  // so without this a `renderNow` / `data/fetch` render would surface no knobs. A `SideEffect`
  // lands
  // in the apply phase after that clear (Compose runs every `RememberObserver` before any
  // `SideEffect`). Idempotent, so the standalone Gradle path (which clears before rendering) is
  // unaffected.
  androidx.compose.runtime.SideEffect {
    declaredKnobs.forEach { RemoteComposeController.recordDeclaration(it) }
  }

  // Snapshot the seeded overrides at composition time. The map is from `RemoteComposeController`
  // (process-static state), seeded by `RemoteComposeOverrideExtension` from
  // `renderNow.overrides.remoteCompose`. Reading `.value` here makes recomposition observe the
  // controller's `MutableState`, so a follow-up render with a new override re-runs the bridge.
  val seededOverrides = RemoteComposeController.namedValues.value

  // Resolved (and linkage-checked) before anything draws: a player the consumer's libraries cannot
  // satisfy is refused by name here, never swapped for the other one.
  val backend = RemoteComposePlayers.forKind(player)

  // Recorded rather than left for a reader to derive from the wrapper — see
  // [RemoteComposeController.recordCapturePlayer].
  androidx.compose.runtime.SideEffect {
    RemoteComposeController.recordCapturePlayer(backend.capturePlayerName)
  }

  backend.Play(document = document, namedValues = seededOverrides, modifier = modifier)
}

// Remote Compose modern-header wire constants (big-endian). The header op is:
//   [op:1][major|MAGIC:4][minor:4][patch:4][propCount:4][ (tag:2)(len:2)(payload:len) ... ]
// where tag = (dataType << 10) | key, dataType FLOAT = 1, key 7 = DOC_DENSITY_AT_GENERATION.
private const val RC_HEADER_MAGIC = 0x048C0000.toInt()
private const val RC_PROP_DENSITY_AT_GENERATION = 7
private const val RC_DATATYPE_FLOAT = 1

/**
 * Insert `DOC_DENSITY_AT_GENERATION = density` into a captured RemoteDocument's header, so a player
 * can scale the dp-typed size modifiers back to generation pixels. Returns the input unchanged if
 * the density is unusable, the header isn't the modern property-table format, or the property is
 * already present (idempotent). Pure byte-surgery on the header — the wire format is exercised by
 * the rc-player parity harness.
 */
internal fun stampGenerationDensity(bytes: ByteArray, density: Float): ByteArray {
  if (!density.isFinite() || density <= 0f) return bytes
  if (bytes.size < 17) return bytes
  fun beInt(o: Int): Int =
    ((bytes[o].toInt() and 0xFF) shl 24) or
      ((bytes[o + 1].toInt() and 0xFF) shl 16) or
      ((bytes[o + 2].toInt() and 0xFF) shl 8) or
      (bytes[o + 3].toInt() and 0xFF)
  fun beShort(o: Int): Int = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)

  if ((beInt(1) and 0xFFFF0000.toInt()) != RC_HEADER_MAGIC) return bytes
  val propCount = beInt(13)
  if (propCount < 0) return bytes
  // Walk the existing property table; bail (leave unchanged) if density is already recorded or the
  // table is malformed.
  var off = 17
  repeat(propCount) {
    if (off + 4 > bytes.size) return bytes
    if ((beShort(off) and 0x3FF) == RC_PROP_DENSITY_AT_GENERATION) return bytes
    off += 4 + beShort(off + 2)
  }

  val tag = (RC_DATATYPE_FLOAT shl 10) or RC_PROP_DENSITY_AT_GENERATION
  val densBits = java.lang.Float.floatToIntBits(density)
  val out = ByteArray(bytes.size + 8)
  System.arraycopy(bytes, 0, out, 0, 17)
  val newCount = propCount + 1
  out[13] = (newCount ushr 24).toByte()
  out[14] = (newCount ushr 16).toByte()
  out[15] = (newCount ushr 8).toByte()
  out[16] = newCount.toByte()
  out[17] = (tag ushr 8).toByte()
  out[18] = tag.toByte()
  out[19] = 0
  out[20] = 4
  out[21] = (densBits ushr 24).toByte()
  out[22] = (densBits ushr 16).toByte()
  out[23] = (densBits ushr 8).toByte()
  out[24] = densBits.toByte()
  System.arraycopy(bytes, 17, out, 25, bytes.size - 17)
  return out
}
