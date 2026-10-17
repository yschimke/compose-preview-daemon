package ee.schimke.composeai.renderer

import android.app.Notification
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.example.consumerclock.ConsumerClockSource
import com.example.consumerclock.ConsumerTimeLabel
import com.example.consumerclock.ConsumerWearM2Screen
import com.example.consumerclock.ConsumerWearM3Screen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end pin of the consumer's own wall clock (compose-ai-tools#5945) on the one-shot
 * renderer's configuration shape.
 *
 * `@Config` spells out what `RobolectricConfig.withClockPackages` writes into
 * `robolectric.properties` — the consumer package in `instrumentedPackages`, [ShadowConsumerClock]
 * in `shadows` — plus the Wear clock pair the Gradle path's generated file already carries, so the
 * Wear shadow and the consumer clock run together. `ShadowSystem` is *not* listed: registering a
 * shadow through `Config.shadows` makes `AndroidConfigurer.withConfig` instrument its `@Implements`
 * class, which is the half this test would catch going missing (the consumer would read 1970).
 *
 * `com.example.consumerclock` stands in for the consumer. Every test sets
 * `composeai.render.clockPackages` to it, as the tools do on the render JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
  sdk = [34],
  qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav",
  instrumentedPackages = [CONSUMER_PACKAGE, "androidx.wear.compose.materialcore.ResourcesKt"],
  shadows = [ShadowConsumerClock::class, ShadowWearTimeSource::class],
)
class ConsumerClockRenderTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule val tmp = TemporaryFolder()

  @Before
  fun configureConsumerPackages() {
    System.setProperty(ConsumerClock.PROPERTY, CONSUMER_PACKAGE)
    // Roborazzi writes nothing unless recording; an empty PNG would compare equal to anything.
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun restoreGlobals() {
    System.clearProperty(ConsumerClock.PROPERTY)
    System.clearProperty(PreviewClock.PROPERTY)
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `instrumented static preview keeps primitive defaults under a partial seed`() {
    val entry =
      RenderPreviewEntry(
        id = "ConsumerDefaultedLabel",
        functionName = "ConsumerDefaultedLabel",
        className = "com.example.consumerclock.ConsumerClockFixturesKt",
      )
    // The same constructor the clock-instrumented Wear catalog acquires. This receiver is
    // ignored by the JVM for a static method, but used to make invokeWithDefaultMask abstain.
    val container = Class.forName(entry.className)
    assertTrue(container.declaredConstructors.any { it.parameterCount == 0 })
    rule.setContent { strategyFor(PreviewKind.COMPOSE).Render(entry, 227, 227, listOf(null, 3)) }
    rule.onNodeWithText("enabled=true count=3").assertExists()
  }

  @Test
  fun `consumer currentTimeMillis reads the pinned instant at any host time`() {
    val first = ConsumerClockSource.now()
    Thread.sleep(20)
    val second = ConsumerClockSource.now()

    assertEquals(PreviewClock.pinnedTimeMillis()!!, first)
    assertEquals(first, second)
  }

  @Test
  fun `an explicit fixedTime is what the consumer reads`() {
    System.setProperty(PreviewClock.PROPERTY, "09:41")

    assertEquals("09:41:00.000", ConsumerClockSource.format(ConsumerClockSource.now()))
  }

  @Test
  fun `fixedTime off hands the consumer the host clock, not the emulated one`() {
    System.setProperty(PreviewClock.PROPERTY, "off")

    val before = System.currentTimeMillis()
    val consumer = ConsumerClockSource.now()

    assertTrue("expected the host clock (~$before), got $consumer", consumer - before in 0..60_000)
  }

  /**
   * Instrumenting the consumer also routes its `System.nanoTime()` through `ShadowSystem`, which
   * would hand it the emulated uptime. The shadow gives the consumer the host value back, so
   * pinning the wall clock changes nothing else about its timing.
   */
  @Test
  fun `consumer nanoTime stays on the host clock`() {
    val host = System.nanoTime()
    val consumer = ConsumerClockSource.nanoNow()

    assertTrue("expected the host nanoTime (~$host), got $consumer", abs(consumer - host) < 1e9)
  }

  /**
   * The caller check: the framework's own `System.currentTimeMillis()` reads (here `Notification`'s
   * constructor stamping `when`) keep Robolectric's emulated clock, as without this change.
   */
  @Test
  fun `framework callers keep ShadowSystem's emulated clock`() {
    val now = SystemClock.uptimeMillis()

    assertEquals(now, Notification().`when`)
  }

  /** With the property cleared the shadow is a pass-through even for an instrumented consumer. */
  @Test
  fun `without the property the shadow defers to ShadowSystem`() {
    System.clearProperty(ConsumerClock.PROPERTY)

    assertEquals(SystemClock.uptimeMillis(), ConsumerClockSource.now())
  }

  @Test
  fun `a consumer time label renders identically at two host times`() {
    val texts = mutableListOf<String>()
    val (first, second) = renderTwice { ConsumerTimeLabel { texts += it } }

    assertEquals(listOf("10:10:00.000"), texts.distinct())
    assertArrayEquals(first, second)
  }

  /**
   * Wear Material3 `TimeText` inside a consumer screen that also reads the clock itself: the Wear
   * shadow and the consumer-clock shadow each handle their own read, neither disturbs the other,
   * and both paint the pinned `10:10`.
   */
  @Test
  fun `a consumer screen wrapping M3 TimeText renders identically at two host times`() {
    val timeTexts = mutableListOf<String>()
    val labels = mutableListOf<String>()
    val (first, second) =
      renderTwice {
        ConsumerWearM3Screen(onTimeText = { timeTexts += it }, onLabel = { labels += it })
      }

    assertEquals(listOf("10:10"), timeTexts.distinct())
    assertEquals(listOf("10:10:00.000"), labels.distinct())
    assertArrayEquals(first, second)
  }

  @Test
  fun `a consumer screen wrapping M2 TimeText renders identically at two host times`() {
    val timeTexts = mutableListOf<String>()
    val labels = mutableListOf<String>()
    val (first, second) =
      renderTwice {
        ConsumerWearM2Screen(onTimeText = { timeTexts += it }, onLabel = { labels += it })
      }

    assertEquals(listOf("10:10"), timeTexts.distinct())
    assertEquals(listOf("10:10:00.000"), labels.distinct())
    assertArrayEquals(first, second)
  }

  /**
   * The control for the three render tests above: with pinning off the same two renders differ, so
   * "identical" there means the clock was pinned, not that the capture could not see it.
   */
  @Test
  fun `with fixedTime off the same two renders differ`() {
    System.setProperty(PreviewClock.PROPERTY, "off")
    val texts = mutableListOf<String>()
    val (first, second) = renderTwice { ConsumerTimeLabel { texts += it } }

    assertTrue("labels should differ: $texts", texts.distinct().size >= 2)
    assertFalse("host-clock renders a second apart should differ", first.contentEquals(second))
  }

  /**
   * Composes [content], captures it, waits until the host clock has moved on by more than a second,
   * recomposes from scratch (a new `key` re-runs every `remember`, Wear's time source included),
   * and captures again.
   */
  private fun renderTwice(content: @Composable () -> Unit): Pair<ByteArray, ByteArray> {
    val generation = mutableIntStateOf(0)
    rule.setContent { key(generation.intValue) { content() } }
    rule.waitForIdle()
    val first = capture("first")
    Thread.sleep(1_100)
    generation.intValue++
    rule.waitForIdle()
    return first to capture("second")
  }

  /**
   * The PNG bytes Roborazzi writes for the root — the same capture call the renderer makes, so
   * "byte-identical" here is the property the visual-diff bot and a render cache rely on.
   */
  private fun capture(name: String): ByteArray {
    val file = tmp.newFile("$name.png")
    rule.onRoot().captureRoboImage(file = file)
    return file.readBytes().also { assertTrue("empty capture $file", it.isNotEmpty()) }
  }
}

private const val CONSUMER_PACKAGE = "com.example.consumerclock."
