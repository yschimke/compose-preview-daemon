package ee.schimke.composeai.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * A value parsed from [file] that follows the file while the daemon runs: [current] parses it again
 * once its modified time or size differ from the last parse.
 *
 * The render daemons route previewIds through the `previews.json` the Gradle plugin rewrites on
 * every discovery, so a manifest held from the daemon's start refused every `@Preview` added since
 * (compose-ai-tools#6239). The freshness rule is [PreviewIndexFileCache]'s: a parse is reused only
 * once the file's modified time is at least [RACY_WINDOW_NANOS] older than the moment it was read,
 * so a same-size rewrite inside one tick of a coarse-timestamp filesystem is still picked up.
 *
 * A missing file keeps the last value. A file that does not parse — caught halfway through a
 * rewrite, say — keeps it too, reports the failure to [onReadFailed] and is tried again on the next
 * call. [initial] is the caller's own parse of [file]; the first [current] checks it against the
 * file once more, because the caller's read and this object's first look at the file are not one
 * atomic step.
 */
public class FollowedFile<T : Any>(
  private val file: File,
  initial: T,
  private val read: (File) -> T,
  private val onReadFailed: (Throwable) -> Unit = {},
) {

  private class Snapshot<T : Any>(val stamp: Stamp?, val readAtNanos: Long, val value: T) {
    fun reusableFor(stamp: Stamp): Boolean =
      this.stamp == stamp && stamp.modifiedNanos < readAtNanos - RACY_WINDOW_NANOS
  }

  private data class Stamp(val modifiedNanos: Long, val size: Long)

  @Volatile private var snapshot: Snapshot<T> = Snapshot(stamp = null, readAtNanos = 0L, initial)

  /** The value of the file as it is now, or the last one that parsed. */
  public fun current(): T {
    val stamp = stamp() ?: return snapshot.value
    snapshot
      .takeIf { it.reusableFor(stamp) }
      ?.let {
        return it.value
      }
    synchronized(this) {
      snapshot
        .takeIf { it.reusableFor(stamp) }
        ?.let {
          return it.value
        }
      // Taken before the read: a write that lands during it leaves a modified time inside the
      // window, so the snapshot is not trusted until it is re-read.
      val readAtNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
      val value =
        try {
          read(file)
        } catch (e: Exception) {
          onReadFailed(e)
          return snapshot.value
        }
      snapshot = Snapshot(stamp, readAtNanos, value)
      return value
    }
  }

  private fun stamp(): Stamp? =
    try {
      val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
      Stamp(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size())
    } catch (_: IOException) {
      null
    }

  private companion object {
    /**
     * [PreviewIndexFileCache]'s window: filesystems that store modified times to a second or two.
     */
    val RACY_WINDOW_NANOS: Long = TimeUnit.SECONDS.toNanos(2)
  }
}
