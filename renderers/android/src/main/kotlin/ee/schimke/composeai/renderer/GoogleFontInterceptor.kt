package ee.schimke.composeai.renderer

import ee.schimke.composeai.fonts.google.GoogleFontCache
import ee.schimke.composeai.fonts.google.GoogleFontKey
import ee.schimke.composeai.fonts.google.GoogleFontSource
import java.io.File
import java.net.URLDecoder

/**
 * Cache and CSS-API helpers that underpin [ShadowFontsContractCompat].
 *
 * `Font(GoogleFont("Lato"), provider)` on a real device goes through
 * `androidx.core.provider.FontsContractCompat.requestFont` → GMS Fonts' ContentProvider. That
 * provider doesn't exist in the Robolectric sandbox: no `com.google.android.gms` package, no
 * registered content provider for `com.google.android.gms.fonts`. Compose's internal
 * `GoogleFontTypefaceLoader` swallows the failure into its async fallback and text silently renders
 * in the platform default (Roboto) — which is why consumers hit the "my downloadable fonts aren't
 * applied in screenshots" symptom when they try to capture previews that use their production
 * GoogleFont typography.
 *
 * The fix ships as a Robolectric shadow ([ShadowFontsContractCompat]) that intercepts `requestFont`
 * before the provider lookup even runs: parse the `FontRequest.query` (the same wire format
 * Compose's `GoogleFont.kt` builds), resolve a TTF from a local cache keyed by `(name, weight,
 * italic)`, and call the supplied callback synchronously with a [Typeface.createFromFile].
 *
 * The cache lives in a shared, machine-local directory (`$XDG_CACHE_HOME/composeai/fonts`, else
 * `~/.cache/composeai/fonts`) — a font keyed by `(family, weight, italic)` is identical across
 * projects, so it resolves once per machine and is reused by every render thereafter. The cache
 * directory is plumbed via the `composeai.fonts.cacheDir` system property by the plugin's
 * `composePreviewRender` `Test` task.
 *
 * Consumer code is unchanged: the same `Font(GoogleFont(...))` that runs on device renders under
 * Robolectric with zero `src/debug` fork, zero `testImplementation` opt-in, zero plugin
 * configuration.
 */
internal object GoogleFontCacheAccess {
  /**
   * The shadow reads this once at the first incoming `requestFont` call. Cached so repeated lookups
   * are allocation-free; re-read is never needed because the system property is pinned for the Test
   * task's lifetime.
   */
  private val cache: GoogleFontSource? by lazy {
    val cacheDirPath = System.getProperty("composeai.fonts.cacheDir") ?: return@lazy null
    val offline = System.getProperty("composeai.fonts.offline")?.lowercase() == "true"
    GoogleFontCache(File(cacheDirPath), offline = offline)
  }

  /**
   * Remembered variable-file lookups, keyed by `(family, italic)` and including misses.
   *
   * Keyed without the weight because one variable file serves every weight: keying per weight would
   * probe — and on a hit download — the same multi-megabyte file once per role a typography
   * declares. Misses are remembered for the opposite reason: a family that ships no variable file
   * costs three `METADATA.pb` probes to establish that, and a sheet that asks for seven roles of it
   * would otherwise pay them seven times.
   */
  private val variableFiles = HashMap<Pair<String, Boolean>, File?>()

  /**
   * The cached face for [key], preferring the family's variable file when [preferVariable].
   *
   * Falls back to the static instance when the variable file cannot be fetched, because a face in
   * the right family at the right weight is still much closer than the platform default — the
   * caller reports the dropped axes rather than the resolution failing outright.
   */
  fun load(key: GoogleFontKey, preferVariable: Boolean): ResolvedFace? {
    val source = cache ?: return null
    if (preferVariable) {
      variableFile(source, key)?.let {
        return ResolvedFace(it, variable = true)
      }
    }
    return source.load(key)?.let { ResolvedFace(it, variable = false) }
  }

  private fun variableFile(source: GoogleFontSource, key: GoogleFontKey): File? =
    synchronized(variableFiles) {
      val cacheKey = key.name to key.italic
      if (variableFiles.containsKey(cacheKey)) return variableFiles[cacheKey]
      val file = runCatching { source.loadVariable(key.name, key.italic) }.getOrNull()
      variableFiles[cacheKey] = file
      file
    }
}

/**
 * A resolved font file plus whether it still carries its `fvar` table.
 *
 * The flag is what lets the shadow tell "the axes will apply" from "the axes are about to be
 * silently dropped", without re-reading the file's table directory to find out.
 */
internal data class ResolvedFace(val file: File, val variable: Boolean)

/**
 * Read-only view of the downloadable-font cache for consumers that need the *file the render
 * actually drew with* rather than a fresh resolution.
 *
 * The `compose/figma-svg` export used to embed a downloadable face by re-fetching a WOFF2 from
 * Google by family name — a second network round-trip, independent of the TTF the render had
 * already resolved. It failed exactly where it mattered: a catalog render whose font cache was warm
 * (so the PNG is correct) but whose egress is closed, or which runs `composeai.fonts.offline`,
 * produced an SVG with no `@font-face` at all, so browsers fell back to sans-serif and every glyph
 * width, line break and ellipsis drifted from the PNG (issue #2906).
 *
 * Looking the already-resolved file up instead makes the embedded face the same bytes the raster
 * used, by construction, and removes the network from the export path entirely.
 */
object GoogleFontFiles {
  /**
   * Faces this process actually resolved, keyed the same way [cached] asks for them.
   *
   * The cache directory alone stopped being able to answer "which file did the render draw with?"
   * once an axes-bearing request could be served the family's variable file: that lands as
   * `<slug>-variable.ttf` and the weight-specific `<slug>-<weight>.ttf` is never downloaded, so a
   * directory lookup misses on a clean cache — and on a shared machine cache warmed by another
   * project it is worse than a miss, answering with a static instance the raster never drew.
   * Recording the resolution keeps [cached]'s contract true by construction rather than by
   * coincidence of filename.
   */
  private val resolved = java.util.concurrent.ConcurrentHashMap<String, File>()

  /**
   * Requests whose axes were filtered away, keyed like [resolved], valued with the settings asked
   * for.
   *
   * Separate from [FontResolutionDiagnostics.recordAxesDropped], which warns once per process and
   * writes to stderr. A warning is not a data product: it cannot be asserted against, and stderr is
   * not on the sheet a consumer reads. This is the queryable half, and it is deliberately NOT
   * deduplicated — the recorder asks per resolution.
   */
  private val axesDropped = java.util.concurrent.ConcurrentHashMap<String, String>()

  /** Publish the face [file] that a render just resolved for [key]. */
  internal fun record(key: GoogleFontKey, file: File) {
    resolved[key.fileName()] = file
  }

  /**
   * Publish that [key] was served a face with no `fvar` table although [variationSettings] named
   * axes, so `Paint.setFontVariationSettings` dropped every one of them.
   */
  internal fun recordAxesDropped(key: GoogleFontKey, variationSettings: String) {
    axesDropped[key.fileName()] = variationSettings
  }

  /**
   * The `font-variation-settings` `(family, weight, italic)` asked for and did not get, or null
   * when it asked for none or got them all.
   *
   * Reads only what this process recorded. There is no cache-directory fallback of the kind
   * [cached] keeps, and there should not be: whether axes were dropped is a property of a
   * RESOLUTION, not of a file on disk, so a face resolved before this process has no answer here
   * and null is the honest one.
   */
  fun droppedVariationSettings(family: String, weight: Int, italic: Boolean): String? =
    axesDropped[GoogleFontKey(family, weight, italic).fileName()]

  /**
   * The TTF `(family, weight, italic)` resolved to, or null when nothing has resolved it. Never
   * downloads — a miss means the render didn't draw with this face either, and the export should
   * degrade rather than fetch a face the raster never saw.
   *
   * Answers from [resolved] first, so an axes-bearing face embeds the variable file the raster
   * used; falls back to the cache directory for a face resolved before this process (or by a path
   * that does not record), which is the original behaviour.
   */
  fun cached(family: String, weight: Int, italic: Boolean): File? {
    val key = GoogleFontKey(family, weight, italic)
    resolved[key.fileName()]
      ?.takeIf { it.isFile && it.length() > 0 }
      ?.let {
        return it
      }
    val dir =
      System.getProperty("composeai.fonts.cacheDir")?.takeIf { it.isNotBlank() } ?: return null
    val file = File(dir, key.fileName())
    return file.takeIf { it.isFile && it.length() > 0 }
  }

  /** Forget the recorded resolutions. Tests only. */
  internal fun resetForTest() {
    resolved.clear()
    axesDropped.clear()
  }
}

/**
 * Render-time surfacing for downloadable-font resolution failures.
 *
 * A `Font(GoogleFont(...))` that can't be resolved — offline, no cache dir, a failed download, or a
 * family/weight Google serves no TTF for — is reported to Compose by [ShadowFontsContractCompat],
 * which then *silently* substitutes the platform default (Roboto). That's the "my branded fonts
 * aren't applied in screenshots" symptom: a preview that asks for Orbitron renders in Roboto with
 * no trace in the render log to say which face fell back or why. That output is *wrong* — a branded
 * sticker rendered in the wrong typeface — so by default the renderer treats a fallback as a
 * **fatal** per-preview error (the render loop drops the PNG and writes the usual `.error.json`).
 *
 * Opt out with `-Dcomposeai.fonts.failOnFallback=false` to downgrade a fallback to a non-fatal
 * warning: the PNG is kept and the fell-back faces are recorded in a `<png>.warnings.json` sidecar
 * instead. Use that for a deliberately-offline render, or a catalog that genuinely tolerates the
 * substitute face.
 *
 * Collection is per-preview: the render loop calls [beginPreview] before a render and
 * [drainPreview] after, so each preview only owns the fonts *it* asked for. A one-line stderr note
 * is emitted once per distinct `(family, weight, italic)` per process (a catalog render asks for
 * the same face hundreds of times) — matching the daemon's other self-diagnostics, surfaced in the
 * VS Code extension as `[daemon stderr] …`.
 */
// Public (not `internal`) so BOTH Android render paths can drive it: the gradle-plugin's
// `RobolectricRenderTest` (same module) and the CLI `bundle pack` / serve daemon's
// `:daemon:android` `RenderEngine`, which lives in a different module and would otherwise be unable
// to bracket a preview's font resolution — the gap that let a daemon-path render silently ship a
// Roboto-fallback sticker.
object FontResolutionDiagnostics {

  /** One downloadable face that couldn't be resolved and fell back to the platform default. */
  data class FontFallback(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val reason: String,
  )

  private val warnedThisProcess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val currentPreview = java.util.Collections.synchronizedList(mutableListOf<FontFallback>())

  /**
   * Whether an unresolved downloadable font fails its preview (default) or degrades to a warning.
   * Read per call so a test / daemon can flip `composeai.fonts.failOnFallback` between renders.
   */
  val failOnFallback: Boolean
    get() = System.getProperty("composeai.fonts.failOnFallback")?.toBooleanStrictOrNull() ?: true

  /** Reset the per-preview buffer. Called by the render loop before each preview's render. */
  fun beginPreview() {
    synchronized(currentPreview) { currentPreview.clear() }
  }

  /** Snapshot and clear the fonts that fell back during the just-finished preview render. */
  fun drainPreview(): List<FontFallback> =
    synchronized(currentPreview) {
      val snapshot = currentPreview.toList()
      currentPreview.clear()
      snapshot
    }

  /**
   * Record that [key] couldn't be resolved (so text will render in the platform fallback) for
   * [reason]. Adds it to the current preview's buffer and emits a de-duplicated stderr line.
   */
  internal fun recordFallback(key: GoogleFontKey, reason: String) {
    val fallback = FontFallback(key.name, key.weight, key.italic, reason)
    currentPreview.add(fallback)
    if (warnedThisProcess.add(key.fileName())) System.err.println(describe(fallback))
  }

  /**
   * Record that [key] resolved to a face with no axes although the request named some, so Compose's
   * `Paint.setFontVariationSettings` will drop them and the text renders at the instance's baked
   * axis values.
   *
   * A warning rather than a per-preview failure, unlike an unresolved family: the face IS the right
   * family at the right weight, so the render is a close approximation rather than the wrong
   * typeface, and a consumer whose egress reaches the CSS API but not the font repository would
   * otherwise lose every preview instead of some axis precision. It is deduplicated per `(family,
   * weight, italic)` for the process the same way [recordFallback] is — a sheet asks for the same
   * face once per role per preview.
   */
  internal fun recordAxesDropped(key: GoogleFontKey, variationSettings: String) {
    if (!warnedThisProcess.add("axes:${key.fileName()}")) return
    System.err.println(
      "ComposeAiFonts: \"${key.name}\" resolved to a static instance, so the requested font " +
        "variation settings ($variationSettings) are dropped and the text renders at that " +
        "instance's baked axis values. The family's variable file — the only one carrying an " +
        "`fvar` table — could not be fetched; allow egress to raw.githubusercontent.com or warm " +
        "the font cache to render the axes."
    )
  }

  /**
   * Best-effort explanation for *why* a resolution just failed, from the process's font config. The
   * shadow doesn't get a reason back from the null [GoogleFontCacheAccess.load] result, so we infer
   * it from the same knobs the cache reads: an unset cache dir, offline mode, else a live fetch
   * that failed (network, or Google serves no TTF for the family/weight).
   */
  fun currentFailureReason(): String {
    val cacheDir = System.getProperty("composeai.fonts.cacheDir")
    val offline = System.getProperty("composeai.fonts.offline")?.equals("true", ignoreCase = true)
    return when {
      cacheDir.isNullOrBlank() -> "no font cache configured (composeai.fonts.cacheDir unset)"
      offline == true ->
        "offline (composeai.fonts.offline=true) and the face was not already cached"
      else ->
        "download from Google Fonts failed (network error, or Google serves no TTF for this " +
          "family/weight)"
    }
  }

  /** The human-readable line for [fallback], used for stderr and the sidecar/exception message. */
  fun describe(fallback: FontFallback): String =
    "ComposeAiFonts: could not resolve downloadable font \"${fallback.family}\" " +
      "(weight=${fallback.weight}${if (fallback.italic) ", italic" else ""}) — ${fallback.reason}; " +
      "text renders in the platform fallback (Roboto)"

  /** Reset process-wide dedupe + the per-preview buffer. Tests only. */
  internal fun resetForTest() {
    warnedThisProcess.clear()
    synchronized(currentPreview) { currentPreview.clear() }
  }
}

/**
 * Thrown by the render loop when a preview asked for one or more downloadable fonts that couldn't
 * be resolved and `composeai.fonts.failOnFallback` is on (the default). Routes through the
 * renderer's existing per-preview `catch (Throwable)` so the failure lands in the `.error.json`
 * sidecar and the (wrong-typeface) PNG is dropped — the same surface a preview that threw uses.
 */
class FontFallbackException(fallbacks: List<FontResolutionDiagnostics.FontFallback>) :
  RuntimeException(
    buildString {
      append("Downloadable font(s) fell back to the platform default (Roboto), so this preview ")
      append("would render in the wrong typeface. Warm the font cache (composeai.fonts.cacheDir) ")
      append("or allow egress to fonts.googleapis.com + fonts.gstatic.com; set ")
      append("-Dcomposeai.fonts.failOnFallback=false to allow the fallback as a warning instead. ")
      append("Unresolved: ")
      append(
        fallbacks.joinToString("; ") {
          "${it.family} (weight=${it.weight}${if (it.italic) ", italic" else ""}) — ${it.reason}"
        }
      )
    }
  )

/**
 * Parses the `FontRequest.query` wire format that Compose's `GoogleFont.kt` builds into a
 * [GoogleFontKey].
 *
 * Expected shape (from `androidx.compose.ui.text.googlefonts.GoogleFont`):
 * ```
 * name=<urlencoded>&weight=<int>&width=<float>&italic=<0.0|1.0>&besteffort=<bool>
 * ```
 *
 * Any missing field falls back to sensible defaults so a slightly different query shape (older or
 * newer Compose, non-Compose callers) still resolves.
 */
internal fun parseFontRequestQuery(query: String?): GoogleFontKey? {
  query ?: return null
  val pairs =
    query
      .split('&')
      .mapNotNull { pair ->
        val idx = pair.indexOf('=').takeIf { i -> i > 0 } ?: return@mapNotNull null
        val key = pair.substring(0, idx)
        val raw = pair.substring(idx + 1)
        val value = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        key to value
      }
      .toMap()
  val name = pairs["name"]?.takeIf { it.isNotBlank() } ?: return null
  val weight = pairs["weight"]?.toIntOrNull() ?: 400
  val italic = pairs["italic"]?.toFloatOrNull()?.let { it >= 0.5f } ?: false
  return GoogleFontKey(name, weight, italic)
}

/**
 * The axes named in a `FontRequest.variationSettings` string, as `tag to value` pairs.
 *
 * The wire format is the CSS-like one `Paint.setFontVariationSettings` takes and Compose's
 * `FontVariation.Settings.toAndroidString` produces — `'wght' 750, 'GRAD' 0, 'opsz' 9` — joined
 * with either `,` or `, ` depending on which branch of that function ran, so the tags are matched
 * rather than the string being split. Anything unparseable reads as no axes, which routes the
 * caller to the existing static-instance behaviour rather than to an error.
 */
internal fun parseVariationAxes(variationSettings: String?): List<Pair<String, Float>> {
  if (variationSettings.isNullOrBlank()) return emptyList()
  return VARIATION_AXIS.findAll(variationSettings)
    .mapNotNull { match ->
      val value = match.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
      match.groupValues[1] to value
    }
    .toList()
}

private val VARIATION_AXIS = Regex("'([^']{1,4})'\\s*(-?[0-9]*\\.?[0-9]+)")

/**
 * Whether [variationSettings] asks for something the *static instance* for [key] cannot express.
 *
 * This is the question that decides which file the shadow serves, and it exists because the two
 * halves of a downloadable variable font travel on different channels. The family and weight go in
 * the `FontRequest` query and pick a file; the axes ride alongside in `variationSettings` and are
 * applied by Compose *after* the typeface comes back (`GoogleFontTypefaceLoader` →
 * `Paint.setFontVariationSettings`). That second step filters every requested axis against
 * `Typeface.isSupportedAxes` and, if nothing survives, returns false and leaves the typeface
 * exactly as it was — no error, no warning, the wrong face drawn.
 *
 * Nothing survives on a file from the CSS API, which bakes a static instance with no `fvar` table
 * at all (see `GoogleFontSource.loadVariable`). So a request that names axes needs the family's
 * pre-instancing file, and one that does not must keep resolving through the CSS API exactly as
 * before — that path is warm in every consumer's cache and its metrics are what existing renders
 * were captured against.
 *
 * "Cannot express" is therefore narrow on purpose:
 * * an axis other than `wght` / `ital` — `GRAD`, `ROND`, `opsz`, `slnt`, `wdth` — can only come
 *   from a variable file;
 * * a `wght` that differs from the query's weight is the same story, and it is not hypothetical:
 *   `createGoogleSansFlexTypography()` leaves every role's `Font` at the default `FontWeight.W400`
 *   and carries 520 / 650 / 750 in the axes, so all seven roles ask the CSS API for the *same* 400
 *   file and every weight distinction is dropped;
 * * a `wght` that matches, or an `ital` that matches, is precisely what the static instance already
 *   is, so it stays on the cheap path.
 */
internal fun requiresVariableFace(variationSettings: String?, key: GoogleFontKey): Boolean =
  parseVariationAxes(variationSettings).any { (axis, value) ->
    when (axis) {
      "wght" -> Math.round(value) != key.weight
      "ital" -> (value >= 0.5f) != key.italic
      else -> true
    }
  }
