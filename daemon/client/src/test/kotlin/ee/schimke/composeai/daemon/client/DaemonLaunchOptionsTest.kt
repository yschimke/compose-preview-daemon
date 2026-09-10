package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonLaunchOptionsTest {

  /**
   * The whole point: what a launcher writes is what the daemon reads.
   *
   * Rendering and parsing live in different processes, so nothing else in either repository would
   * notice them disagreeing — a mis-encoded knob simply arrives as its default and the render comes
   * back subtly wrong. Asserting through the property's own `parse` is what makes this a real check
   * rather than a restatement of `toSystemProperties`.
   */
  @Test
  fun `what the launcher writes is what the daemon parses back`() {
    val options =
      DaemonLaunchOptions(
        previewsJsonPath = "/w/build/previews.json",
        userClassDirs = listOf("/w/build/classes/kotlin/main", "/w/build/classes/java/main"),
        workspaceRoot = "/w",
        modulePath = ":app",
        sandboxCount = 5,
        maxHeapMb = 2048,
        idleTimeout = 30.minutes,
        backgroundSandboxBoot = true,
        warmSpare = false,
        cheapSignalFiles = listOf("/w/build/a.txt", "/w/build/b.txt"),
      )

    val rendered = options.toSystemProperties()
    fun read(name: String): String? = rendered[name]

    assertEquals("/w/build/previews.json", DaemonProperties.previewsJsonPath.read(::read))
    assertEquals(
      listOf("/w/build/classes/kotlin/main", "/w/build/classes/java/main"),
      DaemonProperties.userClassDirs.read(::read),
    )
    assertEquals("/w", DaemonProperties.workspaceRoot.read(::read))
    assertEquals(":app", DaemonProperties.modulePath.read(::read))
    assertEquals(5, DaemonProperties.sandboxCount.read(::read))
    assertEquals(2048, DaemonProperties.maxHeapMb.read(::read))
    assertEquals(30L * 60 * 1000, DaemonProperties.idleTimeoutMs.read(::read))
    assertEquals(true, DaemonProperties.backgroundSandboxBoot.read(::read))
    assertEquals(false, DaemonProperties.warmSpare.read(::read))
    assertEquals(
      listOf("/w/build/a.txt", "/w/build/b.txt"),
      DaemonProperties.cheapSignalFiles.read(::read),
    )
  }

  /**
   * Unset must mean *absent*, not empty.
   *
   * An empty string is a value the parser may accept, so emitting one for a field the caller never
   * touched would override the daemon's own default with the caller's silence — the opposite of
   * what leaving a field alone means.
   */
  @Test
  fun `an untouched field emits no property at all`() {
    val rendered = DaemonLaunchOptions().toSystemProperties()

    assertEquals(
      "an empty options object must render nothing",
      emptyMap<String, String>(),
      rendered,
    )
    assertFalse(rendered.containsKey(DaemonProperties.Names.HISTORY_DIR))
  }

  @Test
  fun `a path list uses the platform separator the daemon splits on`() {
    val dirs = listOf("/a", "/b with space", "/c")
    val raw =
      DaemonLaunchOptions(userClassDirs = dirs)
        .toSystemProperties()[DaemonProperties.Names.USER_CLASS_DIRS]

    assertEquals(dirs.joinToString(File.pathSeparator), raw)
  }

  /**
   * The escape hatch wins over a typed field. Deliberate: an escape hatch that the typed half
   * outranked would be useless for the case it exists for — overriding a knob this type has not
   * grown a field for yet, or one it models differently than a caller needs.
   */
  @Test
  fun `extra overrides a typed field`() {
    val rendered =
      DaemonLaunchOptions(
          maxHeapMb = 1024,
          extra = mapOf(DaemonProperties.Names.MAX_HEAP_MB to "4096"),
        )
        .toSystemProperties()

    assertEquals("4096", rendered[DaemonProperties.Names.MAX_HEAP_MB])
  }

  /**
   * Every name this type emits is a name the daemon actually declares.
   *
   * Guards the failure this type exists to prevent, from the other side: a field that renders to a
   * name nothing reads is as silently broken as a literal that was mistyped.
   */
  @Test
  fun `every emitted name is a declared property`() {
    val declared = DaemonProperties.ALL.map { it.name }.toSet()
    val emitted =
      DaemonLaunchOptions(
          previewsJsonPath = "/p",
          userClassDirs = listOf("/c"),
          bundleManifestPath = "/b",
          irDir = "/ir",
          workspaceRoot = "/w",
          modulePath = ":m",
          moduleProjectDir = "/w/m",
          sandboxCount = 1,
          maxHeapMb = 1,
          idleTimeout = 1.minutes,
          maxRendersPerSandbox = 1,
          historyDir = "/h",
          backgroundSandboxBoot = true,
          warmRenderOnBoot = true,
          warmSpare = true,
          perfettoTrace = true,
          cheapSignalFiles = listOf("/s"),
        )
        .toSystemProperties()
        .keys

    assertEquals(
      "every typed field must map to a registry entry",
      emptySet<String>(),
      emitted - declared,
    )
    assertTrue("the fully-populated case should emit every typed field", emitted.size >= 17)
  }
}
