package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader

/**
 * Owns the disposable child [URLClassLoader] over the user module's compiled classes
 * (docs/daemon/CLASSLOADER.md). The long-lived parent pays the bootstrap cost once; [swap] drops
 * the child so the next render loads recompiled bytecode. An in-flight render keeps the loader it
 * already resolved against.
 *
 * Read by the render thread, swapped by the JSON-RPC thread; guarded by a lock. Every child is also
 * tracked weakly so [liveLoaderCount] can assert recycled loaders collect.
 *
 * @param parentSupplier evaluated at allocation, not construction: on Android the parent must be
 *   the Robolectric sandbox loader, which only exists once the sandbox is up. Otherwise framework
 *   classes load from the app loader and composable lookup fails on classloader-identity skew.
 * @param onSwap called with each newly allocated loader (Android mirrors it into
 *   `DaemonHostBridge`).
 */
public class UserClassLoaderHolder(
  private val urls: List<URL>,
  private val parentSupplier: () -> ClassLoader = {
    Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
  },
  private val onSwap: ((URLClassLoader) -> Unit)? = null,
) {

  private val lock = Any()
  private var current: URLClassLoader? = null
  private val trackedLoaders: MutableList<WeakReference<URLClassLoader>> = mutableListOf()

  /** The current child loader, allocated lazily; stable until [swap]. */
  public fun currentChildLoader(): URLClassLoader =
    synchronized(lock) {
      val existing = current
      if (existing != null) return@synchronized existing
      allocateLocked()
    }

  /**
   * Drops the current child loader; the next [currentChildLoader] allocates a fresh one. Not
   * pre-allocated, so a burst of file changes does not pile up loaders.
   */
  public fun swap() {
    synchronized(lock) {
      current = null
      // Pairs with the `allocate` line: a swap with no allocate means the render path missed it.
      System.err.println(
        "compose-ai-daemon: [classloader] swap requested urlCount=${urls.size} liveLoaders=${trackedLoaders.size}"
      )
    }
  }

  /** The URLs every child loader exposes, for building identically-shaped sibling loaders. */
  public fun urls(): List<URL> = urls.toList()

  /**
   * Forces two GCs and returns how many allocated child loaders survive, including the current one.
   * For soak tests.
   */
  public fun liveLoaderCount(): Int {
    repeat(2) {
      System.gc()
      try {
        Thread.sleep(20)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    synchronized(lock) {
      trackedLoaders.removeAll { it.get() == null }
      return trackedLoaders.size
    }
  }

  private fun allocateLocked(): URLClassLoader {
    val resolvedParent = parentSupplier()
    val fresh = ChildFirstURLClassLoader(urls.toTypedArray(), resolvedParent)
    current = fresh
    trackedLoaders.add(WeakReference(fresh))
    // An mtime that does not advance across saves means the compile rewrote nothing.
    System.err.println(
      "compose-ai-daemon: [classloader] allocate child loader parent=${resolvedParent.javaClass.name} " +
        "loaderId=${System.identityHashCode(fresh).toString(16)} urls=${urlsSummary(urls)}"
    )
    onSwap?.invoke(fresh)
    return fresh
  }

  /**
   * Child-first, because the user classes may also be on the parent's classpath with stale bytes.
   * [mustDelegateToParent] packages always go to the parent.
   */
  private class ChildFirstURLClassLoader(urls: Array<URL>, parent: ClassLoader) :
    URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
      synchronized(getClassLoadingLock(name)) {
        val cached = findLoadedClass(name)
        if (cached != null) {
          if (resolve) resolveClass(cached)
          return cached
        }
        if (UserClassLoaderHolder.mustDelegateToParent(name)) {
          return super.loadClass(name, resolve)
        }
        return try {
          val found = findClass(name)
          if (resolve) resolveClass(found)
          found
        } catch (_: ClassNotFoundException) {
          super.loadClass(name, resolve)
        }
      }
    }
  }

  public companion object {
    /** Sysprop: `File.pathSeparator`-delimited user-class directories, set by the launcher. */
    public const val USER_CLASS_DIRS_PROP: String = DaemonProperties.Names.USER_CLASS_DIRS

    /**
     * Packages the child must never load itself, even when its URLs carry them:
     * 1. JDK and framework runtimes (Kotlin, AndroidX, Robolectric, Skiko), which must be the one
     *    copy the parent bootstrapped.
     * 2. State shared between daemon and preview: `compose.resources` locals, the daemon's handoff
     *    queues, and the `previewOverride*` runtime the connector seeds before composition. A
     *    child-loaded copy would be a separate static, so overrides would silently no-op when a
     *    bundle classpath puts the runtime jar on the child's URLs.
     *
     * `data.overrides` belongs with `overrides`: `previewOverrideChoice` passes a
     * `PreviewOverrideOption` from that package across the seam, and splitting them across loaders
     * threw `ClassCastException` (compose-preview-server#839).
     */
    internal fun mustDelegateToParent(name: String): Boolean =
      name.startsWith("java.") ||
        name.startsWith("javax.") ||
        name.startsWith("sun.") ||
        name.startsWith("jdk.") ||
        name.startsWith("kotlin.") ||
        name.startsWith("kotlinx.") ||
        name.startsWith("androidx.") ||
        name.startsWith("android.") ||
        name.startsWith("org.robolectric.") ||
        name.startsWith("com.github.takahirom.roborazzi.") ||
        name.startsWith("org.jetbrains.skia.") ||
        name.startsWith("org.jetbrains.compose.resources.") ||
        name.startsWith("ee.schimke.composeai.daemon.") ||
        name.startsWith("ee.schimke.composeai.overrides.") ||
        name.startsWith("ee.schimke.composeai.data.overrides.")

    /**
     * Resolves [USER_CLASS_DIRS_PROP] to URLs, dropping missing entries and putting directories
     * before jars. AGP also lists a runtime classes jar that is only rebuilt at daemon start, so
     * after the first save it is stale; listed first, it would shadow the recompiled `.class`
     * directory. The sort is stable, preserving AGP's order within each group.
     */
    public fun urlsFromSysprop(): List<URL> {
      val files =
        DaemonProperties.userClassDirs.read().map { java.io.File(it) }.filter { it.exists() }
      return files.sortedBy { if (it.isDirectory) 0 else 1 }.map { it.toURI().toURL() }
    }

    /** One-line `[<path>(mtime=…), …]` dump of [urls] for stderr. */
    internal fun urlsSummary(urls: List<URL>): String =
      urls.joinToString(prefix = "[", postfix = "]") { url ->
        if (url.protocol == "file") {
          val file = java.io.File(url.toURI())
          val mtime =
            if (file.exists()) java.time.Instant.ofEpochMilli(file.lastModified()).toString()
            else "missing"
          "${file.absolutePath}(mtime=$mtime)"
        } else {
          url.toString()
        }
      }

    /**
     * A `path=… mtime=… size=… sha=…` fingerprint of [className]'s class file (or containing jar),
     * for spotting a save that did not recompile; `null` when it is not on a local file. The SHA is
     * truncated to 12 hex chars.
     */
    public fun classFileFingerprint(loader: ClassLoader, className: String): String? {
      val resourceName = className.replace('.', '/') + ".class"
      if (loader is URLClassLoader) {
        fingerprintFromUrls(loader.urLs, resourceName)?.let {
          return it
        }
      }
      val url = loader.getResource(resourceName) ?: return null
      if (url.protocol != "file") return null
      val file =
        try {
          java.io.File(url.toURI())
        } catch (_: Throwable) {
          return null
        }
      if (!file.exists()) return null
      val mtime = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
      return "path=${file.absolutePath} mtime=$mtime size=${file.length()} sha=${sha256Short(file)}"
    }

    private fun fingerprintFromUrls(urls: Array<URL>, resourceName: String): String? {
      for (url in urls) {
        if (url.protocol != "file") continue
        val root =
          try {
            java.io.File(url.toURI())
          } catch (_: Throwable) {
            continue
          }
        if (root.isDirectory) {
          val file = root.resolve(resourceName)
          if (file.exists()) {
            val mtime = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
            return "path=${file.absolutePath} mtime=$mtime size=${file.length()} sha=${sha256Short(file)}"
          }
        } else if (root.isFile) {
          val hasEntry =
            try {
              java.util.jar.JarFile(root).use { jar -> jar.getEntry(resourceName) != null }
            } catch (_: Throwable) {
              false
            }
          if (hasEntry) {
            val mtime = java.time.Instant.ofEpochMilli(root.lastModified()).toString()
            return "jar=${root.absolutePath}!/$resourceName mtime=$mtime size=${root.length()} sha=${sha256Short(root)}"
          }
        }
      }
      return null
    }

    private fun sha256Short(file: java.io.File): String {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      try {
        file.inputStream().use { input ->
          val buffer = ByteArray(8192)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        }
      } catch (_: Throwable) {
        return "unreadable"
      }
      return md.digest().take(6).joinToString("") { "%02x".format(it) }
    }
  }
}
