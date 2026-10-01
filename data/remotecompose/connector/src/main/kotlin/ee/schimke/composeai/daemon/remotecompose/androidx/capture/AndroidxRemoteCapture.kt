@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon.remotecompose.androidx.capture

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.RemoteDensityBehavior
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.daemon.RemoteCaptureDensity
import ee.schimke.composeai.daemon.remotecompose.RemoteComposeLinkageException
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkage
import ee.schimke.composeai.data.remotecompose.RemoteComposeLinkageReport

/**
 * The connector's one call into `remote-creation-compose`'s capture: [content] recorded as a Remote
 * Compose document. Kept in its own package for the same reason each player backend is —
 * [AndroidxRemoteCapture.requireLinked] checks this package's bytecode against the consumer's
 * creation library before the first capture, so a moved API is refused by name rather than failing
 * as a `NoSuchMethodError` inside the composition.
 *
 * [fontScale] is load-bearing: `RemoteCreationDisplayInfo`'s own `fontScale` defaults to `1f`, not
 * to the host's, so omitting it bakes `fontScale = 1` whatever the render spec asked for.
 */
internal suspend fun captureAndroidxRemoteDocument(
  context: Context,
  widthPixels: Int,
  heightPixels: Int,
  densityDpi: Int,
  fontScale: Float,
  captureDensity: RemoteCaptureDensity,
  profile: Profile,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray {
  // Capture in `Legacy` density behavior — the library's own default, and the only value that
  // describes what `remote-creation-compose` actually writes.
  //
  // `RemoteDensityBehavior.Legacy` is what both `RemoteCreationDisplayInfo` overloads default to,
  // and what `CoreDocument.DEFAULT_DENSITY_BEHAVIOR` is. Its kdoc — "Values are interpreted as
  // pixels" — accurately describes the document the creation library produces. Passing it is
  // therefore not a workaround; it undoes an override that asserted something untrue about our own
  // capture.
  //
  // The header is a single global flag, but the document it describes is MIXED, and the creation
  // library's choice does not follow the flag — a document captured under `Dp` and one captured
  // under `Pixels` are byte-identical. What it writes is fixed:
  //
  //   padding / spacedBy gaps / border width / clip radii   PIXELS (`RemoteDp.toPx()` at capture)
  //   heightIn / widthIn                                    DP     (relies on core to scale)
  //   height / width                                        EXACT_DP, self-describing
  //
  // And remote-core applies a DIFFERENT density predicate per op:
  //
  //   PaddingModifierOperation          updateVariables  scales when behavior == DP
  //   RoundedClipRectModifierOperation  paint            scales when behavior == DP
  //   DimensionInModifierOperation      updateVariables  scales when behavior != PIXELS
  //   DimensionModifierOperation        —                never; branches on EXACT / EXACT_DP
  //
  // So no non-default flag is right for every op, and `Legacy` is right for all of them *as the
  // library writes them*: padding and clip radii are left alone (already px) and `heightIn` is
  // scaled (it is dp). `Dp` — what this used to declare — asserts that padding is dp, so core
  // multiplied already-scaled pixels a second time. That is one bug, and it surfaced three times:
  // the outlined card's border (wear-m3-catalog#89), the compact button's height (#90), and
  // `RemoteButtonGroup`'s 4dp gap rendering at 8dp.
  //
  // What this costs: `Legacy` is safe and correct today, but it is the legacy track, and its own
  // kdoc concedes that "historically some layout properties might have behaved differently" — the
  // `!= PIXELS` predicate above is one of those. The forward-looking value is `Dp`, and it stays
  // unusable until the creation library honours it. Tracked in #4735.
  //
  // The comment this replaces rejected `Legacy` because "Material3 button/card fills and the
  // circular-progress indicator come out ~1/density too small". That description was wrong —
  // measured across the 57-sticker `remote-catalog` sheet, `Legacy` renders 56 byte-identical to
  // `Dp` and fixes the 57th. The symptom behind it was a player-side bug, not a serialisation one.
  //
  // `DOC_DENSITY_AT_GENERATION` is still stamped below: the alpha writer records DOC_WIDTH/HEIGHT
  // in px and the behavior but not the density value, and the player needs it to resolve the
  // dp-typed dimensions.
  //
  // None of the above changes under `RemoteCaptureDensity.HOST`. That setting picks what the
  // conversions are written *as* — a constant, or an expression over `FLOAT_DENSITY` — while
  // `densityBehavior` declares how the player should *interpret* what it finds. A `RemoteDp.toPx()`
  // that used to emit the number 24 emits an expression evaluating to 24 at the capture density;
  // it is still the pixel-typed payload `Legacy` describes, so the per-op predicates above still
  // land the same way. What does change is that the value is no longer knowable without running
  // the graph — see `RemoteDensitySelection` for who that matters to.
  val displayInfo =
    RemoteCreationDisplayInfo(
      widthPixels,
      heightPixels,
      densityDpi,
      fontScale,
      densityBehavior = RemoteDensityBehavior.Legacy,
    )
  // Which `RemoteDensity` the capture converts dp and sp through, and therefore whether
  // the document that comes out can answer a `?fontScale=` request at all.
  //
  //   FIXED  `from(displayInfo)` folds `density` and `fontScale` into literal
  //          `RemoteFloat` constants. Nothing downstream can move them.
  //   HOST   `RemoteDensity.Host` binds density to `RemoteContext.FLOAT_DENSITY` and font
  //          scale to `Rc.System.FONT_SIZE / 14 / density`; both resolve at paint time
  //          from the variables every player already writes.
  //
  // The property is Kotlin-cased `Host`, not `HOST` — it is a companion `val` on
  // `RemoteDensity`, not an enum constant, and the capital is the whole name.
  //
  // `RemoteDensitySelection` explains why this is a per-build setting defaulting to
  // FIXED rather than a straight fix: HOST also defers *density*, so it changes what a
  // captured `.rc` means about geometry, not only about text.
  val remoteDensity =
    when (captureDensity) {
      RemoteCaptureDensity.HOST -> RemoteDensity.Host
      RemoteCaptureDensity.FIXED -> RemoteDensity.from(displayInfo)
    }
  return captureSingleRemoteDocument(
      context,
      displayInfo,
      remoteDensity,
      LayoutDirection.Ltr,
      profile = profile,
      content = content,
    )
    .bytes
}

/** The linkage of [captureAndroidxRemoteDocument] against the consumer's creation library. */
internal object AndroidxRemoteCapture {
  val linkage: RemoteComposeLinkageReport by lazy {
    RemoteComposeLinkage.check(
      AndroidxRemoteCapture::class.java,
      listOf("ee.schimke.composeai.daemon.remotecompose.androidx.capture"),
    )
  }

  /** Throws [RemoteComposeLinkageException] when the capture cannot run on this classpath. */
  fun requireLinked() {
    if (!linkage.isLinked) throw RemoteComposeLinkageException("Remote Compose capture", linkage)
  }
}
