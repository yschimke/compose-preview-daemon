package ee.schimke.composeai.daemon.client

import java.io.File
import java.util.Properties

/**
 * Which renderer a daemon hosts, and the facts about running it that only this repository knows.
 *
 * ### Why this is here and not in the caller
 *
 * A `DaemonLaunchDescriptor` is a JVM command line: classpath, JVM args, system properties, main
 * class. `SubprocessDaemonClientFactory` executes one faithfully and decides almost nothing — so
 * everything that decides what goes *in* one has, until now, lived in the callers. In
 * compose-ai-tools that knowledge exists in **two** copies: `AndroidPreviewClasspath` in the Gradle
 * plugin and `AndroidBundleLaunch` in `bundle/format`, the latter's KDoc asking the reader to "keep
 * them in sync if the plugin side changes".
 *
 * They had not stayed in sync, and the divergence was a bug: three properties the Android renderer
 * reads off system properties (`composeai.fonts.offline`, `composeai.svg.embedFonts`,
 * `composeai.svg.background`) were forwarded to the child JVM on the Gradle path and on the desktop
 * serve daemon but on no Android lane at all, so an air-gapped Android render still tried to fetch
 * Google Fonts (yschimke/compose-ai-tools#5371).
 *
 * None of those flags is a decision a caller is entitled to make. The `--add-opens` set is what
 * *our* Robolectric host needs to reflect into `java.base` on JDK 17+; the `robolectric.*` values
 * are how *our* renderer expects Robolectric configured; the `robolectric.properties` package path
 * is *our* renderer's package. They are facts about this repository, so they belong in it — the
 * seam EMBEDDING.md states as "facts about the daemon here, decisions about the application there".
 */
public sealed interface DaemonBackend {

  /** JVM args the child needs before anything else initialises. */
  public fun jvmArgs(): List<String>

  /**
   * Renderer-facing system properties this backend requires, plus any this process carries that the
   * child must be told about explicitly.
   */
  public fun systemProperties(): Map<String, String>

  /** Artifacts [DaemonRuntimeLocation] must supply for this backend. */
  public val requiredArtifacts: List<DaemonRuntimeArtifact>

  /**
   * Compose Multiplatform Desktop, via `ImageComposeScene`. No sandbox, no Android SDK.
   *
   * @property backgroundAgent runs the JVM as a macOS background agent — no Dock icon, no focus
   *   steal. Passed as a JVM arg rather than through [systemProperties] because it must land before
   *   AWT initialises, which is earlier than the daemon's own property reads. Inert off macOS, and
   *   emitted unconditionally so a descriptor is identical on every host: making it conditional
   *   would mean the golden files differ by build machine.
   */
  public data class Desktop(public val backgroundAgent: Boolean = true) : DaemonBackend {

    override fun jvmArgs(): List<String> = buildList {
      add(ENABLE_NATIVE_ACCESS)
      if (backgroundAgent) add("-Dapple.awt.UIElement=true")
    }

    override fun systemProperties(): Map<String, String> = fontSystemProperties()

    override val requiredArtifacts: List<DaemonRuntimeArtifact>
      get() = listOf(DaemonRuntimeArtifact.DAEMON_DESKTOP, DaemonRuntimeArtifact.RENDERER)
  }

  /**
   * Jetpack Compose on Android, via Robolectric.
   *
   * @property androidJar `android.jar` from a local SDK — see [AndroidSdk.discover].
   * @property sdkLevel clamped into the bundled Robolectric's supported range on construction, so a
   *   caller cannot ask for a level the runtime has no `android-all-instrumented` jar for.
   */
  public data class Android(
    public val androidJar: File,
    public val sdkLevel: Int = AndroidSdk.DEFAULT_SDK,
  ) : DaemonBackend {

    /** Clamped, not rejected: an out-of-range level is a caller guess, not a failure. */
    public val effectiveSdkLevel: Int = sdkLevel.coerceIn(AndroidSdk.MIN_SDK, AndroidSdk.MAX_SDK)

    /** [RobolectricLaunch.jvmArgs] — a renderer fact, and identical for a jar-less caller. */
    override fun jvmArgs(): List<String> = RobolectricLaunch.jvmArgs()

    override fun systemProperties(): Map<String, String> = RobolectricLaunch.systemProperties()

    override val requiredArtifacts: List<DaemonRuntimeArtifact>
      get() = listOf(DaemonRuntimeArtifact.DAEMON_ANDROID, DaemonRuntimeArtifact.RENDERER)

    /** The `robolectric.properties` files Robolectric merges for this SDK level. */
    public fun robolectricConfig(): RobolectricConfig = RobolectricLaunch.config(effectiveSdkLevel)
  }

  public companion object {
    internal const val ENABLE_NATIVE_ACCESS: String = "--enable-native-access=ALL-UNNAMED"

    /**
     * Font properties both backends need, and the three a parent `-D` cannot deliver on its own.
     *
     * A system property set on *this* JVM does not reach a spawned one, so anything the child reads
     * has to be named here or it silently takes its default. These three are read by the renderers
     * and the figma-svg connector in this repository, and each was being forwarded — or not — by
     * hand at three separate call sites in compose-ai-tools.
     */
    internal fun fontSystemProperties(): Map<String, String> = buildMap {
      put("composeai.fonts.cacheDir", composeAiFontsCacheDir().absolutePath)
      forwardIfSet("composeai.fonts.offline")
      forwardIfSet("composeai.svg.embedFonts")
      forwardIfSet("composeai.svg.background")
    }

    /** Copies a property from this process to the child, only when this process actually set it. */
    internal fun MutableMap<String, String>.forwardIfSet(name: String) {
      System.getProperty(name)?.let { put(name, it) }
    }

    /**
     * `$XDG_CACHE_HOME/composeai/fonts`, else `~/.cache/composeai/fonts`.
     *
     * The same directory the Gradle plugin computes, deliberately: a bundle or serve render then
     * reuses the faces a pack-time render already downloaded instead of fetching them again.
     */
    internal fun composeAiFontsCacheDir(
      env: (String) -> String? = { System.getenv(it) },
      userHome: String = System.getProperty("user.home"),
    ): File {
      val base =
        env("XDG_CACHE_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
          ?: File(userHome, ".cache")
      return File(File(base, "composeai"), "fonts")
    }
  }
}

/** An artifact a [DaemonRuntimeLocation] has to be able to supply. */
public enum class DaemonRuntimeArtifact {
  /** The desktop daemon and its Skiko natives. */
  DAEMON_DESKTOP,
  /** The Android daemon — the large sidecar, shipped separately from a CLI tarball. */
  DAEMON_ANDROID,
  /** The renderer the backend links. */
  RENDERER,
}

/**
 * Where this daemon's own jars live.
 *
 * **An interface rather than a resolver, deliberately.** Locating jars is a *packaging* question
 * and every embedder answers it differently: a CLI tarball, a Gradle configuration, an IDE plugin's
 * bundled jars, a Maven resolution, an air-gapped mirror. Shipping a resolver in this module would
 * put an HTTP client, a cache directory, checksum policy and proxy configuration into the daemon —
 * the surface most likely to be wrong in somebody else's environment, which is exactly the
 * population it would exist to serve.
 *
 * What the daemon owes the caller is the *requirement*, not the mechanism: [DaemonBackend]
 * enumerates which artifacts a backend needs, and this interface is how the caller satisfies them.
 * A convenience implementation that resolves published coordinates belongs in a separate, optional
 * artifact so that core stays free of resolution and an enterprise caller can swap it out while
 * keeping the contract (EMBEDDING.md, decision 2).
 */
public fun interface DaemonRuntimeLocation {
  /**
   * Jars for [artifact], or an empty list when this location cannot supply it — the caller turns
   * that into an actionable diagnostic rather than a crash, because "the Android sidecar is not
   * unpacked" is a setup problem with a fix, not a bug.
   */
  public fun jarsFor(artifact: DaemonRuntimeArtifact): List<File>
}

/** Android SDK discovery — the half of "how to run the Android backend" that reads the machine. */
public object AndroidSdk {

  /** Floor of the bundled Robolectric's `android-all-instrumented` range (API 21). */
  public const val MIN_SDK: Int = 21

  /** Ceiling of the bundled Robolectric's supported range (API 36). */
  public const val MAX_SDK: Int = 36

  /** Used when the caller pins none. Recent and widely available. */
  public const val DEFAULT_SDK: Int = 35

  /**
   * Resolve `android.jar`: `sdk.dir` in [localPropertiesFile] first, then `ANDROID_HOME` /
   * `ANDROID_SDK_ROOT`, then the highest-versioned `platforms/android-N/android.jar` under the
   * resolved root.
   *
   * Null when no SDK is reachable. That is a setup condition with a clear remedy, so it is a value
   * the caller can report rather than an exception it has to catch.
   */
  public fun discover(
    localPropertiesFile: File? = null,
    env: (String) -> String? = { System.getenv(it) },
  ): File? = sdkRoot(localPropertiesFile, env)?.let(::highestPlatformAndroidJar)

  private fun sdkRoot(localPropertiesFile: File?, env: (String) -> String?): File? {
    localPropertiesFile
      ?.takeIf { it.isFile }
      ?.let { file ->
        val props = Properties().apply { file.inputStream().use { load(it) } }
        props
          .getProperty("sdk.dir")
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            return File(it)
          }
      }
    for (name in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
      env(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          return File(it)
        }
    }
    return null
  }

  private fun highestPlatformAndroidJar(root: File): File? =
    File(root, "platforms")
      .listFiles()
      ?.filter { it.isDirectory && it.name.startsWith("android-") }
      ?.mapNotNull { dir ->
        val level = dir.name.removePrefix("android-").toIntOrNull() ?: return@mapNotNull null
        val jar = File(dir, "android.jar")
        if (jar.isFile) level to jar else null
      }
      ?.maxByOrNull { it.first }
      ?.second
}
