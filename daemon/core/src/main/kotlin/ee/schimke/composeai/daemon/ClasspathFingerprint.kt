package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.security.MessageDigest

/**
 * Tier-1 staleness detector (DESIGN.md § 8, PROTOCOL.md `classpathDirty`). Two hashes:
 * - a cheap content hash of a few build files, recomputed on every `fileChanged(kind=classpath)`;
 *   content, not mtime, so a touch without an edit does not respawn the daemon;
 * - an authoritative `(path, length, mtime)` hash of the resolved classpath, checked only when the
 *   cheap hash drifts, since most build-file edits do not change the classpath.
 *
 * Both file sets come from the launcher, keeping this module layout-agnostic. A missing file still
 * contributes its path, so creating or deleting one changes the hash. Not thread-safe; callers
 * serialise.
 */
public class ClasspathFingerprint(
  /** Small, stable set of build files (`*.gradle.kts`, `libs.versions.toml`, …) read in full. */
  public val cheapSignalFiles: List<File>,
  /** Resolved classpath; hashed by metadata only, as reading a few hundred jars is too slow. */
  public val classpathEntries: List<File>,
) {

  /** SHA-256 hex over the paths and bytes of [cheapSignalFiles], in order. */
  public fun cheapHash(): String {
    val md = MessageDigest.getInstance(SHA_256)
    for (file in cheapSignalFiles) {
      md.update(file.absolutePath.toByteArray(Charsets.UTF_8))
      md.update(0)
      if (file.isFile) {
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { stream ->
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        }
      }
      md.update(MARKER)
    }
    return md.digest().toHexString()
  }

  /**
   * SHA-256 hex over `(absolutePath, length, lastModified)` of [classpathEntries]; a missing entry
   * contributes `(path, 0, 0)`.
   */
  public fun classpathHash(): String {
    val md = MessageDigest.getInstance(SHA_256)
    for (file in classpathEntries) {
      md.update(file.absolutePath.toByteArray(Charsets.UTF_8))
      md.update(0)
      val length = if (file.exists()) file.length() else 0L
      val mtime = if (file.exists()) file.lastModified() else 0L
      md.update(longToBytes(length))
      md.update(longToBytes(mtime))
      md.update(MARKER)
    }
    return md.digest().toHexString()
  }

  /** Composite snapshot — the daemon stores this at startup as the reference. */
  public fun snapshot(): Snapshot =
    Snapshot(cheapHash = cheapHash(), classpathHash = classpathHash())

  /** Pair of hashes recorded at a single point in time. */
  public data class Snapshot(val cheapHash: String, val classpathHash: String)

  public companion object {
    /** Sysprop: `File.pathSeparator`-delimited cheap-signal files, set by the launcher. */
    public const val CHEAP_SIGNAL_FILES_PROP: String = DaemonProperties.Names.CHEAP_SIGNAL_FILES

    /** SHA-256 hash size — used by tests to assert hex-string length. */
    public const val SHA_256_HEX_LENGTH: Int = 64

    private const val SHA_256: String = "SHA-256"
    private const val BUFFER_SIZE: Int = 64 * 1024
    private val MARKER: ByteArray = byteArrayOf(0xFE.toByte(), 0xED.toByte())

    /**
     * Parses [CHEAP_SIGNAL_FILES_PROP]. Unset yields an empty list, which disables Tier-1 detection
     * (classpath changes are then only seen on respawn).
     */
    public fun parseCheapSignalFilesSysprop(
      value: String? = System.getProperty(CHEAP_SIGNAL_FILES_PROP)
    ): List<File> = DaemonProperties.cheapSignalFiles.parse(value).map { File(it) }

    private fun longToBytes(v: Long): ByteArray {
      val out = ByteArray(8)
      var x = v
      for (i in 7 downTo 0) {
        out[i] = (x and 0xFF).toByte()
        x = x ushr 8
      }
      return out
    }

    private fun ByteArray.toHexString(): String {
      val sb = StringBuilder(size * 2)
      for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
      }
      return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
  }
}
