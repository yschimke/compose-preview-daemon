package ee.schimke.composeai.daemon

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * `-D` arguments for a launched JVM, kept under the OS limits on one `execve` argument and on a
 * whole command line. Shared by the daemon client, which launches the daemon, and the daemon's
 * sandbox pool, which launches its workers with the same `-D` set.
 */
public object SystemPropertyArgs {
  /**
   * Linux refuses any single `execve` argument over `MAX_ARG_STRLEN` (32 pages, 128 KiB) with
   * `E2BIG`, whatever `ARG_MAX` allows. `composeai.daemon.userClassDirs` on a target with ~1,500
   * runtime jars is ~290 KiB, so past these sizes every `-D` goes through an argfile instead.
   */
  public const val MAX_INLINE_PROPERTY: Int = 32 * 1024
  public const val MAX_INLINE_PROPERTIES: Int = 96 * 1024

  /**
   * Windows passes one command line to `CreateProcess`, at most 32,767 characters in all. The
   * classpath is usually an argfile already, so the properties share that budget with the launcher,
   * JVM args and main class.
   */
  public const val MAX_WINDOWS_INLINE_PROPERTIES: Int = 16 * 1024

  /**
   * Conservative age threshold for sweeping legacy (pre-PID-tagged) `composeai-cp*.args` /
   * `composeai-props*.args` files. The `java` launcher reads an `@argfile` once at process startup,
   * so any non-PID file older than ten minutes is guaranteed stale.
   */
  public val DEFAULT_LEGACY_ARGFILE_MAX_AGE_MS: Long = TimeUnit.MINUTES.toMillis(10)

  private const val PROPS_PREFIX = "composeai-props"
  private const val CP_PREFIX = "composeai-cp"
  private const val LAUNCH_DIR_PREFIX = "composeai-launch-pid"
  private val PID_ARGFILE_REGEX = Regex("""^composeai-(?:props|cp)-pid(\d+)-.*\.args$""")
  private val PID_LAUNCH_DIR_REGEX = Regex("""^composeai-launch-pid(\d+)(?:-.*)?$""")

  private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
  private val launchSeq = AtomicLong()
  private val sweptDirs = ConcurrentHashMap.newKeySet<String>()

  /**
   * The `-D` arguments for [properties]: inline, or one `@argfile` holding them all when one is too
   * long for `execve` ([MAX_INLINE_PROPERTY]) or together they would crowd the limit. The argfile
   * is created in [directory] (the temp directory when null), tagged with this JVM's PID so a
   * future startup can sweep it even after `SIGKILL`, and marked `deleteOnExit()`.
   */
  public fun of(properties: Map<String, String>, directory: File? = null): List<String> {
    val inline = properties.map { (k, v) -> "-D$k=$v" }
    val limit = if (isWindows) MAX_WINDOWS_INLINE_PROPERTIES else MAX_INLINE_PROPERTIES
    if (inline.none { it.length > MAX_INLINE_PROPERTY } && inline.sumOf { it.length } <= limit) {
      return inline
    }
    sweepStaleArgFilesOnce(directory)
    val pid = ProcessHandle.current().pid()
    val file = File.createTempFile("$PROPS_PREFIX-pid$pid-", ".args", directory)
    file.deleteOnExit()
    // The launcher decodes argfiles in the platform encoding, as `classpathArgFile` writes them.
    val charset = runCatching {
      Charset.forName(System.getProperty("sun.jnu.encoding"))
    }
      .getOrDefault(Charset.defaultCharset())
    file.writeText(inline.joinToString("\n", postfix = "\n") { quote(it) }, charset)
    return listOf("@" + file.absolutePath)
  }

  /**
   * Creates an isolated per-launch directory (`composeai-launch-pid<PID>-<seq>`) under [baseDir]
   * (defaulting to `java.io.tmpdir`) to hold one child launch's `@argfile`s (`composeai-cp*.args`
   * and `composeai-props*.args`). Deleting this directory once the child has started leaves zero
   * temp files behind, and any directory left by a `SIGKILL`ed parent is swept on the next startup.
   */
  public fun newLaunchArgDir(baseDir: File? = null): File {
    val parent = baseDir ?: defaultTempDir()
    sweepStaleArgFilesOnce(parent)
    val pid = ProcessHandle.current().pid()
    val seq = launchSeq.incrementAndGet()
    val dir = Files.createTempDirectory(parent.toPath(), "$LAUNCH_DIR_PREFIX$pid-$seq-").toFile()
    dir.deleteOnExit()
    return dir
  }

  /** Returns the `composeai-props*.args` and `composeai-cp*.args` files referenced by [command]. */
  public fun extractArgFiles(command: List<String>): List<File> = command.mapNotNull { token ->
    if (!token.startsWith("@")) return@mapNotNull null
    val file = File(token.substring(1))
    val name = file.name
    if (name.endsWith(".args") && (name.startsWith(PROPS_PREFIX) || name.startsWith(CP_PREFIX))) {
      file
    } else {
      null
    }
  }

  /** Best-effort deletion of [files]; never throws. */
  public fun deleteArgFiles(files: Collection<File>) {
    for (file in files) {
      runCatching { file.delete() }
    }
  }

  /**
   * Deletes [dir] and its descendants in post-order without following symbolic links.
   *
   * Top-level symbolic links are left untouched and return `false`. Nested symbolic links (or
   * reparse points / junctions reported as `isOther`) are unlinked directly without descending into
   * their targets. Never throws.
   */
  public fun deleteRecursivelyNoFollow(dir: File): Boolean = runCatching {
    val root = dir.toPath()
    if (Files.isSymbolicLink(root) || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return@runCatching false
    }
    var allDeleted = true
    Files.walkFileTree(
      root,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
          subDir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (attrs.isSymbolicLink || attrs.isOther) {
            if (subDir == root) {
              allDeleted = false
              return FileVisitResult.TERMINATE
            }
            if (!runCatching { Files.deleteIfExists(subDir) }.isSuccess) {
              allDeleted = false
            }
            return FileVisitResult.SKIP_SUBTREE
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (file == root && (attrs.isSymbolicLink || attrs.isOther)) {
            allDeleted = false
            return FileVisitResult.TERMINATE
          }
          if (!runCatching { Files.deleteIfExists(file) }.isSuccess) {
            allDeleted = false
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
          if (file == root && Files.isSymbolicLink(file)) {
            allDeleted = false
            return FileVisitResult.TERMINATE
          }
          if (!runCatching { Files.deleteIfExists(file) }.isSuccess) {
            allDeleted = false
          }
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(subDir: Path, exc: IOException?): FileVisitResult {
          if (exc != null) allDeleted = false
          if (!runCatching { Files.deleteIfExists(subDir) }.isSuccess) {
            allDeleted = false
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    allDeleted && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
  }
    .getOrDefault(false)

  /** Sweeps stale argfiles and launch dirs in [directory] at most once per directory per JVM. */
  public fun sweepStaleArgFilesOnce(directory: File? = null) {
    val target = directory ?: defaultTempDir()
    val key = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
    if (sweptDirs.add(key)) {
      sweepStaleArgFiles(target)
    }
  }

  /**
   * Sweeps stale `composeai-launch-pid*` directories and `composeai-props*.args` /
   * `composeai-cp*.args` files in [directory] (or `java.io.tmpdir` when null):
   * - PID-tagged files and launch directories are removed immediately once their owner PID is dead.
   * - Legacy non-PID argfiles are removed once older than [legacyMaxAgeMs].
   * - Top-level symbolic links are skipped, and directory deletion never follows nested symbolic
   *   links.
   *
   * Returns the number of entries removed. Never throws.
   */
  public fun sweepStaleArgFiles(
    directory: File? = null,
    legacyMaxAgeMs: Long = DEFAULT_LEGACY_ARGFILE_MAX_AGE_MS,
    nowMs: Long = System.currentTimeMillis(),
    isPidAlive: (Long) -> Boolean = ::isProcessAlive,
  ): Int {
    val target = directory ?: defaultTempDir()
    val entries = runCatching { target.listFiles() }.getOrNull() ?: return 0
    var removed = 0
    for (entry in entries) {
      val path = entry.toPath()
      if (runCatching { Files.isSymbolicLink(path) }.getOrDefault(true)) continue
      val name = entry.name
      val dirMatch = PID_LAUNCH_DIR_REGEX.matchEntire(name)
      if (dirMatch != null && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        val pid = dirMatch.groupValues[1].toLongOrNull() ?: continue
        if (!isPidAlive(pid) && deleteRecursivelyNoFollow(entry)) {
          removed++
        }
        continue
      }
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !name.endsWith(".args")) continue
      if (!name.startsWith(PROPS_PREFIX) && !name.startsWith(CP_PREFIX)) continue
      val fileMatch = PID_ARGFILE_REGEX.matchEntire(name)
      if (fileMatch != null) {
        val pid = fileMatch.groupValues[1].toLongOrNull() ?: continue
        if (!isPidAlive(pid) && runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
          removed++
        }
      } else {
        val lastModified = runCatching {
          Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        }
          .getOrElse { entry.lastModified() }
        val ageMs = nowMs - lastModified
        if (
          ageMs >= legacyMaxAgeMs && runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
        ) {
          removed++
        }
      }
    }
    return removed
  }

  /** One argfile token: quoted, with `\` and `"` escaped as the launcher un-escapes them. */
  public fun quote(token: String): String =
    "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private fun defaultTempDir(): File = File(System.getProperty("java.io.tmpdir"))

  private fun isProcessAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
