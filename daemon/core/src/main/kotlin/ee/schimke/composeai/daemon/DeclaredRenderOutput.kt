package ee.schimke.composeai.daemon

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a one-shot render of a manifest preview should land when the discovery manifest
 * **declares** it (compose-ai-tools#6114).
 *
 * Discovery plans every output a preview produces as a `captures[]` entry, each with a
 * `renderOutput` relative to the manifest's own directory (`renders/<id>.png`, but also
 * `renders/<id>/composite.png`, a tagged stem, a Lottie subdirectory, …). The daemon used to ignore
 * that and always write `<outputDir>/<outputBaseName>.png`, so a client that wanted the declared
 * file had to copy the reported `pngPath` there. The router now asks this object for the declared
 * path and has the engine write it directly; `renderFinished.pngPath` then names it.
 *
 * **Only an unambiguous still is honoured.** The daemon's surface is one frame per preview id, and
 * the frame it produces is the plain still: settled when the preview asks for a settle, driven to
 * the end when it asks for `@ScrollingPreview(END)`, otherwise undriven. A capture describes that
 * frame only when it carries nothing else — no time advance, animation, interaction, focus, hover,
 * drag, tour step, … — and its output is a `.png`. When exactly one capture qualifies, its
 * `renderOutput` is the answer. When none does (every output is a GIF, a time step or an
 * interaction), or several do (`@ScrollingPreview(modes = [TOP, END])`, a two-timing
 * `@RoboComposePreviewOptions`), there is no honest single answer and the caller keeps the default
 * `<outputDir>/<outputBaseName>.png` it wrote before. The check is an allow-list, so a capture
 * field this daemon does not know about yet also falls back rather than being silently overwritten
 * by a frame that ignored it.
 */
public object DeclaredRenderOutput {

  /** Capture keys that do not change which frame is drawn, or that the one-shot still applies. */
  private val STILL_KEYS: Set<String> =
    setOf("renderOutput", "optional", "cost", "settle", "scroll")

  /** `@ScrollingPreview` modes that plan a plain PNG rather than a separate scroll artefact. */
  private val STATIC_SCROLL_MODES: Set<String> = setOf("TOP", "END")

  /**
   * The single `renderOutput` among [captures] that describes the daemon's still, or `null` when
   * none or several do. See the type's kdoc for the rule.
   */
  public fun stillOutput(captures: List<JsonObject>): String? =
    captures.mapNotNull { capture -> capture.stillRenderOutput() }.singleOrNull()

  /**
   * [renderOutput] for `@PreviewParameter` row [row]: `_<row>` inserted before the leaf's
   * extension, the way the fan-out renderer names a row (`renders/Foo.png` →
   * `renders/Foo_Dark.png`) and the way the router suffixes the row's `outputBaseName`. `null` or
   * blank [row] returns [renderOutput] unchanged.
   */
  public fun forRow(renderOutput: String, row: String?): String {
    if (row.isNullOrBlank()) return renderOutput
    val leafStart = renderOutput.lastIndexOf('/') + 1
    val dot = renderOutput.lastIndexOf('.')
    return if (dot > leafStart)
      renderOutput.substring(0, dot) + "_" + row + renderOutput.substring(dot)
    else renderOutput + "_" + row
  }

  /**
   * The absolute file [renderOutput] names: resolved against [manifestDir] when relative (it is
   * relative to the manifest's directory, not to the daemon's output directory or working
   * directory), taken as-is when already absolute.
   */
  public fun resolve(manifestDir: File, renderOutput: String): File =
    manifestDir.absoluteFile.resolve(renderOutput).normalize()

  private fun JsonObject.stillRenderOutput(): String? {
    val present = filterValues { it !is JsonNull }
    if (!STILL_KEYS.containsAll(present.keys)) return null
    val scrollMode = (present["scroll"] as? JsonObject)?.get("mode")?.primitiveContent()
    if (present.containsKey("scroll") && scrollMode?.uppercase() !in STATIC_SCROLL_MODES) {
      return null
    }
    val output = present["renderOutput"]?.primitiveContent()?.takeIf { it.isNotBlank() }
    return output?.takeIf { it.endsWith(".png", ignoreCase = true) }
  }

  private fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
}
