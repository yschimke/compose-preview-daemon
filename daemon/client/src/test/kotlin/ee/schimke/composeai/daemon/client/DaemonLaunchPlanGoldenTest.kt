package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Golden descriptors.
 *
 * A launch plan's whole output is one `DaemonLaunchDescriptor`, and every part of it is
 * load-bearing: drop an `--add-opens` and Robolectric fails on SDK 36, drop
 * `composeai.fonts.offline` and an air-gapped render reaches for Google Fonts anyway
 * (yschimke/compose-ai-tools#5371 — the bug that motivated this API). None of that shows up as a
 * compile error in any consumer, so the only thing that can catch it is an assertion over the
 * entire descriptor rather than over the field somebody remembered to check.
 *
 * The rendering is deliberately a flat, sorted text file: a reviewer reads the diff and sees "this
 * PR removes a JVM arg" without running anything.
 *
 * Regenerate with `./gradlew :daemon-client:test -Dcomposeai.updateGolden=true` and read the diff
 * before committing it.
 */
class DaemonLaunchPlanGoldenTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `desktop descriptor`() {
    val work = temp.newFolder("work")
    val plan =
      DaemonLaunchPlan(
        backend = DaemonBackend.Desktop(),
        applicationClasspath = listOf(File("/app/classes"), File("/app/libs/kotlin-stdlib.jar")),
        runtime = fakeRuntime(),
        workingDirectory = work,
        options =
          DaemonLaunchOptions(
            previewsJsonPath = "/app/build/compose-previews/previews.json",
            userClassDirs = listOf("/app/classes"),
            sandboxCount = 2,
            idleTimeout = 90.seconds,
          ),
        modulePath = ":app",
      )

    assertGolden("desktop.txt", plan.describe(), work)
  }

  @Test
  fun `android descriptor`() {
    val work = temp.newFolder("work")
    val plan =
      DaemonLaunchPlan(
        backend = DaemonBackend.Android(androidJar = File("/sdk/platforms/android-35/android.jar")),
        applicationClasspath = listOf(File("/app/classes")),
        runtime = fakeRuntime(),
        workingDirectory = work,
        options = DaemonLaunchOptions(userClassDirs = listOf("/app/classes")),
        modulePath = ":app",
        javaLauncher = File("/jdk/bin/java"),
      )

    assertGolden("android.txt", plan.describe(), work)
  }

  @Test
  fun `the android descriptor materialises both robolectric config lanes`() {
    val work = temp.newFolder("work")
    DaemonLaunchPlan(
        backend = DaemonBackend.Android(androidJar = File("/sdk/platforms/android-35/android.jar")),
        applicationClasspath = emptyList(),
        runtime = fakeRuntime(),
        workingDirectory = work,
      )
      .describe()

    val root = File(work, DaemonLaunchPlan.ROBOLECTRIC_CONFIG_DIR)
    val composable =
      File(root, "${RobolectricConfig.RENDERER_PACKAGE_PATH}/${RobolectricConfig.FILE_NAME}")
    val appTour =
      File(root, "${RobolectricConfig.APP_TOUR_PACKAGE_PATH}/${RobolectricConfig.FILE_NAME}")

    assertThat(composable.readText().trim().lines())
      .containsExactly(
        "sdk=35",
        "graphicsMode=NATIVE",
        "application=android.app.Application",
        "shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat",
      )
      .inOrder()
    // The app-tour lane must not inherit the stub Application: an Activity preview *is* the app.
    assertThat(appTour.readText()).doesNotContain("application=")
  }

  @Test
  fun `a caller option overrides a backend property of the same name`() {
    val work = temp.newFolder("work")
    val descriptor =
      DaemonLaunchPlan(
          backend = DaemonBackend.Desktop(),
          applicationClasspath = emptyList(),
          runtime = fakeRuntime(),
          workingDirectory = work,
          options =
            DaemonLaunchOptions(
              extra = mapOf("composeai.fonts.cacheDir" to "/pinned/fonts"),
              previewsJsonPath = "/app/previews.json",
            ),
        )
        .describe()

    assertThat(descriptor.systemProperties["composeai.fonts.cacheDir"]).isEqualTo("/pinned/fonts")
    assertThat(descriptor.systemProperties[DaemonProperties.previewsJsonPath.name])
      .isEqualTo("/app/previews.json")
  }

  @Test
  fun `an absent previews index leaves the manifest pointer blank rather than null`() {
    val work = temp.newFolder("work")
    val descriptor =
      DaemonLaunchPlan(
          backend = DaemonBackend.Desktop(),
          applicationClasspath = emptyList(),
          runtime = fakeRuntime(),
          workingDirectory = work,
        )
        .describe()

    // Both daemon mains read a blank manifest as "none set" and fall back to the PreviewIndex.
    assertThat(descriptor.manifestPath).isEmpty()
  }

  @Test
  fun `missing artifacts name what the runtime could not supply`() {
    val work = temp.newFolder("work")
    val empty = DaemonRuntimeLocation { emptyList() }

    val plan =
      DaemonLaunchPlan(
        backend = DaemonBackend.Android(androidJar = File("/sdk/android.jar")),
        applicationClasspath = emptyList(),
        runtime = empty,
        workingDirectory = work,
      )

    assertThat(plan.missingArtifacts())
      .containsExactly(DaemonRuntimeArtifact.DAEMON_ANDROID, DaemonRuntimeArtifact.RENDERER)
    // Still describable: building a descriptor from an incomplete runtime is legitimate in a test.
    assertThat(plan.describe().mainClass).isEqualTo(DaemonLaunchPlan.DAEMON_MAIN_CLASS)
  }

  private fun fakeRuntime(): DaemonRuntimeLocation = DaemonRuntimeLocation { artifact ->
    when (artifact) {
      DaemonRuntimeArtifact.DAEMON_DESKTOP -> listOf(File("/runtime/daemon-desktop.jar"))
      DaemonRuntimeArtifact.DAEMON_ANDROID -> listOf(File("/runtime/daemon-android.jar"))
      DaemonRuntimeArtifact.RENDERER -> listOf(File("/runtime/renderer.jar"))
    }
  }

  private fun assertGolden(name: String, descriptor: DaemonLaunchDescriptor, work: File) {
    val actual = render(descriptor, work)
    val file = File(GOLDEN_DIR, name)
    if (System.getProperty("composeai.updateGolden") == "true") {
      file.parentFile.mkdirs()
      file.writeText(actual)
      return
    }
    val expected =
      javaClass.getResourceAsStream("/golden/$name")?.reader()?.readText()
        ?: error("missing golden /golden/$name — regenerate with -Dcomposeai.updateGolden=true")
    assertThat(actual).isEqualTo(expected)
  }

  /**
   * Host-independent text for one descriptor.
   *
   * Two values cannot appear literally: the working directory is a per-run temporary folder, and
   * the font cache resolves against `$XDG_CACHE_HOME` / `$HOME`. Both are normalised to a token so
   * that the golden asserts the *shape* — that the config root is under the working directory, that
   * the cache is named at all — without pinning it to the machine that recorded it.
   */
  private fun render(descriptor: DaemonLaunchDescriptor, work: File): String = buildString {
    fun scrub(value: String) =
      value
        .replace(work.absolutePath, "<work>")
        .replace(DaemonBackend.composeAiFontsCacheDir().absolutePath, "<fontsCache>")

    appendLine("schemaVersion=${descriptor.schemaVersion}")
    appendLine("modulePath=${descriptor.modulePath}")
    appendLine("variant=${descriptor.variant}")
    appendLine("enabled=${descriptor.enabled}")
    appendLine("mainClass=${descriptor.mainClass}")
    appendLine("javaLauncher=${descriptor.javaLauncher ?: "<inherit>"}")
    appendLine("workingDirectory=${scrub(descriptor.workingDirectory)}")
    appendLine("manifestPath=${scrub(descriptor.manifestPath)}")
    appendLine("classpath:")
    descriptor.classpath.forEach { appendLine("  ${scrub(it)}") }
    appendLine("jvmArgs:")
    descriptor.jvmArgs.forEach { appendLine("  $it") }
    appendLine("systemProperties:")
    descriptor.systemProperties.toSortedMap().forEach { (k, v) -> appendLine("  $k=${scrub(v)}") }
  }

  private companion object {
    val GOLDEN_DIR = File("src/test/resources/golden")
  }
}
