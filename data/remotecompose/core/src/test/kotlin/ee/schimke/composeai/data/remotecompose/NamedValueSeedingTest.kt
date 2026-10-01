package ee.schimke.composeai.data.remotecompose

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [reseed] — what a seeded named value means to every player. The type mapping is the one
 * the connector's View-player `ApplyConnectorOverridesTest` pins through a real `StateUpdater`, so
 * a knob means the same thing on any player; the rest pins the re-seed behaviour a second render
 * with a different override set relies on.
 */
class NamedValueSeedingTest {

  /** Records every setter call as a tag/name/value triple, and every clear as `clear`/name. */
  private class CapturingTarget : NamedValueSeedTarget {
    val calls = mutableListOf<Triple<String, String, Any?>>()

    override fun setString(name: String, value: String) {
      calls += Triple("string", name, value)
    }

    override fun setFloat(name: String, value: Float) {
      calls += Triple("float", name, value)
    }

    override fun setInt(name: String, value: Int) {
      calls += Triple("int", name, value)
    }

    override fun setBoolean(name: String, value: Boolean) {
      calls += Triple("boolean", name, value)
    }

    override fun setColor(name: String, argb: Int) {
      calls += Triple("color", name, argb)
    }

    override fun clear(name: String) {
      calls += Triple("clear", name, null)
    }
  }

  @Test
  fun `every value type lands on its typed setter`() {
    val target = CapturingTarget()
    val applied =
      target.reseed(
        previous = emptySet(),
        overrides =
          linkedMapOf(
            "label" to RemoteNamedValue.StringValue("Hello!"),
            "opacity" to RemoteNamedValue.FloatValue(0.5f),
            "corner" to RemoteNamedValue.DpValue(8f),
            "count" to RemoteNamedValue.IntValue(42),
            "on" to RemoteNamedValue.BooleanValue(true),
            "seed" to RemoteNamedValue.ColorValue("#FF3366FF"),
          ),
      )
    assertEquals(
      listOf(
        Triple("string", "label", "Hello!"),
        Triple("float", "opacity", 0.5f),
        // A dp is a raw float, as on the view player.
        Triple("float", "corner", 8f),
        Triple("int", "count", 42),
        Triple("boolean", "on", true),
        Triple("color", "seed", 0xFF3366FF.toInt()),
      ),
      target.calls,
    )
    assertEquals(setOf("label", "opacity", "corner", "count", "on", "seed"), applied)
  }

  @Test
  fun `six-digit color value is opaque`() {
    val target = CapturingTarget()
    target.reseed(emptySet(), mapOf("seed" to RemoteNamedValue.ColorValue("#FF6F61")))
    assertEquals(listOf(Triple("color", "seed", 0xFFFF6F61.toInt() as Any?)), target.calls)
  }

  @Test
  fun `an unparseable color is skipped and not reported as seeded`() {
    val target = CapturingTarget()
    val applied =
      target.reseed(
        emptySet(),
        linkedMapOf(
          "short" to RemoteNamedValue.ColorValue("#F61"),
          "good" to RemoteNamedValue.ColorValue("#FF3366FF"),
        ),
      )
    assertEquals(listOf(Triple("color", "good", 0xFF3366FF.toInt() as Any?)), target.calls)
    assertEquals(setOf("good"), applied)
  }

  @Test
  fun `a name dropped from the seed set is restored`() {
    val target = CapturingTarget()
    val applied =
      target.reseed(
        previous = setOf("label", "count"),
        overrides = mapOf("label" to RemoteNamedValue.StringValue("Hi")),
      )
    assertEquals(
      listOf(Triple("string", "label", "Hi" as Any?), Triple("clear", "count", null)),
      target.calls,
    )
    assertEquals(setOf("label"), applied)
  }

  /**
   * A name that held a valid colour and is now sent with a typo would otherwise keep the previous
   * render's colour — neither what was asked for nor the authored default.
   */
  @Test
  fun `a previously seeded name whose new colour does not parse is restored`() {
    val target = CapturingTarget()
    val applied =
      target.reseed(
        previous = setOf("seed"),
        overrides = mapOf("seed" to RemoteNamedValue.ColorValue("#ZZTOPZZ")),
      )
    assertEquals(listOf(Triple("clear", "seed", null as Any?)), target.calls)
    assertEquals(emptySet<String>(), applied)
  }

  @Test
  fun `empty overrides with nothing previously seeded is a no-op`() {
    val target = CapturingTarget()
    assertEquals(emptySet<String>(), target.reseed(emptySet(), emptyMap()))
    assertEquals(emptyList<Any>(), target.calls)
  }
}
