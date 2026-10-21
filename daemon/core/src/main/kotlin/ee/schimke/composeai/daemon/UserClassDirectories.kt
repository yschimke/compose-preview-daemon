package ee.schimke.composeai.daemon

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * A loader's generation of mutable compiler output directories.
 *
 * A base generation ([capture]) copies every user-class directory and remembers each file's size
 * and mtime. A tip generation ([tip]) holds, in memory, only the classes that differ from a base
 * plus every base class that links against them, so a tip loader layered over the base loader
 * reloads those and leaves the rest warm. A [live] generation serves the directories in place.
 */
internal class UserClassDirectories
private constructor(
  val urls: List<URL>,
  private val root: Path?,
  /** Per URL: the live directory and its private copy; `null` for a jar or a missing entry. */
  private val directories: List<Pair<Path, Path>?>,
  /**
   * Per URL: the copied files' stamps by relative path; `null` where [directories] is. A file whose
   * mtime was too close to the capture to trust (see [RACY_WINDOW_NANOS]) has no stamp entry beyond
   * its size, so it is always compared by bytes.
   */
  private val manifests: List<Map<String, Stamp>?>,
  /** Per URL: the jar's stamp at capture; `null` for anything else. */
  private val jarStamps: List<Stamp?>,
  /** A tip's classes by binary name; `null` for a base or live generation. */
  val classes: Map<String, TipClass>?,
) : Runnable {

  /**
   * A class a tip defines from memory, with the live class-path entry it came from as its code
   * source. Empty [bytes] mark a class deleted since the base.
   */
  internal class TipClass(val bytes: ByteArray, val location: URL?)

  /** The binary class names a tip defines itself; `null` for a base or live generation. */
  val owned: Set<String>?
    get() = classes?.keys

  /** Whether this generation's directory URLs point at private copies, not at the live output. */
  val copied: Boolean
    get() = root != null

  /** The live class-path entry a URL of this generation stands for; itself when not a copy. */
  fun originalOf(url: URL): URL {
    val index = urls.indexOf(url)
    return directories.getOrNull(index)?.first?.toUri()?.toURL() ?: url
  }

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
   * [readBase] returns a base class's bytes by resource name from the base loader's own URLs; it is
   * only asked for classes outside the copied directories (user-class jars).
   */
  fun tip(readBase: (String) -> ByteArray?): UserClassDirectories? {
    check(classes == null && manifests.size == urls.size) {
      "a tip is only taken over a captured base generation"
    }
    jarStamps.forEachIndexed { index, stamp ->
      if (stamp != null && stamp != Stamp.of(File(urls[index].toURI()))) return null
    }
    val changed = HashSet<String>()
    directories.forEachIndexed { index, pair ->
      val (live, copy) = pair ?: return@forEachIndexed
      val manifest = manifests[index]!!
      val current = stamps(live)
      // One stat per live file, from the walk itself; bytes are read only on a stamp mismatch.
      for ((relative, stamp) in current) {
        val known = manifest[relative]
        if (known == stamp && !known.racy) continue
        if (
          known?.size == stamp.size && sameBytes(live.resolve(relative), copy.resolve(relative))
        ) {
          // Verified after the window: any later rewrite gets a newer mtime, so trust the stamp.
          if (
            known == stamp &&
              System.currentTimeMillis() * 1_000_000 - RACY_WINDOW_NANOS > known.modified
          ) {
            known.racy = false
          }
          continue
        }
        if (!relative.endsWith(".class")) return null
        changed += relative.removeSuffix(".class")
      }
      for (relative in manifest.keys) {
        if (relative in current) continue
        if (!relative.endsWith(".class")) return null
        changed += relative.removeSuffix(".class")
      }
    }
    if (changed.isEmpty()) {
      return UserClassDirectories(
        emptyList(),
        null,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyMap(),
      )
    }
    val index = referrers ?: buildReferrers(readBase)?.also { referrers = it } ?: return null
    val classes = HashMap<String, TipClass>()
    for (name in closure(changed, index)) {
      val resource = "$name.class"
      val binaryName = name.replace('/', '.')
      // A changed class with no live bytes was deleted. It stays owned, as an empty entry the tip
      // loader reports missing, so the base's stale copy is never served.
      classes[binaryName] =
        if (name in changed) read(resource) { it.first } ?: TipClass(ByteArray(0), null)
        else
          read(resource) { it.second }
            ?: readBase(resource)?.let { TipClass(it, jarHolding(resource)) }
            ?: return null
    }
    return UserClassDirectories(emptyList(), null, emptyList(), emptyList(), emptyList(), classes)
  }

  /**
   * [resource] from the first directory holding it, live or copied as [side] picks, located at the
   * live directory either way.
   */
  private fun read(resource: String, side: (Pair<Path, Path>) -> Path): TipClass? =
    directories.firstNotNullOfOrNull { pair ->
      pair?.let(side)?.resolve(resource)?.let { path ->
        try {
          TipClass(Files.readAllBytes(path), pair.first.toUri().toURL())
        } catch (_: IOException) {
          null
        }
      }
    }

  /** The user-class jar URL holding [resource], for a tip class read through the base. */
  private fun jarHolding(resource: String): URL? =
    jarStamps.indices
      .firstOrNull { index ->
        jarStamps[index] != null &&
          JarFile(File(urls[index].toURI())).use { it.getJarEntry(resource) != null }
      }
      ?.let(urls::get)

  private fun buildReferrers(readBase: (String) -> ByteArray?): Map<String, Set<String>>? {
    val names = LinkedHashSet<String>()
    manifests.forEach { manifest ->
      manifest
        ?.keys
        ?.filter { it.endsWith(".class") }
        ?.forEach { names += it.removeSuffix(".class") }
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
      val resource = "$name.class"
      val bytes = read(resource) { it.second }?.bytes ?: readBase(resource) ?: continue
      val references = ClassReferences.of(bytes) ?: return null
      for (reference in references) {
        if (reference != name && reference in names) index.getOrPut(reference) { HashSet() } += name
      }
    }
    return index
  }

  /** A file's size and mtime, the cheap identity a compiler that rewrote it would change. */
  internal data class Stamp(val size: Long, val modified: Long) {
    /** Modified so close to the capture that a same-size rewrite could share its mtime. */
    var racy: Boolean = false

    companion object {
      fun of(attributes: BasicFileAttributes) =
        Stamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS))

      fun of(file: File) = Stamp(file.length(), file.lastModified())
    }
  }

  companion object {
    private val roots = ConcurrentHashMap.newKeySet<Path>()

    /**
     * Filesystem timestamps are coarser than a compile is fast, so a file modified within this long
     * of the capture is "racily clean", as git calls it: its stamp cannot vouch for its bytes.
     */
    private val RACY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2)

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

    /** [urls] served in place, as a loader replaced on every swap reads them. */
    fun live(urls: List<URL>): UserClassDirectories =
      UserClassDirectories(urls, null, emptyList(), emptyList(), emptyList(), null)

    fun capture(urls: List<URL>): UserClassDirectories {
      var root: Path? = null
      try {
        val directories = ArrayList<Pair<Path, Path>?>()
        val manifests = ArrayList<Map<String, Stamp>?>()
        val jarStamps = ArrayList<Stamp?>()
        val captured = urls.mapIndexed { index, url ->
          val source = if (url.protocol == "file") Path.of(url.toURI()) else null
          if (source == null || !Files.isDirectory(source)) {
            directories += null
            manifests += null
            jarStamps += source?.toFile()?.takeIf { it.isFile }?.let(Stamp::of)
            return@mapIndexed url
          }
          val base =
            root ?: Files.createTempDirectory("compose-preview-classes-").also { root = it }
          val destination = base.resolve(index.toString())
          val manifest = HashMap<String, Stamp>()
          val racyAfter = System.currentTimeMillis() * 1_000_000 - RACY_WINDOW_NANOS
          walk(source) { file, relative, attributes ->
            val target = destination.resolve(relative)
            Files.createDirectories(target.parent)
            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
            manifest[relative] = Stamp.of(attributes).apply { racy = modified >= racyAfter }
          }
          directories += source to destination
          manifests += manifest
          jarStamps += null
          destination.toUri().toURL()
        }
        root?.let(roots::add)
        return UserClassDirectories(captured, root, directories, manifests, jarStamps, null)
      } catch (failure: Throwable) {
        root?.toFile()?.deleteRecursively()
        throw failure
      }
    }

    /** Every regular file under [directory] by `/`-separated relative path, with its stamp. */
    private fun stamps(directory: Path): Map<String, Stamp> {
      val stamps = HashMap<String, Stamp>()
      if (Files.isDirectory(directory)) {
        walk(directory) { _, relative, attributes -> stamps[relative] = Stamp.of(attributes) }
      }
      return stamps
    }

    private fun walk(
      directory: Path,
      onFile: (file: Path, relative: String, attributes: BasicFileAttributes) -> Unit,
    ) {
      Files.walkFileTree(
        directory,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (attributes.isRegularFile) {
              onFile(file, directory.relativize(file).joinToString("/"), attributes)
            }
            return FileVisitResult.CONTINUE
          }
        },
      )
    }

    private fun sameBytes(a: Path, b: Path): Boolean =
      try {
        Files.mismatch(a, b) == -1L
      } catch (_: IOException) {
        false
      }
  }
}
