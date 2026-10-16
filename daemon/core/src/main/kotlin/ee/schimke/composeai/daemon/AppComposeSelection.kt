package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.jar.JarFile

/**
 * Which Compose runs a preview when the user classpath carries its own (issue
 * compose-ai-tools#6016), and the loader that makes it so. docs/daemon/CLASSLOADER.md § "Whose
 * Compose runs".
 *
 * The daemon distribution bundles a Compose on its own classpath. The Bazel and bundle lanes put
 * the target's runtime jars — Compose included, possibly newer than the daemon's — on the child
 * `composeai.daemon.userClassDirs` loader, which [UserClassLoaderHolder.mustDelegateToParent] sends
 * every `androidx.` lookup *past*. So those lanes always ran the daemon's Compose.
 *
 * Splitting Compose across the two loaders is not an option: the renderer calls Compose itself
 * (`setContent`, `captureToImage`, semantics), so it and the preview must link against one copy, or
 * a `Composer` crosses the seam as a `ClassCastException` / `LinkageError`. What this does instead
 * is change which bytes the *one* Compose is defined from. Robolectric's sandbox loader defines
 * every acquired class (all of `androidx.`) itself, reading the bytecode through its parent's
 * `getResource`; [AppComposeFirstLoader] is installed as that parent and answers `androidx/`
 * resources from the user classpath first. Renderer, preview and every bundled library then link
 * against the app's Compose, the child loader still delegates `androidx.` to the sandbox, and the
 * shared-state packages stay parent-only exactly as before.
 *
 * This is the Gradle lane's arrangement reproduced without a dependency resolver: there the
 * consumer's Compose wins conflict resolution and the renderer, compiled against a compat floor
 * (docs/RENDERER_COMPATIBILITY.md), runs on it.
 */
public object AppComposeSelection {

  /**
   * `auto`: the app's Compose runs when it is newer than the daemon's, or its version is unknown.
   */
  public const val MODE_AUTO: String = "auto"

  /** `app`: the app's Compose runs whenever the user classpath carries one. */
  public const val MODE_APP: String = "app"

  /** `daemon`: the daemon's bundled Compose always runs — the behaviour before this knob. */
  public const val MODE_DAEMON: String = "daemon"

  /** The class whose presence means "this classpath carries Compose". */
  public const val COMPOSER_RESOURCE: String = "androidx/compose/runtime/Composer.class"

  /** The version marker every AndroidX Compose runtime jar ships. */
  public const val RUNTIME_VERSION_RESOURCE: String =
    "META-INF/androidx.compose.runtime_runtime.version"

  /**
   * The outcome of [decide]. [preferApp] is the only field that changes behaviour; the rest is for
   * the log line and for tests.
   */
  public class Decision(
    public val mode: String,
    public val preferApp: Boolean,
    public val appComposeSource: URL?,
    public val appVersion: String?,
    public val daemonVersion: String?,
    public val reason: String,
    /** The user-classpath entries the sandbox reads `androidx/` resources from first. */
    public val appUrls: List<URL>,
  ) {
    /** One stderr line naming whose Compose runs and why. */
    public fun logLine(): String =
      "compose-ai-daemon: [appCompose] mode=$mode runs=${if (preferApp) "app" else "daemon"} " +
        "app=${appVersion ?: if (appComposeSource == null) "absent" else "unknown"}" +
        (appComposeSource?.let { " appSource=${it.path}" } ?: "") +
        " daemon=${daemonVersion ?: "unknown"} reason=$reason"

    override fun toString(): String = logLine()
  }

  /**
   * Decides whose Compose runs for a user classpath of [userUrls], given the daemon's own
   * [daemonLoader] and [mode] (one of [MODE_AUTO], [MODE_APP], [MODE_DAEMON]; anything else is
   * treated as [MODE_AUTO] and says so in the reason).
   */
  public fun decide(
    userUrls: List<URL>,
    daemonLoader: ClassLoader,
    mode: String = DaemonProperties.appCompose.read() ?: MODE_AUTO,
  ): Decision {
    val normalized = mode.trim().lowercase()
    val effectiveMode =
      if (normalized in setOf(MODE_AUTO, MODE_APP, MODE_DAEMON)) normalized else MODE_AUTO
    val unknownModeNote = if (effectiveMode != normalized) " (unknown mode '$mode')" else ""
    val appSource = userUrls.firstOrNull { entryExists(it, COMPOSER_RESOURCE) }
    val appVersion = appSource?.let { readEntry(it, RUNTIME_VERSION_RESOURCE) }
    val daemonVersion =
      daemonLoader.getResource(RUNTIME_VERSION_RESOURCE)?.let { url ->
        runCatching { url.openStream().use { it.readBytes().decodeToString().trim() } }.getOrNull()
      }
    fun decision(preferApp: Boolean, reason: String) =
      Decision(
        mode = effectiveMode,
        preferApp = preferApp,
        appComposeSource = appSource,
        appVersion = appVersion,
        daemonVersion = daemonVersion,
        reason = reason + unknownModeNote,
        appUrls = if (preferApp) userUrls.toList() else emptyList(),
      )
    if (appSource == null) return decision(false, "user-classpath-has-no-compose")
    if (effectiveMode == MODE_DAEMON) return decision(false, "escape-hatch")
    if (effectiveMode == MODE_APP) return decision(true, "forced")
    if (daemonLoader.getResource(COMPOSER_RESOURCE) == null) {
      return decision(true, "daemon-has-no-compose")
    }
    if (appVersion == null) return decision(true, "app-version-unknown")
    if (daemonVersion == null) return decision(true, "daemon-version-unknown")
    val cmp = compareVersions(appVersion, daemonVersion)
    return when {
      cmp > 0 -> decision(true, "app-newer")
      cmp == 0 -> decision(false, "same-version")
      // An older app Compose is the direction that already works: the preview, compiled against
      // it, runs forward-compatibly on the daemon's. Preferring it would instead run the daemon's
      // bundled libraries (Material 3, ui-test) below the Compose they were compiled against.
      else -> decision(false, "app-older")
    }
  }

  /**
   * Compares two AndroidX versions (`1.12.0`, `1.13.0-alpha03`, `1.13.0-SNAPSHOT`). Numeric
   * segments compare numerically; a pre-release sorts below its release, and `alpha < beta < rc`. A
   * qualifier the scheme does not know (`SNAPSHOT`, a build hash) sorts above every known one, so a
   * locally built Compose counts as the newest of its line.
   */
  public fun compareVersions(a: String, b: String): Int {
    val (aNums, aPre) = split(a)
    val (bNums, bPre) = split(b)
    for (i in 0 until maxOf(aNums.size, bNums.size)) {
      val c = (aNums.getOrElse(i) { 0 }).compareTo(bNums.getOrElse(i) { 0 })
      if (c != 0) return c
    }
    return preRank(aPre).compareTo(preRank(bPre)).takeIf { it != 0 }
      ?: aPre.orEmpty().compareTo(bPre.orEmpty())
  }

  private fun split(v: String): Pair<List<Int>, String?> {
    val trimmed = v.trim()
    val dash = trimmed.indexOf('-')
    val core = if (dash >= 0) trimmed.substring(0, dash) else trimmed
    val pre = if (dash >= 0) trimmed.substring(dash + 1) else null
    return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
  }

  /** Release is 4; `rc` 3, `beta` 2, `alpha` 1, `dev` 0; an unknown qualifier 5. */
  private fun preRank(pre: String?): Int {
    if (pre == null) return 4
    val p = pre.lowercase()
    return when {
      p.startsWith("rc") -> 3
      p.startsWith("beta") -> 2
      p.startsWith("alpha") -> 1
      p.startsWith("dev") -> 0
      else -> 5
    }
  }

  private fun entryExists(url: URL, entry: String): Boolean = readEntryBytes(url, entry) != null

  private fun readEntry(url: URL, entry: String): String? =
    readEntryBytes(url, entry)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }

  private fun readEntryBytes(url: URL, entry: String): ByteArray? {
    if (url.protocol != "file") return null
    val file = runCatching { File(url.toURI()) }.getOrNull() ?: return null
    return when {
      file.isDirectory -> file.resolve(entry).takeIf { it.isFile }?.readBytes()
      file.isFile -> runCatching {
          JarFile(file).use { jar ->
            jar.getJarEntry(entry)?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
          }
        }
          .getOrNull()
      else -> null
    }
  }

  /**
   * Whether [name] (a resource path) is answered from the user classpath first: every `androidx/`
   * resource — classes, AndroidX `R` classes, version markers under `META-INF/androidx.` — plus the
   * legacy `android/support/` classes AndroidX jars still ship (`androidx.core:core` carries
   * `android.support.v4.app.SupportActivity`, which the app's `ComponentActivity` extends; the
   * child loader delegates `android.` to the sandbox, so it must find them there). Nothing else, so
   * the app's own classes never become visible to the sandbox loader (they stay the child loader's)
   * and Kotlin, coroutines, Robolectric and the daemon keep the daemon's copy.
   */
  public fun isPreferredResource(name: String): Boolean =
    name.startsWith("androidx/") ||
      name.startsWith("android/support/") ||
      name.startsWith("META-INF/androidx.")
}

/**
 * The parent the Robolectric sandbox loader is built over when the app's Compose runs: answers
 * [AppComposeSelection.isPreferredResource] resources from [appUrls] first, everything else (and
 * every class load) from [parent] as before.
 *
 * Deliberately **not** a [URLClassLoader]: Robolectric takes a `URLClassLoader` parent's URLs as
 * the sandbox's own classpath, which would drop `java.class.path` and expose the user's classes to
 * the sandbox loader. As a plain [ClassLoader] the sandbox still parses `java.class.path`.
 */
public class AppComposeFirstLoader(appUrls: List<URL>, parent: ClassLoader) : ClassLoader(parent) {

  // Bootstrap-parented: only ever asked for resources, never to define a class.
  private val app = URLClassLoader(appUrls.toTypedArray(), null)

  override fun getResource(name: String): URL? {
    if (AppComposeSelection.isPreferredResource(name)) {
      app.findResource(name)?.let {
        return it
      }
    }
    return super.getResource(name)
  }

  override fun getResources(name: String): Enumeration<URL> {
    val parentResources = super.getResources(name)
    if (!AppComposeSelection.isPreferredResource(name)) return parentResources
    val appResources = Collections.list(app.findResources(name))
    if (appResources.isEmpty()) return parentResources
    return Collections.enumeration(appResources + Collections.list(parentResources))
  }

  override fun toString(): String = "AppComposeFirstLoader(${app.urLs.size} app entries)"
}
