package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The machine-facing half: SDK discovery, the level clamp, and the font cache location. */
class DaemonBackendTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `an out of range sdk level is clamped rather than rejected`() {
    val jar = File("/sdk/android.jar")
    assertThat(DaemonBackend.Android(jar, sdkLevel = 99).effectiveSdkLevel)
      .isEqualTo(AndroidSdk.MAX_SDK)
    assertThat(DaemonBackend.Android(jar, sdkLevel = 4).effectiveSdkLevel)
      .isEqualTo(AndroidSdk.MIN_SDK)
    assertThat(DaemonBackend.Android(jar).effectiveSdkLevel).isEqualTo(AndroidSdk.DEFAULT_SDK)
    // The clamp reaches the config Robolectric actually reads, not just the property.
    assertThat(DaemonBackend.Android(jar, sdkLevel = 99).robolectricConfig().composableLaneBody())
      .contains("sdk=${AndroidSdk.MAX_SDK}")
  }

  @Test
  fun `the background agent flag is a jvm arg because it must land before AWT initialises`() {
    assertThat(DaemonBackend.Desktop().jvmArgs()).contains("-Dapple.awt.UIElement=true")
    assertThat(DaemonBackend.Desktop(backgroundAgent = false).jvmArgs())
      .doesNotContain("-Dapple.awt.UIElement=true")
  }

  /**
   * The regression this API exists for: the three properties were forwarded on the Gradle path and
   * the desktop serve daemon but on no Android lane, so an air-gapped Android render still reached
   * for Google Fonts (yschimke/compose-ai-tools#5371).
   */
  @Test
  fun `font properties this process set are forwarded to both backends`() {
    val names =
      listOf("composeai.fonts.offline", "composeai.svg.embedFonts", "composeai.svg.background")
    val saved = names.associateWith { System.getProperty(it) }
    try {
      names.forEach { System.setProperty(it, "sentinel-$it") }
      for (backend in listOf(DaemonBackend.Desktop(), DaemonBackend.Android(File("/a.jar")))) {
        val properties = backend.systemProperties()
        names.forEach { assertThat(properties[it]).isEqualTo("sentinel-$it") }
      }
    } finally {
      saved.forEach { (name, value) ->
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
      }
    }
  }

  @Test
  fun `an unset font property emits nothing rather than an empty value`() {
    val saved = System.getProperty("composeai.fonts.offline")
    System.clearProperty("composeai.fonts.offline")
    try {
      assertThat(DaemonBackend.Desktop().systemProperties())
        .doesNotContainKey("composeai.fonts.offline")
    } finally {
      saved?.let { System.setProperty("composeai.fonts.offline", it) }
    }
  }

  @Test
  fun `the font cache follows XDG_CACHE_HOME and falls back to the home cache`() {
    val xdg =
      DaemonBackend.composeAiFontsCacheDir(env = { if (it == "XDG_CACHE_HOME") "/xdg" else null })
    assertThat(xdg.path).isEqualTo("/xdg/composeai/fonts")

    val home = DaemonBackend.composeAiFontsCacheDir(env = { null }, userHome = "/home/someone")
    assertThat(home.path).isEqualTo("/home/someone/.cache/composeai/fonts")

    // A blank XDG_CACHE_HOME is unset, not a relative root.
    val blank = DaemonBackend.composeAiFontsCacheDir(env = { "  " }, userHome = "/home/someone")
    assertThat(blank.path).isEqualTo("/home/someone/.cache/composeai/fonts")
  }

  @Test
  fun `sdk discovery prefers local properties then the environment`() {
    val sdk = temp.newFolder("sdk")
    File(sdk, "platforms/android-34").mkdirs()
    File(sdk, "platforms/android-36").mkdirs()
    File(sdk, "platforms/android-34/android.jar").writeText("")
    File(sdk, "platforms/android-36/android.jar").writeText("")
    val localProperties = temp.newFile("local.properties")
    localProperties.writeText("sdk.dir=${sdk.absolutePath}\n")

    // Highest platform wins.
    assertThat(AndroidSdk.discover(localProperties, env = { null }))
      .isEqualTo(File(sdk, "platforms/android-36/android.jar"))
    // local.properties outranks the environment.
    assertThat(AndroidSdk.discover(localProperties, env = { "/nowhere" }))
      .isEqualTo(File(sdk, "platforms/android-36/android.jar"))
    // Environment when there is no local.properties.
    assertThat(AndroidSdk.discover(null, env = { if (it == "ANDROID_HOME") sdk.path else null }))
      .isEqualTo(File(sdk, "platforms/android-36/android.jar"))
  }

  @Test
  fun `an unreachable sdk is null rather than an exception`() {
    assertThat(AndroidSdk.discover(null, env = { null })).isNull()
    assertThat(AndroidSdk.discover(null, env = { temp.root.path })).isNull()
  }

  @Test
  fun `a platform directory without an android jar is skipped`() {
    val sdk = temp.newFolder("sdk2")
    File(sdk, "platforms/android-36").mkdirs() // no jar
    File(sdk, "platforms/android-33").mkdirs()
    File(sdk, "platforms/android-33/android.jar").writeText("")

    assertThat(AndroidSdk.discover(null, env = { sdk.path }))
      .isEqualTo(File(sdk, "platforms/android-33/android.jar"))
  }
}
