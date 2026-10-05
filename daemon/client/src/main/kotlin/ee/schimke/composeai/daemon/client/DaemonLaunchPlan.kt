package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File

/**
 * Everything needed to launch one daemon, assembled into a [DaemonLaunchDescriptor], so callers do
 * not each re-derive what belongs in one.
 *
 * ### The split of responsibility
 * |this owns                                     |the caller owns                                                 |
 * |----------------------------------------------|----------------------------------------------------------------|
 * |`mainClass`                                   |the application classpath — the composables to render           |
 * |JVM args per backend                          |the working directory                                           |
 * |renderer-facing system properties             |which module / workspace this daemon serves                     |
 * |`robolectric.properties` and its package paths|how many daemons exist, and for how long                        |
 * |the Android SDK clamp                         |where this daemon's own jars come from ([DaemonRuntimeLocation])|
 *
 * The daemon never decides how many daemons exist, how long they live, or what happens when one
 * dies (EMBEDDING.md).
 *
 * Classpath order: on Android the generated Robolectric config (so it beats the copy in the
 * renderer jar) and `android.jar`; then the application classes; then the daemon runtime.
 *
 * @property backend which renderer, and the facts about running it.
 * @property applicationClasspath the composables to render and what they need. The caller's half.
 * @property runtime where this daemon's own jars live.
 * @property workingDirectory the child's working directory. Also where [describe] writes the
 *   Android `robolectric.properties` tree, since both sides can always see it.
 * @property options the `composeai.daemon.*` knobs, typed. See [DaemonLaunchOptions].
 * @property modulePath names the daemon in logs and keys test doubles; not a filesystem path.
 * @property javaLauncher the `java` binary. Null means the launcher's own JVM.
 */
public class DaemonLaunchPlan(
  public val backend: DaemonBackend,
  public val applicationClasspath: List<File>,
  public val runtime: DaemonRuntimeLocation,
  public val workingDirectory: File,
  public val options: DaemonLaunchOptions = DaemonLaunchOptions(),
  public val modulePath: String = ":",
  public val javaLauncher: File? = null,
) {

  /**
   * The artifacts [backend] needs that [runtime] could not supply. Callers should report a
   * non-empty result (typically the separately shipped Android sidecar) rather than launch a JVM
   * that fails with `NoClassDefFoundError`.
   */
  public fun missingArtifacts(): List<DaemonRuntimeArtifact> =
    backend.requiredArtifacts.filter { runtime.jarsFor(it).isEmpty() }

  /**
   * Assembles the descriptor. On Android this also writes the `robolectric.properties` tree under
   * [workingDirectory], where a jailed child (with its own `/tmp`) can still see it. Does not check
   * [missingArtifacts].
   */
  public fun describe(): DaemonLaunchDescriptor {
    val runtimeJars = backend.requiredArtifacts.flatMap(runtime::jarsFor)
    val classpath = buildList {
      if (backend is DaemonBackend.Android) {
        add(backend.robolectricConfig().writeTo(File(workingDirectory, ROBOLECTRIC_CONFIG_DIR)))
        add(backend.androidJar)
      }
      addAll(applicationClasspath)
      addAll(runtimeJars)
    }

    return DaemonLaunchDescriptor.Builder(
        schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
        modulePath = modulePath,
        variant = variantName(),
        enabled = true,
        mainClass = DAEMON_MAIN_CLASS,
        classpath = classpath.map { it.absolutePath }.distinct(),
        // Bound JIT threads in Android daemons (pooled workers and spares inherit it). A caller
        // can still replace it through the descriptor's jvmArgs.
        jvmArgs =
          backend.jvmArgs() +
            if (backend is DaemonBackend.Android) listOf("-XX:CICompilerCount=2") else emptyList(),
        // Backend properties first, so a caller's explicit option wins a collision.
        systemProperties = backend.systemProperties() + options.toSystemProperties(),
        workingDirectory = workingDirectory.absolutePath,
        // Blank means "none"; the daemon mains then fall back to the PreviewIndex catalog.
        manifestPath = options.previewsJsonPath.orEmpty(),
      )
      .also { it.javaLauncher = javaLauncher?.absolutePath }
      .build()
  }

  private fun variantName(): String =
    when (backend) {
      is DaemonBackend.Desktop -> "desktop"
      is DaemonBackend.Android -> "android"
    }

  public companion object {
    /** Both backends run the same entrypoint; the classpath is what differs. */
    public const val DAEMON_MAIN_CLASS: String = "ee.schimke.composeai.daemon.DaemonMain"

    /**
     * The `daemon-launch.json` schema version; part of the API, since consumers on other release
     * trains parse the file back.
     */
    public const val DAEMON_LAUNCH_SCHEMA_VERSION: Int = 2

    /** Where [describe] materialises the Android `robolectric.properties` tree. */
    public const val ROBOLECTRIC_CONFIG_DIR: String = "robolectric-config"
  }
}
