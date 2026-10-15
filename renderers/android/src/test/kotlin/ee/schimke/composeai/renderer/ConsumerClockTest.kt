package ee.schimke.composeai.renderer

import com.example.consumerclock.ConsumerClockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM half of [ConsumerClock]: parsing, protected prefixes and the caller walk. */
class ConsumerClockTest {

  @Test
  fun `unset or blank names no packages`() {
    assertEquals(emptyList<String>(), ConsumerClock.parse(null))
    assertEquals(emptyList<String>(), ConsumerClock.parse(""))
    assertEquals(emptyList<String>(), ConsumerClock.parse(" , ;"))
  }

  @Test
  fun `comma and semicolon separated prefixes are trimmed and deduplicated`() {
    assertEquals(
      listOf("com.example.app.", "com.example.lib"),
      ConsumerClock.parse(" com.example.app. ;com.example.lib, com.example.app."),
    )
  }

  /**
   * A prefix overlapping the JDK, Robolectric, the framework or this renderer is dropped: the last
   * would instrument [PreviewClock] itself, whose `fixedTime=off` read would then loop back here.
   */
  @Test
  fun `prefixes overlapping protected roots are dropped`() {
    for (bad in
      listOf(
        "java.",
        "java",
        "kotlin.collections.",
        "org.robolectric.shadows.",
        "android.",
        "ee.schimke.composeai.renderer.",
        "ee.schimke.composeai.",
        "ee.",
        "e",
      )) {
      assertEquals("'$bad' should be rejected", emptyList<String>(), ConsumerClock.parse(bad))
    }
    assertEquals(
      listOf("ee.schimke.composeai.samples."),
      ConsumerClock.parse("ee.schimke.composeai.samples."),
    )
  }

  @Test
  fun `consumer classes match by prefix, like Robolectric's instrumentedPackages`() {
    val packages = listOf("com.example.app.")
    assertTrue(ConsumerClock.isConsumerClass("com.example.app.ui.Clock", packages))
    assertTrue(ConsumerClock.isConsumerClass("com.example.app.ClockKt\$Label\$1", packages))
    assertFalse(ConsumerClock.isConsumerClass("com.example.application.Clock", packages))
    assertFalse(ConsumerClock.isConsumerClass("android.app.Notification", packages))
  }

  /** Outside a sandbox there is no `ShadowSystem` frame, so nothing is attributed to a consumer. */
  @Test
  fun `the caller walk finds nothing when the target is not on the stack`() {
    assertNull(ConsumerClock.callerOf(ConsumerClock.SHADOW_SYSTEM))
  }

  /** And without instrumentation, setting the property alone changes nothing. */
  @Test
  fun `an uninstrumented consumer still reads the host clock`() {
    System.setProperty(ConsumerClock.PROPERTY, "com.example.consumerclock.")
    try {
      val before = System.currentTimeMillis()
      assertTrue(ConsumerClockSource.now() - before in 0..60_000)
    } finally {
      System.clearProperty(ConsumerClock.PROPERTY)
    }
  }
}
