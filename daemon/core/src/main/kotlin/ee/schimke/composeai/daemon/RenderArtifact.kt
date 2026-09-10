package ee.schimke.composeai.daemon

import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * What a render produced — as a **value**, not as a filesystem path.
 *
 * This replaces `RenderResult.pngPath: String?`, a field whose name had stopped describing it three
 * ways over:
 * - it was not always a PNG. Three engine sites returned an SVG path through it, and the
 *   classloader forensic lane returned the dump path, because widening the result type looked more
 *   expensive than overloading a string.
 * - it was not always a path that existed. `JsonRpcServer`'s own KDoc has to warn that it is "a
 *   string field, not a postcondition" — a stub host returns a `daemon-stub-<id>.png` that is never
 *   written, and every reader has to test for the file before trusting it.
 * - it was not always usefully a path at all. `JsonRpcServer.readFrameBytes` re-read it off disk on
 *   **every** `renderFinished`, to hash the frame and to feed `stream/start`. So the live-frame
 *   path rendered a bitmap, encoded it to PNG, wrote it to disk, and immediately read it back — a
 *   round-trip that existed only because the seam between the engine and the wire could not carry
 *   bytes.
 *
 * **Why one flat type and not a `LocalFile | Bytes` sealed pair.** The sealed shape reads better
 * until you look at what a backend actually has at the moment it returns: the engines encode the
 * frame to PNG *and* write it, so they hold both, and a sum type would have made each of them pick
 * one and throw the other away. A reader wanting bytes off a `LocalFile` would then re-read the
 * file the producer still had in hand — which is the exact round-trip this type exists to remove.
 * Both fields are nullable because either half can genuinely be absent: a `stream/start` frame that
 * was never published to disk has no [path], and a backend that only wrote a file has no [bytes] to
 * hand over cheaply. Ask for what you want through [pathOrNull] and [bytesOrNull] and let the
 * artifact answer from whichever half it has.
 *
 * **It is also the piece a remote daemon needs.** An absolute local path cannot cross a machine
 * boundary; bytes can. Nothing in this module speaks to a remote yet, and this type does not add a
 * wire format for one — `renderFinished` still carries `pngPath`, projected via [pathOrNull]. What
 * changes is that the daemon's *internal* seam is a value, so adding that wire form later is a
 * change to one encoder rather than to every host.
 *
 * @property path the absolute path a backend wrote, or null when it wrote none. That a path is set
 *   still does not promise the file exists — a stub host names a `daemon-stub-<id>.png` it never
 *   writes — so [bytesOrNull] returns null rather than throwing when it is absent.
 * @property bytes the encoded artifact, when the backend already holds it. Serialized as a JSON
 *   number array, so it is only worth sending across the sandbox boundary for a frame that has no
 *   file behind it.
 * @property mediaType IANA media type — [PNG] for a render, [SVG] for a figma-svg export.
 */
@Serializable
public class RenderArtifact(
  public val path: String? = null,
  public val bytes: ByteArray? = null,
  public val mediaType: String = PNG,
) {
  /**
   * Note the hand-written [equals] / [hashCode] rather than a `data class`: `ByteArray` compares by
   * identity, so the generated ones would silently give two artifacts holding the same frame
   * reference inequality — which the render-result comparisons and the frame-dedup hash would then
   * get wrong in a way that looks like "every frame changed".
   */
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

    /**
     * Encode for the Robolectric sandbox classloader crossing — the same boundary, and the same
     * reason, as `RenderTarget.encode` going the other way: the sandbox's copy of this class is a
     * different `Class` object, so only a `java.*` value survives the trip.
     */
    public fun encode(artifact: RenderArtifact): String =
      json.encodeToString(serializer(), artifact)

    /** Inverse of [encode]. */
    public fun decode(encoded: String): RenderArtifact =
      json.decodeFromString(serializer(), encoded)
  }
}

/**
 * The on-disk path this artifact names, or null when it is bytes that were never written.
 *
 * This is the projection `renderFinished.pngPath` is built from, so a bytes-only artifact reaches a
 * path-only client as an absent `pngPath` rather than as a lie about a file that is not there.
 */
public fun RenderArtifact?.pathOrNull(): String? = this?.path

/**
 * The artifact's encoded bytes, or null when there are none to be had.
 *
 * Free when the backend already handed them over. Otherwise this reads [RenderArtifact.path], which
 * is what the whole daemon used to do unconditionally: the live-frame lane hashed and streamed
 * every render by re-reading the PNG the engine had written moments earlier. A backend that fills
 * in `bytes` skips that hop entirely.
 *
 * **Which backends fill it in, and why not all of them.** The desktop engine's `renderOnce` does:
 * it encodes the frame to a `ByteArray` and writes *that*, so it holds the bytes at the moment it
 * returns and handing them over costs nothing. The Android engine does not, and cannot cheaply — it
 * captures through Roborazzi's `captureRoboImage(file = …)`, which owns the encode and the write
 * and never surfaces the bytes; filling `bytes` there would mean reading back the very file this
 * function would have read anyway. The remaining sites (scroll strips, Lottie APNGs, the figma-svg
 * exports) are the same shape — a renderer helper is handed a `File`. So this read stays the
 * correct answer whenever the producer genuinely never held the bytes, not a legacy path to retire.
 *
 * Null rather than throwing when the file is missing, because a path on a result was never a
 * postcondition that one exists — a stub host names a `daemon-stub-<id>.png` it never writes.
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
