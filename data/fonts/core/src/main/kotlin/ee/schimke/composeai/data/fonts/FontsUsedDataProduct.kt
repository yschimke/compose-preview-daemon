package ee.schimke.composeai.data.fonts

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/** Path-backed producer for `fonts/used`, written by backend render loops in default mode. */
object FontsUsedDataProducer {
  const val KIND: String = "fonts/used"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "fonts-used.json"

  val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  fun readPayload(
    rootDir: File,
    previewId: String,
    fileSystem: FileSystem = SystemFileSystem,
  ): FontsUsedPayload? {
    val file = rootDir.resolve(previewId).resolve(FILE)
    if (!file.exists()) return null
    return json.decodeFromString(
      FontsUsedPayload.serializer(),
      fileSystem.read(file.path.toPath()) { readUtf8() },
    )
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    payload: FontsUsedPayload,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(FontsUsedPayload.serializer(), payload))
    }
  }
}

@Serializable data class FontsUsedPayload(val fonts: List<FontUsedEntry>)

@Serializable
data class FontUsedEntry(
  val requestedFamily: String,
  val resolvedFamily: String,
  val weight: Int,
  val style: String,
  val sourceFile: String? = null,
  val fellBackFrom: List<String>? = null,
  val consumerNodeIds: List<String> = emptyList(),
  /**
   * The `font-variation-settings` this resolution asked for and did NOT get, or null when it asked
   * for none or got them all.
   *
   * The one fact that separated a correct render from the weight collapse of #114, and the only one
   * no other field here could express. `Paint.setFontVariationSettings` filters each requested axis
   * against `Typeface.isSupportedAxes` and, when nothing survives, returns false and leaves the
   * typeface exactly as it was — no error, no warning. Nothing survives on a face from the Google
   * Fonts CSS API, which bakes a static instance with no `fvar` table at all.
   *
   * Every other field stays clean through that: [resolvedFamily] is right, because the FAMILY
   * resolved and only the FACE did not, and [fellBackFrom] is empty, because nothing fell back. A
   * consumer asserting on those alone reports a pass over a sheet whose every type role collapsed
   * onto one weight — which is what happened, undetected, for the whole life of a catalog.
   *
   * Non-null is the failure. The value is the requested settings verbatim (e.g. `'wght' 750`)
   * rather than a boolean, because a report naming what was lost costs a reader no second lookup.
   */
  val droppedVariationSettings: String? = null,
)
