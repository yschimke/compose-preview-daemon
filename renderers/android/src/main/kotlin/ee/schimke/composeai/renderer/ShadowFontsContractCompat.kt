package ee.schimke.composeai.renderer

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for `androidx.core.provider.FontsContractCompat.requestFont` that
 * short-circuits the GMS Fonts provider lookup and delegates to [GoogleFontCacheAccess] instead.
 *
 * Why shadow here rather than wrap `FontFamily.Resolver`: in Compose 1.9+ `FontFamily.Resolver` is
 * a `sealed interface`, so external modules can't implement it. The TypefaceLoader path is the
 * lowest-level hook the public AndroidX surface exposes — `FontsContractCompat.requestFont` is what
 * Compose's internal `DefaultFontsContractCompatLoader` calls, so a shadow covers every GoogleFont
 * usage no matter which FontFamily the consumer builds.
 *
 * Registered globally via the generated `robolectric.properties` on the renderer's test classpath
 * (see [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask]).
 *
 * **Variable faces.** A request that names font variation axes is served the family's *variable*
 * file rather than the CSS API's baked static instance, because Compose applies those axes to
 * whatever Typeface this shadow hands back and `Paint.setFontVariationSettings` silently drops
 * every axis the face does not support. [requiresVariableFace] is where that decision is made and
 * why it is narrow.
 *
 * **Callback dispatch.** The real `requestFont` posts the callback through the supplied [Handler].
 * Under Robolectric's PAUSED looper mode the post never runs during `renderDefault`'s
 * `advanceTimeBy(32ms)` pump — that pump drives Compose's `MonotonicFrameClock`, not the main
 * Looper's pending-task queue — so the awaiting coroutine stays suspended, Compose's async font
 * loader never transitions, and Text keeps rendering in the platform fallback. We invoke the
 * callback synchronously instead: the caller is `GoogleFontTypefaceLoader.awaitLoad` inside a
 * coroutine, and `onTypefaceRetrieved` merely resumes that continuation — no re-entrance hazard,
 * and the Typeface lands before the first frame fires.
 */
@Implements(FontsContractCompat::class)
class ShadowFontsContractCompat {
  companion object {
    /**
     * Matches the 7-arg `FontsContractCompat.requestFont` overload that AndroidX exposes in
     * `core:1.7+`:
     * ```java
     * public static void requestFont(
     *     Context, FontRequest, int style, boolean isBlockingFetch,
     *     int timeout, Handler, FontRequestCallback)
     * ```
     *
     * This is the entrypoint Compose's `DefaultFontsContractCompatLoader` calls. Older / newer
     * 5-arg variants (deprecated but still on the class in some BOMs) fall through to the real
     * implementation — they aren't on the Compose path.
     */
    @JvmStatic
    @Implementation
    fun requestFont(
      @Suppress("UNUSED_PARAMETER") context: Context,
      request: FontRequest,
      @Suppress("UNUSED_PARAMETER") style: Int,
      @Suppress("UNUSED_PARAMETER") isBlockingFetch: Boolean,
      @Suppress("UNUSED_PARAMETER") timeout: Int,
      @Suppress("UNUSED_PARAMETER") handler: Handler,
      callback: FontsContractCompat.FontRequestCallback,
    ) {
      val key = parseFontRequestQuery(request.query)
      if (key == null) {
        System.err.println(
          "ComposeAiFonts: ignored a downloadable-font request with an unparseable query " +
            "(${request.query}); text renders in the platform fallback (Roboto)"
        )
        callback.onTypefaceRequestFailed(
          FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND
        )
        return
      }
      // Compose puts the axes in `FontRequest.variationSettings` rather than in the query, so this
      // is the only place the shadow can learn that the caller needs a face with an `fvar` table.
      // Read defensively: the field arrived in a later `androidx.core` than the 5-arg `requestFont`
      // overload above, and a consumer resolving an older one would otherwise take a
      // `NoSuchMethodError` here instead of simply keeping the static-instance behaviour.
      val variationSettings = runCatching { request.variationSettings }.getOrNull()
      val needsAxes = requiresVariableFace(variationSettings, key)
      val resolved: ResolvedFace? = GoogleFontCacheAccess.load(key, preferVariable = needsAxes)
      if (needsAxes && resolved != null && !resolved.variable) {
        FontResolutionDiagnostics.recordAxesDropped(key, variationSettings.orEmpty())
      }
      val file: File? = resolved?.file
      if (file == null) {
        // The cache couldn't produce a TTF (offline / no cache dir / failed download / no such
        // face). Record the fallback so the render loop can fail or warn on it — text would
        // otherwise silently render in Roboto.
        FontResolutionDiagnostics.recordFallback(
          key,
          FontResolutionDiagnostics.currentFailureReason(),
        )
        callback.onTypefaceRequestFailed(
          FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND
        )
        return
      }
      val typeface = runCatching { buildTypefaceFromFile(file, key.weight, key.italic) }.getOrNull()
      if (typeface == null) {
        FontResolutionDiagnostics.recordFallback(
          key,
          "the cached font file could not be decoded into a Typeface (corrupt or unreadable)",
        )
        callback.onTypefaceRequestFailed(
          FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR
        )
        return
      }
      // Publish the exact file the raster is about to draw with, so `compose/figma-svg` embeds
      // those bytes rather than guessing at `<slug>-<weight>.ttf` — which an axes-bearing request
      // never downloads (issue #2906 is what that divergence looks like from the outside).
      GoogleFontFiles.record(key, file)
      callback.onTypefaceRetrieved(typeface)
    }
  }
}

/**
 * Build a Typeface from [file], applying `wght` (and `ital`) axis settings so variable TTFs render
 * at the requested weight. For static TTFs the variation tags are simply ignored.
 *
 * `Typeface.Builder.setFontVariationSettings` is API 26+ (minSdk = 24 for the renderer AAR; the
 * Test task renders at the consumer's `compileSdk` — wired via `sdk=…` in the generated
 * `robolectric.properties`, see `GenerateRobolectricPropertiesTask`). Fall through to the plain
 * `createFromFile` on pre-O just in case a consumer overrides the SDK level.
 *
 * Shared between [ShadowFontsContractCompat] (the `Font(GoogleFont(...))` path) and
 * [PixelSystemFontAliases] (the `Font(DeviceFontFamilyName("roboto-flex"))` seeding path) — both
 * resolve a cached TTF and need identical axis handling.
 */
internal fun buildTypefaceFromFile(file: File, weight: Int, italic: Boolean): Typeface? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
    return Typeface.createFromFile(file)
  }
  val settings = buildString {
    append("'wght' ")
    append(weight)
    if (italic) append(", 'ital' 1")
  }
  return Typeface.Builder(file)
    .setFontVariationSettings(settings)
    // `setWeight` / `setItalic` set the Typeface's declared weight
    // metadata so Compose's post-resolve synthesis layer
    // (`AndroidFontResolveInterceptor`) doesn't think our
    // already-interpolated variable TTF still needs fake-bolding.
    .setWeight(weight)
    .setItalic(italic)
    .build() ?: Typeface.createFromFile(file)
}
