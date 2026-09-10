package ee.schimke.composeai.daemon.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `parse(render(v)) == v`, across the whole registry.
 *
 * [DaemonProperty.render] and [DaemonProperty.parse] are the two halves of one encoding, written in
 * different places and read by different processes — the launcher renders, the daemon parses. A
 * disagreement between them is not a compile error and not a test failure anywhere else: it is a
 * knob that silently arrives as its default, which is exactly the failure mode
 * `DaemonPropertyRegistryTest` was written to end on the *reading* side and could not reach on the
 * writing side.
 *
 * The sweep over [DaemonProperties.ALL] is the point. A knob added tomorrow is covered without
 * anyone remembering to cover it, which is the same property that makes `docs/daemon/TUNABLES.md`
 * trustworthy.
 */
class DaemonPropertyRoundTripTest {

  /**
   * Every declared default survives the trip.
   *
   * Defaults are the one value guaranteed to exist for each property and guaranteed to be one the
   * daemon considers legal — a default below an `IntProperty`'s own floor would be a bug in the
   * declaration, so using them here also incidentally asserts that none is.
   */
  @Test
  fun `every property's default round-trips through render and parse`() {
    val broken =
      DaemonProperties.ALL.mapNotNull { property ->
        @Suppress("UNCHECKED_CAST") val typed = property as DaemonProperty<Any?>
        val rendered = typed.render(typed.defaultValue)
        val reparsed = typed.parse(rendered)
        if (reparsed == typed.defaultValue) null
        else "${property.name}: default=${typed.defaultValue} rendered='$rendered' parsed=$reparsed"
      }

    assertEquals(
      "These properties do not survive render → parse:\n" + broken.joinToString("\n") { "  $it" },
      emptyList<String>(),
      broken,
    )
  }

  /**
   * The list encodings specifically, because they are the ones a hand-written launcher gets wrong:
   * a path list is `File.pathSeparator`-delimited and a CSV list is not, and nothing about the call
   * site says which you are holding.
   */
  @Test
  fun `path lists and csv lists round-trip with realistic values`() {
    val paths = listOf("/tmp/classes", "/home/u/build/libs/app.jar", "/opt/with space/x")
    val pathList = DaemonProperties.ALL.filterIsInstance<PathListProperty>().first()
    assertEquals(paths, pathList.parse(pathList.render(paths)))
    assertEquals(
      "a path list must use the platform separator",
      paths.joinToString(File.pathSeparator),
      pathList.render(paths),
    )

    val csv = DaemonProperties.ALL.filterIsInstance<CsvListProperty>().firstOrNull()
    if (csv != null) {
      val values = listOf("alpha", "beta", "gamma")
      assertEquals(values, csv.parse(csv.render(values)))
    }
  }

  /** Booleans in both states, since only one of them is ever a property's default. */
  @Test
  fun `booleans round-trip in both states`() {
    val booleans = DaemonProperties.ALL.filterIsInstance<BooleanProperty>()
    assertEquals(
      "the registry should declare at least one boolean",
      true,
      booleans.isNotEmpty(),
    )
    for (property in booleans) {
      assertEquals(property.name, true, property.parse(property.render(true)))
      assertEquals(property.name, false, property.parse(property.render(false)))
    }
  }

  /**
   * An empty list renders to the empty string and parses back to an empty list — the case a naive
   * `joinToString` gets right by accident and a naive `split` gets wrong, returning `[""]`.
   */
  @Test
  fun `an empty list is not a list containing an empty string`() {
    for (property in DaemonProperties.ALL.filterIsInstance<PathListProperty>()) {
      assertEquals(property.name, emptyList<String>(), property.parse(property.render(emptyList())))
    }
    for (property in DaemonProperties.ALL.filterIsInstance<CsvListProperty>()) {
      assertEquals(property.name, emptyList<String>(), property.parse(property.render(emptyList())))
    }
  }
}
