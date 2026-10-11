package ee.schimke.composeai.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Memoises [PreviewIndex.loadFromFile] by absolute path, last-modified time and size, so a render
 * engine that asks for the preview index several times per render reads and decodes `previews.json`
 * once rather than on every call.
 *
 * Both render engines look up per-preview metadata (`@SettledPreview`, `@ScrollingPreview`,
 * `@OverrideVariant(interaction = …)`) off the file named by [PreviewIndex.PREVIEWS_JSON_PATH_PROP]
 * — up to five lookups per render on desktop. Decoding the whole manifest each time cost a
 * multi-megabyte JSON parse per lookup on a large catalog (thousands of previews), which dominated
 * a batch of fast renders such as `bundle pack --with-semantics`.
 *
 * The cache keeps the engines' existing freshness contract: a `previews.json` rewritten by the
 * Gradle plugin while the daemon runs changes the file's modified time or size, so the next lookup
 * reloads it. A rewrite can keep both, though — same length, inside one tick of a coarse-timestamp
 * filesystem — so, like git's racily-clean index check, a parse is only reused once the file's
 * modified time is at least [RACY_WINDOW_NANOS] older than the moment it was read; a file modified
 * that recently is re-read on every lookup until it settles. A missing file is never cached, so its
 * "file does not exist" diagnostic still prints and a file that appears later is picked up.
 *
 * The returned [PreviewIndex] is shared between callers; treat it as read-only.
 */
public object PreviewIndexFileCache {

  private class Entry(
    val path: Path,
    val modifiedNanos: Long,
    val size: Long,
    val readAtNanos: Long,
    val index: PreviewIndex,
  ) {
    fun reusableFor(path: Path, modifiedNanos: Long, size: Long): Boolean =
      this.path == path &&
        this.modifiedNanos == modifiedNanos &&
        this.size == size &&
        modifiedNanos < readAtNanos - RACY_WINDOW_NANOS
  }

  /**
   * How much older than the read a file's modified time must be before its parse is trusted. Covers
   * filesystems that store modified times at one- or two-second granularity.
   */
  private val RACY_WINDOW_NANOS: Long = TimeUnit.SECONDS.toNanos(2)

  @Volatile private var entry: Entry? = null

  /**
   * The index at [PreviewIndex.PREVIEWS_JSON_PATH_PROP], or [PreviewIndex.empty] when the property
   * is unset (harness and fake-mode tests).
   */
  public fun fromSystemProperty(): PreviewIndex {
    val path = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    return if (path.isNullOrBlank()) PreviewIndex.empty() else load(Paths.get(path))
  }

  /**
   * [PreviewIndex.loadFromFile] for [path], reusing the previous parse while the file is unchanged.
   */
  public fun load(path: Path): PreviewIndex {
    val absolute = path.toAbsolutePath()
    val attributes =
      try {
        Files.readAttributes(absolute, BasicFileAttributes::class.java)
      } catch (_: IOException) {
        null
      } ?: return PreviewIndex.loadFromFile(absolute)
    val modifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
    val size = attributes.size()
    entry
      ?.takeIf { it.reusableFor(absolute, modifiedNanos, size) }
      ?.let {
        return it.index
      }
    synchronized(this) {
      entry
        ?.takeIf { it.reusableFor(absolute, modifiedNanos, size) }
        ?.let {
          return it.index
        }
      // Taken before the read: a write that lands during it leaves a modified time inside the
      // window, so the entry is not trusted until it is re-read.
      val readAtNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
      val index = PreviewIndex.loadFromFile(absolute)
      entry = Entry(absolute, modifiedNanos, size, readAtNanos, index)
      return index
    }
  }

  /** Drops the memoised index; the next [load] re-reads the file. For tests. */
  public fun clear() {
    entry = null
  }
}
