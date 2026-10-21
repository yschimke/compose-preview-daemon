package ee.schimke.composeai.daemon.warm.impl

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [WarmCompilerEntry] the way the daemon does: the compiler and this module's jar in a class
 * loader of their own, the entry reached reflectively through its JDK-typed surface. A small
 * Compose module is compiled with the stock compiler first, as Gradle would have.
 */
class WarmCompilerEntryTest {

  private lateinit var root: File
  private lateinit var src: File
  private lateinit var classes: File
  private lateinit var loader: URLClassLoader
  private lateinit var entry: AutoCloseable

  private val composeRuntime = paths("warm.test.composeRuntime")
  private val composePlugin = paths("warm.test.composePlugin")
  private val pluginOption =
    "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"

  @Before
  fun setUp() {
    root = Files.createTempDirectory("warm-entry").toFile()
    src = File(root, "src/demo").apply { mkdirs() }
    classes = File(root, "classes")
    write("Label.kt", label("plain"))
    write("Screen.kt", screen("blue"))
    loader =
      URLClassLoader(
        (paths("warm.test.compiler") + System.getProperty("warm.test.implJar"))
          .map { File(it).toURI().toURL() }
          .toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
    stockCompile(src.listFiles()!!.toList(), classes)
    entry =
      loader
        .loadClass("ee.schimke.composeai.daemon.warm.impl.WarmCompilerEntry")
        .getConstructor(Map::class.java)
        .newInstance(
          mapOf(
            "compileClasspath" to composeRuntime,
            "classesDir" to classes.path,
            "moduleName" to "demo",
            "stagingDir" to File(root, "staging").path,
            "pluginClasspath" to composePlugin,
            "pluginOptions" to listOf(pluginOption),
          )
        ) as AutoCloseable
  }

  @After
  fun tearDown() {
    entry.close()
    loader.close()
    root.deleteRecursively()
  }

  @Test
  fun `a body edit installs the same bytecode a full stock compile produces`() {
    val screen = write("Screen.kt", screen("red"))
    assertEquals("ok", compile(screen)["status"])

    val reference = File(root, "reference")
    stockCompile(src.listFiles()!!.toList(), reference)
    for (name in listOf("demo/ScreenKt.class", "demo/LabelKt.class")) {
      assertArrayEquals(name, File(reference, name).readBytes(), File(classes, name).readBytes())
    }
  }

  @Test
  fun `repeated edits reuse the class-path state and the module index`() {
    val screen = write("Screen.kt", screen("red"))
    assertEquals("ok", compile(screen)["status"])
    write("Screen.kt", screen("green"))
    val second = compile(screen)
    assertEquals("ok", second["status"])
    assertFalse("index rebuilt on a warm compile", timings(second).containsKey("index"))
  }

  @Test
  fun `an ABI change is refused and leaves the classes untouched`() {
    val before = File(classes, "demo/LabelKt.class").readBytes()
    val label = write("Label.kt", label("plain") + "\nfun extra(): Int = 1\n")
    val result = compile(label)
    assertEquals("unsupported", result["status"])
    assertTrue(result["reason"].toString(), result["reason"].toString().startsWith("ABI changed"))
    assertArrayEquals(before, File(classes, "demo/LabelKt.class").readBytes())
  }

  @Test
  fun `a new source file is refused`() {
    val extra = write("Extra.kt", "package demo\nfun extraValue() = 2\n")
    val result = compile(extra)
    assertEquals("unsupported", result["status"])
    assertTrue(result["reason"].toString().contains("new source file"))
  }

  @Test
  fun `a non-private inline function is refused`() {
    val label =
      write("Label.kt", label("plain").replace("fun labelSuffix()", "inline fun labelSuffix()"))
    stockCompile(src.listFiles()!!.toList(), classes)
    entry.javaClass.getMethod("invalidate").invoke(entry)
    val result = compile(label)
    assertEquals("unsupported", result["status"])
    assertTrue(result["reason"].toString().contains("inline"))
  }

  @Test
  fun `compile errors come back with their location`() {
    val screen = write("Screen.kt", screen("red").replace("Label(", "Missing("))
    val result = compile(screen)
    assertEquals("errors", result["status"])
    @Suppress("UNCHECKED_CAST") val errors = result["errors"] as List<Map<String, Any?>>
    assertTrue(errors.isNotEmpty())
    assertEquals(screen.path, errors.first()["file"])
    assertTrue((errors.first()["line"] as Int) > 0)
  }

  @Test
  fun `classes rewritten behind its back drop the warm state`() {
    val screen = write("Screen.kt", screen("red"))
    assertEquals("ok", compile(screen)["status"])

    // A Gradle build changes Label's ABI; the cached library session must not keep the old one.
    write(
      "Label.kt",
      label("plain").replace("fun Label(text: String)", "fun Label(text: String, size: Int = 1)"),
    )
    stockCompile(src.listFiles()!!.toList(), classes)
    write("Screen.kt", screen("green").replace("Label(\"green\")", "Label(\"green\", size = 2)"))
    val result = compile(screen)
    assertEquals(result.toString(), "ok", result["status"])
    assertTrue(timings(result).containsKey("index"))
  }

  private fun compile(vararg files: File): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return entry.javaClass
      .getMethod("compile", List::class.java)
      .invoke(entry, files.map { it.path }) as Map<String, Any?>
  }

  @Suppress("UNCHECKED_CAST")
  private fun timings(result: Map<String, Any?>) = result["timings"] as Map<String, Long>

  /**
   * Full compile with the stock CLI, standing in for the Gradle build that produced the classes.
   */
  private fun stockCompile(sources: List<File>, out: File) {
    val compiler =
      loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler").getConstructor().newInstance()
    val log = ByteArrayOutputStream()
    val args =
      listOf(
        "-classpath",
        composeRuntime.joinToString(File.pathSeparator),
        "-d",
        out.path,
        "-jvm-target",
        "17",
        "-module-name",
        "demo",
        "-no-stdlib",
        "-no-reflect",
        "-Xplugin=${composePlugin.joinToString(",")}",
        "-P",
        pluginOption,
      ) + sources.map { it.path }
    val code =
      compiler.javaClass
        .getMethod("exec", PrintStream::class.java, Array<String>::class.java)
        .invoke(compiler, PrintStream(log), args.toTypedArray())
    assertEquals(log.toString(), "OK", code.toString())
  }

  private fun write(name: String, text: String) = File(src, name).apply { writeText(text) }

  private fun label(variant: String) =
    """
    package demo
    import androidx.compose.runtime.Composable
    @Composable fun Label(text: String) { println(text + labelSuffix()) }
    fun labelSuffix(): String = "$variant"
    """
      .trimIndent()

  private fun screen(color: String) =
    """
    package demo
    import androidx.compose.runtime.Composable
    @Composable fun Screen() {
      Label("$color")
      listOf(1, 2).forEach { println(it) }
    }
    """
      .trimIndent()

  private fun paths(property: String): List<String> =
    System.getProperty(property).split(File.pathSeparator).filter { it.isNotBlank() }
}
