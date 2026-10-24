package ee.schimke.composeai.daemon.compile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Memoized content hashes of compile-classpath entries, for the classpath-snapshot cache filename
 * [BtaCompileSession.compileIncremental] keys on.
 *
 * The hash itself is unchanged: SHA-256 over the file bytes (a JAR), or over every regular file's
 * relative path and bytes in stable order (a classes directory). What this adds is that an entry is
 * only re-read when its on-disk metadata stamp moves, so a save loop over an Android module's
 * ~400-900 entry classpath stats each entry instead of streaming every JAR on every compile.
 *
 * The stamp is what decides reuse:
 * - **A file** is stamped by its size and last-modified time. An in-place JAR rebuild changes at
 *   least one of them, so it re-hashes.
 * - **A directory** is stamped by a digest over every regular file's relative path, size and
 *   last-modified time — a metadata-only walk. Adding, removing, renaming or rewriting any class
 *   file moves the stamp, so a changed classes directory never reuses a stale hash. The directory's
 *   own mtime is deliberately not used: it does not change when a file in a subdirectory does.
 *
 * Entries are keyed by real path (symlinks resolved), holding only the latest stamp for each, so
 * the map is bounded by the number of distinct classpath entries a session has seen. Thread-safe.
 */
internal class ClasspathEntryHashes {

  private class Entry(val stamp: String, val sha256: String)

  private val entries = ConcurrentHashMap<Path, Entry>()

  /** Number of full content hashes computed — test and benchmark visibility only. */
  private val computed = AtomicInteger()

  internal val contentHashesComputed: Int
    get() = computed.get()

  fun sha256(path: Path): String {
    // The real path, not a lexical normalization: `link/../lib.jar` through a symlinked `link`
    // names the file the filesystem resolves, which is the one read and stamped below.
    val key = path.toRealPath()
    // Stamp before reading: if the entry moves while it is being hashed, the stored stamp is the
    // pre-read one, so the next call sees a mismatch and re-hashes rather than trusting a torn
    // read.
    val stamp = stampOf(key)
    entries[key]?.let { if (it.stamp == stamp) return it.sha256 }
    // Hash the path as the caller spelled it — a JAR's hash mixes in its file name — so the digest,
    // and therefore the snapshot filename, is byte-for-byte what it was before memoization.
    val sha = sha256OfPath(path)
    computed.incrementAndGet()
    entries[key] = Entry(stamp, sha)
    return sha
  }

  private fun stampOf(path: Path): String {
    if (!Files.isDirectory(path)) {
      val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
      return "f:${attrs.size()}:${attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
    }
    val md = MessageDigest.getInstance("SHA-256")
    var count = 0
    Files.walk(path).use { paths ->
      paths
        .filter { Files.isRegularFile(it) }
        .sorted(compareBy { path.relativize(it).toString() })
        .forEach { file ->
          val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
          md.update(path.relativize(file).toString().toByteArray(Charsets.UTF_8))
          md.update(0)
          md.update(
            "${attrs.size()}:${attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
              .toByteArray(Charsets.UTF_8)
          )
          md.update(0)
          count++
        }
    }
    return "d:$count:" + md.digest().toHex()
  }

  private fun sha256OfPath(path: Path): String {
    // BTA snapshots both JARs and classes directories. Hash directory entries in stable relative
    // path order so an extracted catalog `classes/` directory gets the same content-addressed
    // cache semantics as a JAR rebuilt in place.
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8 * 1024)
    fun hashFile(file: Path, relative: String) {
      md.update(relative.toByteArray(Charsets.UTF_8))
      md.update(0)
      Files.newInputStream(file).use { stream ->
        while (true) {
          val n = stream.read(buf)
          if (n <= 0) break
          md.update(buf, 0, n)
        }
      }
    }
    if (Files.isDirectory(path)) {
      Files.walk(path).use { paths ->
        paths
          .filter { Files.isRegularFile(it) }
          .sorted(compareBy { path.relativize(it).toString() })
          .forEach { hashFile(it, path.relativize(it).toString()) }
      }
    } else {
      hashFile(path, path.fileName.toString())
    }
    return md.digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
