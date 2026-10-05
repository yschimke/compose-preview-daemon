package ee.schimke.composeai.daemon.client

import java.io.File
import java.util.Properties

/**
 * Which renderer a daemon hosts, and the launch facts only this repository knows: JVM args such as
 * Robolectric's `--add-opens`, renderer system properties, required artifacts. Kept here so callers
 * cannot drift apart (EMBEDDING.md: "facts about the daemon here, decisions about the application
 * there").
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
   * @property backgroundAgent run as a macOS background agent (no Dock icon). A JVM arg because it
   *   must precede AWT init; emitted on every OS so descriptors do not differ by build machine.
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
     * Font properties for both backends. A `-D` on this JVM does not reach the child, so each
     * property the renderers read must be forwarded explicitly (compose-ai-tools#5371).
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
     * `$XDG_CACHE_HOME/composeai/fonts`, else `~/.cache/composeai/fonts` — the Gradle plugin's
     * directory too, so downloaded faces are shared.
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
 * Where this daemon's own jars live. An interface, not a resolver: packaging differs per embedder,
 * and resolution (HTTP, caching, proxies) does not belong in the daemon (EMBEDDING.md, decision 2).
 */
public fun interface DaemonRuntimeLocation {
  /** Jars for [artifact], or empty when unavailable (reported as a setup problem, not a crash). */
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
   * Resolves the highest `platforms/android-N/android.jar` under `sdk.dir` from
   * [localPropertiesFile], else `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Null when no SDK is reachable.
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
