package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RobolectricConfigTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  /**
   * Robolectric merges a parent package's `robolectric.properties` into a child's. The app-tour
   * lane must therefore be a *sibling* of the renderer package: nested under it, it would inherit
   * the stub `application=` line — the one line an Activity preview must not have.
   */
  @Test
  fun `the app tour package is a sibling of the renderer package`() {
    assertThat(RobolectricConfig.APP_TOUR_PACKAGE)
      .doesNotContain("${RobolectricConfig.RENDERER_PACKAGE}.")
    assertThat(RobolectricConfig.APP_TOUR_PACKAGE.substringBeforeLast('.'))
      .isEqualTo(RobolectricConfig.RENDERER_PACKAGE.substringBeforeLast('.'))
  }

  @Test
  fun `the composable lane pins the stub application and the app tour lane does not`() {
    val config = RobolectricConfig(sdkLevel = 35)
    assertThat(config.composableLaneBody()).contains("application=android.app.Application")
    assertThat(config.appTourLaneBody()).doesNotContain("application=")
    // Everything else is identical, so the lanes cannot drift on graphics or shadows.
    assertThat(config.appTourLaneBody())
      .isEqualTo(
        config
          .composableLaneBody()
          .lines()
          .filterNot { it.startsWith("application=") }
          .joinToString("\n")
      )
  }

  @Test
  fun `a preview safe application can be opted into`() {
    assertThat(RobolectricConfig(sdkLevel = 35, useConsumerApplication = true).composableLaneBody())
      .doesNotContain("application=")
  }

  @Test
  fun `writeTo lays both files out on the package paths robolectric looks at`() {
    val root = temp.newFolder("config")
    assertThat(RobolectricConfig(sdkLevel = 33).writeTo(root)).isEqualTo(root)

    val composable =
      File(root, "${RobolectricConfig.RENDERER_PACKAGE_PATH}/${RobolectricConfig.FILE_NAME}")
    val appTour =
      File(root, "${RobolectricConfig.APP_TOUR_PACKAGE_PATH}/${RobolectricConfig.FILE_NAME}")
    assertThat(composable.isFile).isTrue()
    assertThat(appTour.isFile).isTrue()
    assertThat(composable.readText()).startsWith("sdk=33\n")
    assertThat(composable.readText()).endsWith("\n")
  }

  @Test
  fun `writing twice overwrites rather than appends`() {
    val root = temp.newFolder("config2")
    RobolectricConfig(sdkLevel = 33).writeTo(root)
    RobolectricConfig(sdkLevel = 35).writeTo(root)

    val composable =
      File(root, "${RobolectricConfig.RENDERER_PACKAGE_PATH}/${RobolectricConfig.FILE_NAME}")
    assertThat(composable.readText()).startsWith("sdk=35\n")
    assertThat(composable.readText()).doesNotContain("sdk=33")
  }
}
