package ee.schimke.composeai.daemon

import java.io.File
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * A loader's private generation of mutable compiler output directories.
 *
 * A base generation ([capture]) copies every user-class directory. A tip generation ([tip]) holds
 * only the classes that differ from a base, plus every base class that links against them, so a tip
 * loader layered over the base loader reloads those and leaves the rest warm.
 */
internal class UserClassDirectories
private constructor(
  val urls: List<URL>,
  private val root: Path?,
  /** Per URL: the live directory and its private copy; `null` for a jar or a missing entry. */
  private val directories: List<Pair<Path, Path>?>,
  /** Per URL: the jar's length and mtime at capture; `null` for anything else. */
  private val jarStamps: List<Pair<Long, Long>?>,
  /** The binary class names a tip defines itself; `null` for a base generation. */
  val owned: Set<String>?,
) : Runnable {

  /** Base class → the base classes whose bytes reference it. Built on the first [tip]. */
  private var referrers: Map<String, Set<String>>? = null

  override fun run() {
    root?.let { if (it.toFile().deleteRecursively()) roots.remove(it) }
  }

  /**
   * The tip over this base for the user directories as they are now, or `null` when only a fresh
   * base can represent them: a jar or a non-class resource changed, or a class is unparseable. A
   * tip that owns nothing means the output still matches this base.
   *
   * [readBase] returns a base class's bytes by resource name from the base loader's own URLs.
   */
  fun tip(readBase: (String) -> ByteArray?): UserClassDirectories? {
    check(owned == null) { "a tip is only taken over a base generation" }
    jarStamps.forEachIndexed { index, stamp ->
      if (stamp != null && stamp != stampOf(File(urls[index].toURI()))) return null
    }
    val changed = HashSet<String>()
    for (pair in directories) {
      val (live, copy) = pair ?: continue
      for (relative in relativeFiles(live) + relativeFiles(copy)) {
        if (sameFile(live.resolve(relative), copy.resolve(relative))) continue
        if (!relative.endsWith(".class")) return null
        changed += relative.removeSuffix(".class")
      }
    }
    if (changed.isEmpty()) {
      return UserClassDirectories(emptyList(), null, emptyList(), emptyList(), emptySet())
    }
    val index = referrers ?: buildReferrers(readBase)?.also { referrers = it } ?: return null
    val owned = closure(changed, index)
    val tipRoot = Files.createTempDirectory("compose-preview-tip-")
    try {
      for (name in owned) {
        val resource = "$name.class"
        // A changed class with no live bytes was deleted: owned, so the tip never serves the
        // base's.
        val bytes = if (name in changed) liveBytes(resource) else readBase(resource)
        if (bytes == null) {
          if (name in changed) continue
          tipRoot.toFile().deleteRecursively()
          return null
        }
        val target = tipRoot.resolve(resource)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
      }
    } catch (failure: Throwable) {
      tipRoot.toFile().deleteRecursively()
      throw failure
    }
    roots.add(tipRoot)
    return UserClassDirectories(
      listOf(tipRoot.toUri().toURL()),
      tipRoot,
      emptyList(),
      emptyList(),
      owned.mapTo(HashSet()) { it.replace('/', '.') },
    )
  }

  /** The current bytes of [resource] from the first live directory holding it. */
  private fun liveBytes(resource: String): ByteArray? = directories.firstNotNullOfOrNull { pair ->
    pair?.first?.resolve(resource)?.takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes)
  }

  private fun buildReferrers(readBase: (String) -> ByteArray?): Map<String, Set<String>>? {
    val names = LinkedHashSet<String>()
    for (pair in directories) {
      val copy = pair?.second ?: continue
      relativeFiles(copy)
        .filter { it.endsWith(".class") }
        .forEach { names += it.removeSuffix(".class") }
    }
    jarStamps.forEachIndexed { index, stamp ->
      if (stamp == null) return@forEachIndexed
      JarFile(File(urls[index].toURI())).use { jar ->
        jar
          .entries()
          .asSequence()
          .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
          .forEach { names += it.name.removeSuffix(".class") }
      }
    }
    val index = HashMap<String, MutableSet<String>>()
    for (name in names) {
      val bytes = readBase("$name.class") ?: continue
      val references = ClassReferences.of(bytes) ?: return null
      for (reference in references) {
        if (reference != name && reference in names) index.getOrPut(reference) { HashSet() } += name
      }
    }
    return index
  }

  companion object {
    private val roots = ConcurrentHashMap.newKeySet<Path>()

    init {
      Runtime.getRuntime()
        .addShutdownHook(
          Thread({ roots.forEach { it.toFile().deleteRecursively() } }, "preview-class-cleanup")
        )
    }

    /** [changed] plus every class that transitively references one of them. */
    internal fun closure(changed: Set<String>, referrers: Map<String, Set<String>>): Set<String> {
      val owned = HashSet(changed)
      val pending = ArrayDeque(changed)
      while (pending.isNotEmpty()) {
        referrers[pending.removeFirst()]?.forEach { if (owned.add(it)) pending.addLast(it) }
      }
      return owned
    }

    fun capture(urls: List<URL>): UserClassDirectories {
      var root: Path? = null
      try {
        val directories = ArrayList<Pair<Path, Path>?>()
        val jarStamps = ArrayList<Pair<Long, Long>?>()
        val captured = urls.mapIndexed { index, url ->
          val source = if (url.protocol == "file") Path.of(url.toURI()) else null
          if (source == null || !Files.isDirectory(source)) {
            directories += null
            jarStamps += source?.toFile()?.takeIf { it.isFile }?.let(::stampOf)
            return@mapIndexed url
          }
          val base =
            root ?: Files.createTempDirectory("compose-preview-classes-").also { root = it }
          val destination = base.resolve(index.toString())
          Files.walk(source, FileVisitOption.FOLLOW_LINKS).use { entries ->
            entries.forEach { entry ->
              val target = destination.resolve(source.relativize(entry).toString())
              if (Files.isDirectory(entry)) Files.createDirectories(target)
              else Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
          }
          directories += source to destination
          jarStamps += null
          destination.toUri().toURL()
        }
        root?.let(roots::add)
        return UserClassDirectories(captured, root, directories, jarStamps, null)
      } catch (failure: Throwable) {
        root?.toFile()?.deleteRecursively()
        throw failure
      }
    }

    private fun stampOf(file: File): Pair<Long, Long> = file.length() to file.lastModified()

    /** Every regular file under [directory], as `/`-separated paths relative to it. */
    private fun relativeFiles(directory: Path): Set<String> {
      if (!Files.isDirectory(directory)) return emptySet()
      return Files.walk(directory, FileVisitOption.FOLLOW_LINKS).use { entries ->
        entries
          .filter { Files.isRegularFile(it) }
          .map { directory.relativize(it).joinToString("/") }
          .toList()
          .toSet()
      }
    }

    private fun sameFile(a: Path, b: Path): Boolean {
      if (!Files.isRegularFile(a) || !Files.isRegularFile(b)) return false
      if (Files.size(a) != Files.size(b)) return false
      // The copy keeps the original's mtime, so an untouched file matches without being read.
      if (Files.getLastModifiedTime(a) == Files.getLastModifiedTime(b)) return true
      return Files.mismatch(a, b) == -1L
    }
  }
}
