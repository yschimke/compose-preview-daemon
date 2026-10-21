package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UserClassLoaderTipTest {
  private val root = Files.createTempDirectory("preview-tip-test").toFile()
  private val classes = root.resolve("classes").apply { mkdirs() }
  private val sources = root.resolve("src").apply { mkdirs() }

  @After
  fun cleanUp() {
    root.deleteRecursively()
  }

  /** Compiles [files] (simple name → body) into [classes], as an incremental compile would. */
  private fun compile(files: Map<String, String>) {
    val paths = files.map { (name, body) ->
      sources.resolve("$name.java").apply { writeText("package sample; $body") }.path
    }
    val args = listOf("-d", classes.path, "-cp", classes.path) + paths
    assertEquals(
      0,
      ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()),
    )
  }

  private fun holder() =
    UserClassLoaderHolder(listOf(classes.toURI().toURL())).apply { tipReload = true }

  private fun call(loader: ClassLoader, className: String): Any? =
    loader.loadClass("sample.$className").getMethod("value").invoke(null)

  private fun initial() =
    compile(
      mapOf(
        "Edited" to "public class Edited { public static String value() { return \"before\"; } }",
        "Caller" to
          "public class Caller { public static String value() { return Edited.value(); } }",
        "Preview" to
          "public class Preview { public static String value() { return Caller.value(); } }",
        "Theme" to "public class Theme { public static String value() { return \"theme\"; } }",
      )
    )

  @Test
  fun `a swap reloads the edited class and its referrers and keeps the rest warm`() {
    initial()
    val holder = holder()
    val base = holder.currentChildLoader()
    assertEquals("before", call(base, "Preview"))
    val theme = base.loadClass("sample.Theme")

    compile(
      mapOf(
        "Edited" to "public class Edited { public static String value() { return \"after\"; } }"
      )
    )
    holder.swap()
    val tip = holder.currentChildLoader()

    assertNotSame(base, tip)
    assertSame(base, tip.parent)
    assertEquals("after", call(tip, "Preview"))
    assertSame(theme, tip.loadClass("sample.Theme"))
    for (reloaded in listOf("Edited", "Caller", "Preview")) {
      assertSame(tip, tip.loadClass("sample.$reloaded").classLoader)
    }
    // Copies are invisible: base and tip classes both report the live output as their code source.
    val live = classes.toURI().toURL()
    assertEquals(live, theme.protectionDomain.codeSource.location)
    assertEquals(live, tip.loadClass("sample.Edited").protectionDomain.codeSource.location)
    // The base still renders what it loaded, for a render already in flight.
    assertEquals("before", call(base, "Preview"))
  }

  @Test
  fun `tips accumulate over the same base`() {
    initial()
    val holder = holder()
    val base = holder.currentChildLoader()
    compile(
      mapOf("Edited" to "public class Edited { public static String value() { return \"one\"; } }")
    )
    holder.swap()
    assertEquals("one", call(holder.currentChildLoader(), "Preview"))

    compile(
      mapOf("Theme" to "public class Theme { public static String value() { return \"two\"; } }")
    )
    holder.swap()
    val second = holder.currentChildLoader()
    assertSame(base, second.parent)
    assertEquals("one", call(second, "Preview"))
    assertEquals("two", call(second, "Theme"))
  }

  @Test
  fun `an unchanged output reuses the base`() {
    initial()
    val holder = holder()
    val base = holder.currentChildLoader()
    holder.swap()
    assertSame(base, holder.currentChildLoader())
  }

  @Test
  fun `a deleted class is not served from the base`() {
    initial()
    val holder = holder()
    holder.currentChildLoader().loadClass("sample.Theme")
    assertEquals(true, classes.resolve("sample/Theme.class").delete())
    holder.swap()
    assertThrows(ClassNotFoundException::class.java) {
      holder.currentChildLoader().loadClass("sample.Theme")
    }
  }

  @Test
  fun `a changed resource replaces the base`() {
    initial()
    classes.resolve("strings.txt").writeText("before")
    val holder = holder()
    val base = holder.currentChildLoader()
    classes.resolve("strings.txt").writeText("after!")
    holder.swap()
    val fresh = holder.currentChildLoader()
    assertNotSame(base, fresh)
    assertNotSame(base.loadClass("sample.Theme"), fresh.loadClass("sample.Theme"))
    assertEquals("after!", fresh.getResourceAsStream("strings.txt")!!.bufferedReader().readText())
  }

  @Test
  fun `without tip reload every swap replaces all user classes`() {
    initial()
    val holder = UserClassLoaderHolder(listOf(classes.toURI().toURL())).apply { tipReload = false }
    val first = holder.currentChildLoader()
    holder.swap()
    val second = holder.currentChildLoader()
    assertNotSame(first.loadClass("sample.Theme"), second.loadClass("sample.Theme"))
  }

  @Test
  fun `class references include descriptors and the closure is transitive`() {
    initial()
    val caller = File(classes, "sample/Caller.class").readBytes()
    assertEquals(true, "sample/Edited" in ClassReferences.of(caller)!!)
    val closure =
      UserClassDirectories.closure(
        setOf("a/Edited"),
        mapOf("a/Edited" to setOf("a/Caller"), "a/Caller" to setOf("a/Preview")),
      )
    assertEquals(setOf("a/Edited", "a/Caller", "a/Preview"), closure)
  }
}
