package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A batch render's shape — one `@Test` per preview, one sandbox — on SDK 37 with
 * [ShadowPausedClockHardwareRenderer] registered, as the render lane's `robolectric.properties`
 * registers it. Each test must capture its own colour.
 *
 * Before the cross-test `VSYNC` guard ([MonotonicFrameVsync]) only the first test to run did: the
 * paused clock restarts at 100 ms for every test, SDK 37's hwui drops a frame whose `VSYNC` is not
 * later than the last one it drew, and every later capture read back the launch frame's empty
 * `#FAFAFA` window (compose-ai-tools#6229).
 *
 * SDK 37 needs a JDK 21 test JVM, which this build's toolchain is not; on an older JVM the tests
 * report as ignored rather than fail.
 */
@RunWith(SuccessiveTestFramesSdk37Test.Jdk21Runner::class)
@Config(sdk = [37], shadows = [ShadowPausedClockHardwareRenderer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SuccessiveTestFramesSdk37Test {

  @Suppress("DEPRECATION") @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun `a first preview captures red`() = assertCaptures(Color.Red)

  @Test fun `b second preview captures blue`() = assertCaptures(Color.Blue)

  @Test fun `c third preview captures green`() = assertCaptures(Color.Green)

  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("successive-frames").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  /** Captures the way the render lane's stills do: Roborazzi, reading the window back. */
  private fun assertCaptures(colour: Color) {
    rule.setContent { Box(Modifier.fillMaxSize().background(colour)) }
    rule.waitForIdle()
    val file = File(dir, "capture.png")
    rule.onRoot().captureRoboImage(file = file)
    val image = ImageIO.read(file)
    assertEquals(
      "capture must be this test's own colour, not the previous window's launch frame",
      "#%08X".format(colour.toArgb()),
      "#%08X".format(image.getRGB(image.width / 2, image.height / 2)),
    )
  }

  /** [RobolectricTestRunner] on JDK 21+, every test ignored below it. */
  class Jdk21Runner(private val testClass: Class<*>) : Runner() {
    private val delegate: Runner? =
      if (javaFeatureVersion() >= 21) RobolectricTestRunner(testClass) else null

    override fun getDescription(): Description =
      delegate?.description
        ?: Description.createSuiteDescription(testClass).also { suite ->
          testClass.methods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .forEach { suite.addChild(Description.createTestDescription(testClass, it.name)) }
        }

    override fun run(notifier: RunNotifier) {
      delegate?.run(notifier) ?: description.children.forEach(notifier::fireTestIgnored)
    }

    // `Runtime.version()` is not on the android.jar compile classpath.
    private fun javaFeatureVersion(): Int =
      System.getProperty("java.specification.version").substringAfter("1.").toIntOrNull() ?: 0
  }
}
