// The sidecar archives consumers download instead of resolving ~250 MB of Robolectric + Compose
// from Maven Central at first render:
//
//   * `compose-preview-android-daemon-<version>.zip`   → `lib-daemon-android/`
//   * `compose-preview-desktop-daemon-<version>.tar.gz` → `lib-daemon-desktop/` + `lib-renderer/`
//
// `compose-preview bundle daemon` (compose-ai-tools) fetches the first on demand; the
// preview-server
// image bakes both. Before the split these were staged by `cli/build.gradle.kts` in
// compose-ai-tools;
// the archive names and the directory layout inside them are the contract that repository, the
// server image and `ServeBundleDaemon.locateBundleSidecarJars` rely on, so they are unchanged.
//
// A plain project, no `distributions {}` entry: the distribution plugin would wire the archives
// into
// `assemble` and drag `:daemon:android` (and its Android SDK requirement) into every `./gradlew
// build`. The release job calls the two package tasks explicitly.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins { id("composeai.base-conventions") }

val composePreviewRenderer =
  configurations.create("composePreviewRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

val composePreviewDaemonDesktop =
  configurations.create("composePreviewDaemonDesktop") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// `:daemon:android` publishes its runtime classpath as a text descriptor (one absolute jar path
// per line, ordered module jar → testFixtures → R.jar → full test runtime → android.jar) on a
// consumable configuration carrying this attribute; see that module's `writeDaemonClasspath`.
val composePreviewDaemonAndroid =
  configurations.create("composePreviewDaemonAndroid") {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
      attribute(
        Attribute.of("ee.schimke.composeai.daemon.harness.classpath", String::class.java),
        "android",
      )
    }
  }

dependencies {
  add("composePreviewRenderer", project(":renderer-desktop"))
  add("composePreviewDaemonDesktop", project(":daemon:desktop"))
  add("composePreviewDaemonAndroid", project(":daemon:android"))
}

/**
 * Copies the jars a resolved configuration names, dropping host-specific Skiko natives (the
 * consumer resolves the pair for its own platform) and renaming basename collisions to
 * `module-version.jar` — several artifacts ship as `classes.jar`, and a flat directory would
 * otherwise keep one of them.
 */
fun Sync.stageFrom(configuration: Configuration) {
  val artifactsProvider = configuration.incoming.artifacts.resolvedArtifacts
  from(
    artifactsProvider.map { resolved ->
      resolved
        .filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
        .map(ResolvedArtifactResult::getFile)
    }
  )
  val nameByPath = artifactsProvider.map { resolved ->
    val staged = resolved.filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
    val counts = staged.groupingBy { it.file.name }.eachCount()
    staged.associate { artifact ->
      val original = artifact.file.name
      val mapped =
        if (counts.getValue(original) > 1) {
          val id = artifact.id.componentIdentifier
          if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
        } else original
      artifact.file.absolutePath to mapped
    }
  }
  inputs.property("nameByPath", nameByPath)
  eachFile {
    val mapped = nameByPath.get()[file.absolutePath]
    if (mapped != null) name = mapped
  }
}

val stageRendererLibs =
  tasks.register<Sync>("stageRendererLibs") {
    description = "Stages the desktop renderer runtime without host-specific Skiko natives."
    destinationDir = layout.buildDirectory.dir("staged-renderer-libs").get().asFile
    stageFrom(composePreviewRenderer)
  }

val stageDaemonDesktopLibs =
  tasks.register<Sync>("stageDaemonDesktopLibs") {
    description = "Stages :daemon:desktop runtime artifacts, renaming filename collisions."
    destinationDir = layout.buildDirectory.dir("staged-daemon-desktop-libs").get().asFile
    stageFrom(composePreviewDaemonDesktop)
  }

/**
 * Copies each jar the Android daemon's classpath descriptor names into one directory. Two
 * deliberate transforms: `android.jar` is dropped (the SDK platform jar is redistribution-sensitive
 * and the launcher re-adds it from the consumer's `ANDROID_HOME`), and filenames are index-prefixed
 * (`%04d-<name>`) so the descriptor's classpath precedence survives the `lib-daemon-android/` glob
 * the launcher expands and AAR `classes.jar` / AGP `R.jar` basenames cannot collide.
 */
abstract class StageDaemonAndroidLibs : DefaultTask() {
  @get:InputFiles abstract val classpathDescriptor: ConfigurableFileCollection

  @get:OutputDirectory abstract val destinationDir: DirectoryProperty

  @TaskAction
  fun stage() {
    val descriptor = classpathDescriptor.singleFile
    val dest = destinationDir.get().asFile
    dest.deleteRecursively()
    dest.mkdirs()
    descriptor
      .readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { File(it) }
      .filter { it.isFile && it.name.endsWith(".jar") && it.name != "android.jar" }
      .forEachIndexed { index, jar -> jar.copyTo(File(dest, "%04d-%s".format(index, jar.name))) }
  }
}

val stageDaemonAndroidLibs =
  tasks.register<StageDaemonAndroidLibs>("stageDaemonAndroidLibs") {
    description =
      "Stages :daemon:android runtime jars (from its classpath descriptor) into lib-daemon-android."
    classpathDescriptor.from(composePreviewDaemonAndroid)
    destinationDir.set(layout.buildDirectory.dir("staged-daemon-android-libs"))
  }

abstract class CheckSkikoNativePackaging : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val stagedJars: ConfigurableFileCollection

  @TaskAction
  fun checkPackaging() {
    val jars = stagedJars.files.flatMap { root -> root.listFiles()?.toList().orEmpty() }
    val nativeJars = jars.filter { it.name.startsWith("skiko-awt-runtime-") }
    check(nativeJars.isEmpty()) {
      "Desktop sidecar contains host-specific Skiko natives: ${nativeJars.joinToString { it.name }}"
    }
    check(jars.any { it.name.matches(Regex("skiko-awt-[^-].*\\.jar")) }) {
      "Desktop sidecar lost the skiko-awt API jar needed to derive the native version"
    }
  }
}

val checkSkikoNativePackaging =
  tasks.register<CheckSkikoNativePackaging>("checkSkikoNativePackaging") {
    description = "Checks that the desktop sidecar stages no host-specific Skiko native jars."
    group = "verification"
    dependsOn(stageRendererLibs, stageDaemonDesktopLibs)
    stagedJars.from(stageRendererLibs, stageDaemonDesktopLibs)
  }

// The version is the release-please manifest's, or the tag's on a release — the same
// `publishedVersion()` the published modules use, read off a module that carries it.
val sidecarVersion = provider { project(":daemon:core").version.toString() }

tasks.register<Zip>("packageAndroidDaemon") {
  description =
    "Packages the Android daemon runtime as a standalone archive for on-demand download."
  archiveFileName.set(sidecarVersion.map { "compose-preview-android-daemon-$it.zip" })
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  into("lib-daemon-android") { from(stageDaemonAndroidLibs) }
}

tasks.register<Tar>("packageDesktopDaemon") {
  description = "Packages the desktop daemon and renderer sidecars."
  dependsOn(checkSkikoNativePackaging)
  archiveFileName.set(sidecarVersion.map { "compose-preview-desktop-daemon-$it.tar.gz" })
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  compression = Compression.GZIP
  into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
  into("lib-renderer") { from(stageRendererLibs) }
}
