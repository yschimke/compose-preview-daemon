package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The seam that makes a renderer launch and a daemon launch configure Robolectric identically.
 *
 * The point of these tests is the *equality*: a caller with no `android.jar` must not end up with a
 * subtly different flag set from a daemon's, because that is exactly how the two copies in
 * compose-ai-tools drifted and lost the font opt-outs on every Android lane
 * (yschimke/compose-ai-tools#5371).
 */
class RobolectricLaunchTest {

  private val backend = DaemonBackend.Android(androidJar = File("/sdk/android.jar"))

  @Test
  fun `the backend's jvm args are the renderer's, exactly`() {
    assertThat(RobolectricLaunch.jvmArgs()).isEqualTo(backend.jvmArgs())
    assertThat(RobolectricLaunch.jvmArgs())
      .containsAtLeast(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
      )
  }

  @Test
  fun `the backend's system properties are the renderer's, exactly`() {
    assertThat(RobolectricLaunch.systemProperties()).isEqualTo(backend.systemProperties())
  }

  @Test
  fun `the four forwarded properties reach a jar-less caller too`() {
    val names =
      listOf(
        "composeai.fonts.offline",
        "composeai.svg.embedFonts",
        "composeai.svg.background",
        "composeai.fonts.failOnFallback",
      )
    val saved = names.associateWith { System.getProperty(it) }
    try {
      names.forEach { System.setProperty(it, "sentinel-$it") }
      val properties = RobolectricLaunch.systemProperties()
      names.forEach { assertThat(properties[it]).isEqualTo("sentinel-$it") }
      assertThat(properties).isEqualTo(backend.systemProperties())
    } finally {
      saved.forEach { (name, value) ->
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
      }
    }
  }

  @Test
  fun `config clamps the sdk level like the backend does`() {
    assertThat(RobolectricLaunch.config(sdkLevel = 99).sdkLevel).isEqualTo(AndroidSdk.MAX_SDK)
    assertThat(RobolectricLaunch.config(sdkLevel = 4).sdkLevel).isEqualTo(AndroidSdk.MIN_SDK)
    assertThat(RobolectricLaunch.config(sdkLevel = 33).composableLaneBody())
      .isEqualTo(
        DaemonBackend.Android(File("/sdk/android.jar"), sdkLevel = 33)
          .robolectricConfig()
          .composableLaneBody()
      )
  }

  @Test
  fun `config carries the consumer-application opt-in through`() {
    assertThat(RobolectricLaunch.config(sdkLevel = 35).composableLaneBody())
      .contains("application=android.app.Application")
    assertThat(
        RobolectricLaunch.config(sdkLevel = 35, useConsumerApplication = true).composableLaneBody()
      )
      .doesNotContain("application=")
  }

  @Test
  fun `renderer jars are the one artifact a one-shot render needs`() {
    val runtime = DaemonRuntimeLocation { artifact ->
      when (artifact) {
        DaemonRuntimeArtifact.RENDERER -> listOf(File("/runtime/renderer.jar"))
        else -> listOf(File("/runtime/should-not-be-asked-for.jar"))
      }
    }

    assertThat(RobolectricLaunch.rendererJars(runtime))
      .containsExactly(File("/runtime/renderer.jar"))
  }
}
