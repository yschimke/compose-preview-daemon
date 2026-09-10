package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File

/**
 * Everything needed to launch one daemon, assembled into a [DaemonLaunchDescriptor].
 *
 * This is the piece the daemon was missing. `SubprocessDaemonClientFactory` already executes a
 * descriptor faithfully; nothing published the knowledge of what belongs *in* one, so every caller
 * worked it out again — and compose-ai-tools worked it out twice, in two modules, kept aligned by a
 * KDoc comment asking the reader to keep them in sync. They diverged, and the divergence was a live
 * bug (yschimke/compose-ai-tools#5371).
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
 * The right-hand column is the negative rule EMBEDDING.md states: **the daemon never decides how
 * many daemons exist, how long they live, or what happens when one dies.** A default here is a
 * suggestion the caller overrides, never a policy it inherits.
 *
 * ### Classpath order
 *
 * `robolectricConfigRoot` (Android only) comes first, so a config written for this launch wins over
 * any copy baked into the shipped renderer jar. Then the caller's application classes, then the
 * daemon runtime. The daemon's own jars go last so that a consumer's version of a shared library
 * does not shadow the classes the renderer was compiled against.
 *
 * @property backend which renderer, and the facts about running it.
 * @property applicationClasspath the composables to render and what they need. The caller's half.
 * @property runtime where this daemon's own jars live — see [DaemonRuntimeLocation] for why this is
 *   an interface rather than a resolver.
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
   * The artifacts [backend] needs that [runtime] could not supply.
   *
   * Empty means [describe] will produce a runnable descriptor. Non-empty is a **setup** problem —
   * the Android sidecar is ~150–200 MB and ships separately from a CLI tarball, so "not unpacked
   * yet" is an ordinary state with a clear remedy. Callers are expected to check this and report
   * it, rather than launch a JVM that will fail with a `NoClassDefFoundError` three seconds later.
   */
  public fun missingArtifacts(): List<DaemonRuntimeArtifact> =
    backend.requiredArtifacts.filter { runtime.jarsFor(it).isEmpty() }

  /**
   * Assemble the descriptor.
   *
   * **Side effect, on Android only:** writes the `robolectric.properties` tree under
   * [workingDirectory]. It is a side effect because the files have to exist on disk for Robolectric
   * to find them, and putting them under the working directory is what keeps them visible to a
   * jailed child (whose `/tmp` is its own).
   *
   * Does not check [missingArtifacts] — a caller that wants that diagnostic asks for it. Building a
   * descriptor from an incomplete runtime is legitimate in a test.
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

    return DaemonLaunchDescriptor(
      schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
      modulePath = modulePath,
      variant = variantName(),
      mainClass = DAEMON_MAIN_CLASS,
      javaLauncher = javaLauncher?.absolutePath,
      classpath = classpath.map { it.absolutePath }.distinct(),
      jvmArgs = backend.jvmArgs(),
      // Backend properties first, so a caller's explicit option wins a collision. The two sets are
      // disjoint today — `robolectric.*` / `composeai.fonts.*` against `composeai.daemon.*` — and
      // this ordering is what keeps that from becoming load-bearing.
      systemProperties = backend.systemProperties() + options.toSystemProperties(),
      workingDirectory = workingDirectory.absolutePath,
      // The preview index doubles as the descriptor's manifest pointer, so a caller that set
      // `previewsJsonPath` does not have to say it twice. Empty rather than null because the
      // descriptor's field is non-null, and both daemon mains already read a blank manifest as
      // "none set" and fall back to the `PreviewIndex`-backed catalog.
      manifestPath = options.previewsJsonPath.orEmpty(),
      enabled = true,
    )
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
     * The `daemon-launch.json` schema this descriptor is written against.
     *
     * Pinned rather than inferred: the file is read back by consumers on their own release trains
     * (compose-preview-server parses one to resume a suspended session), so the version a plan
     * emits is part of what this API promises.
     */
    public const val DAEMON_LAUNCH_SCHEMA_VERSION: Int = 2

    /** Where [describe] materialises the Android `robolectric.properties` tree. */
    public const val ROBOLECTRIC_CONFIG_DIR: String = "robolectric-config"
  }
}
