package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * What a render produced, as a value rather than a path. One flat type rather than a sum type
 * because engines usually hold both the encoded bytes and the file they wrote; read through
 * [pathOrNull] / [bytesOrNull] and the artifact answers from whichever it has.
 *
 * @property path the absolute path a backend wrote, if any. Not a promise that the file exists (a
 *   stub host names one it never writes).
 * @property bytes the encoded artifact, when the backend already holds it. Serialized as a JSON
 *   number array, so only worth sending across the sandbox for a frame with no file behind it.
 * @property mediaType [PNG] for a render, [SVG] for a figma-svg export.
 */
@Serializable
public class RenderArtifact(
  public val path: String? = null,
  public val bytes: ByteArray? = null,
  public val mediaType: String = PNG,
) {
  // Not a data class: generated equals/hashCode would compare ByteArray by identity.
  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is RenderArtifact &&
        path == other.path &&
        mediaType == other.mediaType &&
        (bytes?.let { other.bytes?.contentEquals(it) == true } ?: (other.bytes == null)))

  override fun hashCode(): Int {
    var result = path?.hashCode() ?: 0
    result = 31 * result + (bytes?.contentHashCode() ?: 0)
    return 31 * result + mediaType.hashCode()
  }

  override fun toString(): String =
    "RenderArtifact(path=$path, bytes=${bytes?.size ?: 0}, mediaType=$mediaType)"

  public companion object {
    public const val PNG: String = "image/png"
    public const val SVG: String = "image/svg+xml"

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /** For the sandbox classloader crossing, where only `java.*` values survive. */
    public fun encode(artifact: RenderArtifact): String =
      json.encodeToString(serializer(), artifact)

    /** Inverse of [encode]. */
    public fun decode(encoded: String): RenderArtifact =
      json.decodeFromString(serializer(), encoded)
  }
}

/** The on-disk path, or null for bytes never written; projected to `renderFinished.pngPath`. */
public fun RenderArtifact?.pathOrNull(): String? = this?.path

/**
 * The encoded bytes: free when the backend supplied them, otherwise read from [RenderArtifact.path]
 * (the Android engine writes through Roborazzi and never holds them). Null when neither is
 * available, including a path whose file was never written.
 */
public fun RenderArtifact?.bytesOrNull(fileSystem: FileSystem = SystemFileSystem): ByteArray? {
  val artifact = this ?: return null
  artifact.bytes?.let {
    return it
  }
  val path =
    try {
      artifact.path?.toPath()
    } catch (_: Throwable) {
      null
    } ?: return null
  if (!fileSystem.exists(path)) return null
  return try {
    fileSystem.read(path) { readByteArray() }
  } catch (_: Throwable) {
    null
  }
}
