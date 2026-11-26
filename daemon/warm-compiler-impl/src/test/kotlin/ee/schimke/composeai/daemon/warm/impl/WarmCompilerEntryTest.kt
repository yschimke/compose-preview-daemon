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
import org.junit.Assume.assumeFalse
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
    assumeFalse(
      "this compiler is one the jar refuses",
      System.getProperty("warm.test.expectRefused").toBoolean(),
    )
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
    entry = newEntry(composeRuntime)
  }

  private fun newEntry(
    compileClasspath: List<String>,
    classesDir: File = classes,
    moduleName: String = "demo",
    jvmTarget: String? = null,
  ): AutoCloseable {
    val config =
      buildMap<String, Any?> {
        put("compileClasspath", compileClasspath)
        put("classesDir", classesDir.path)
        put("moduleName", moduleName)
        put("stagingDir", File(root, "staging-$moduleName").path)
        if (jvmTarget != null) put("jvmTarget", jvmTarget)
        put("pluginClasspath", composePlugin)
        put("pluginOptions", listOf(pluginOption))
      }
    return loader
      .loadClass("ee.schimke.composeai.daemon.warm.impl.WarmCompilerEntry")
      .getConstructor(Map::class.java)
      .newInstance(config) as AutoCloseable
  }

  @After
  fun tearDown() {
    if (!::entry.isInitialized) return
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
  fun `an edit on cached class-path state installs the same bytecode a stock compile produces`() {
    // The second compile reuses the project, so a plugin registered once per compile (2.3 keeps
    // extensions in the project) must neither run twice nor carry the first compile's settings.
    val screen = write("Screen.kt", screen("red"))
    assertEquals("ok", compile(screen)["status"])
    write("Screen.kt", screen("green"))
    assertEquals("ok", compile(screen)["status"])

    val reference = File(root, "reference")
    stockCompile(src.listFiles()!!.toList(), reference)
    for (name in listOf("demo/ScreenKt.class", "demo/LabelKt.class")) {
      assertArrayEquals(name, File(reference, name).readBytes(), File(classes, name).readBytes())
    }
  }

  @Test
  fun `an edit calling another file's internal function compiles`() {
    // The other file is read from the class directory, not from source: without it as a friend
    // path its internals are invisible ("Cannot access 'Label': it is internal in file").
    write(
      "Label.kt",
      label("plain").replace("@Composable fun Label(", "@Composable internal fun Label("),
    )
    stockCompile(src.listFiles()!!.toList(), classes)
    entry.javaClass.getMethod("invalidate").invoke(entry)

    val screen = write("Screen.kt", screen("red"))
    val result = compile(screen)
    assertEquals(result.toString(), "ok", result["status"])

    val reference = File(root, "reference")
    stockCompile(src.listFiles()!!.toList(), reference)
    assertArrayEquals(
      File(reference, "demo/ScreenKt.class").readBytes(),
      File(classes, "demo/ScreenKt.class").readBytes(),
    )
  }

  @Test
  fun `an internal call compiles when a jar of the module's own classes comes first`() {
    // AGP's unit-test class path, which the daemon compiles against, carries a jar of the module's
    // own classes (`bundle<Variant>ClassesToRuntimeJar`) ahead of everything else. The first copy
    // of
    // a class on the class path wins, so unless the class directory comes first, `Label` resolves
    // from that jar, which is no friend, and its internals are invisible again.
    write(
      "Label.kt",
      label("plain").replace("@Composable fun Label(", "@Composable internal fun Label("),
    )
    stockCompile(src.listFiles()!!.toList(), classes)
    val moduleJar = File(root, "module-classes.jar")
    java.util.jar.JarOutputStream(moduleJar.outputStream()).use { jar ->
      classes
        .walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
          jar.putNextEntry(java.util.jar.JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
          file.inputStream().use { it.copyTo(jar) }
          jar.closeEntry()
        }
    }
    val agpShaped = newEntry(listOf(moduleJar.path) + composeRuntime)
    try {
      val result = compileWith(agpShaped, write("Screen.kt", screen("red")))
      assertEquals(result.toString(), "ok", result["status"])
    } finally {
      agpShaped.close()
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
  fun `compile errors on cached class-path state reach this compile's result`() {
    val screen = write("Screen.kt", screen("red"))
    assertEquals("ok", compile(screen)["status"])
    write("Screen.kt", screen("green").replace("Label(", "Missing("))
    val result = compile(screen)
    assertEquals(result.toString(), "errors", result["status"])
    write("Screen.kt", screen("blue"))
    assertEquals("ok", compile(screen)["status"])
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

  @Test
  fun `a warm-up builds the class-path state without touching the classes`() {
    val before = classes.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
    val warmUp =
      File(root, "warm-up/WarmUp.kt").apply {
        parentFile.mkdirs()
        writeText(
          """
          package warmup
          import androidx.compose.runtime.Composable
          @Composable private fun WarmUp(items: List<String>) { items.forEach { println(it) } }
          """
            .trimIndent(),
        )
      }
    @Suppress("UNCHECKED_CAST")
    val result =
      entry.javaClass.getMethod("warmUp", List::class.java).invoke(entry, listOf(warmUp.path))
        as Map<String, Any?>
    assertEquals(result.toString(), "ok", result["status"])
    val after = classes.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
    assertEquals(before.keys, after.keys)
    for ((path, bytes) in before) assertArrayEquals(path, bytes, after.getValue(path))

    // The edit after it reuses the state and the module index the warm-up built.
    val screen = write("Screen.kt", screen("red"))
    val edit = compile(screen)
    assertEquals("ok", edit["status"])
    assertFalse("index rebuilt after a warm-up", timings(edit).containsKey("index"))
  }

  @Test
  fun `a warm-up file that does not compile reports why and keeps the state`() {
    val broken =
      File(root, "warm-up/Broken.kt").apply {
        parentFile.mkdirs()
        writeText("package warmup\nfun broken(): Int = missing()\n")
      }
    val result = warmUp(broken)
    assertEquals("errors", result["status"])
    assertTrue(result["reason"].toString(), result["reason"].toString().contains("missing"))
    val screen = write("Screen.kt", screen("red"))
    val edit = compile(screen)
    assertEquals("ok", edit["status"])
    assertFalse("index rebuilt after a warm-up", timings(edit).containsKey("index"))
  }

  @Test
  fun `a warm-up with the module's own file installs nothing`() {
    val before = classes.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
    // The file as it stands on disk, edited since the classes were built.
    val screen = write("Screen.kt", screen("green"))
    repeat(3) { assertEquals("ok", warmUp(screen)["status"]) }
    val after = classes.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes() }
    assertEquals(before.keys, after.keys)
    for ((path, bytes) in before) assertArrayEquals(path, bytes, after.getValue(path))

    val edit = compile(write("Screen.kt", screen("red")))
    assertEquals("ok", edit["status"])
    assertFalse("index rebuilt after a warm-up", timings(edit).containsKey("index"))
  }

  @Suppress("UNCHECKED_CAST")
  private fun warmUp(vararg files: File): Map<String, Any?> =
    entry.javaClass.getMethod("warmUp", List::class.java).invoke(entry, files.map { it.path })
      as Map<String, Any?>

  private fun compile(vararg files: File): Map<String, Any?> = compileWith(entry, *files)

  @Test
  fun `jvmTarget 21 compiles edits that inline JVM 21 dependency bytecode and emits class version 65`() {
    // Regression for #505: a module targeting JVM 21 that calls an inline function compiled at
    // JVM 21 failed in-process when the warm compiler hardcoded `-jvm-target 17`.
    val libSrc = File(root, "lib-src/lib").apply { mkdirs() }
    val libClasses = File(root, "lib-classes")
    val libFile =
      File(libSrc, "Labels.kt").apply {
        writeText(
          """
          package lib
          inline fun shout(text: String): String = text.uppercase() + "!"
          """
            .trimIndent(),
        )
      }
    stockCompile(listOf(libFile), libClasses, jvmTarget = "21", moduleName = "lib")

    val appClasses = File(root, "app21-classes")
    val appSrc = File(root, "app21-src/app").apply { mkdirs() }
    fun callerSource(greeting: String) =
      """
      package app
      import androidx.compose.runtime.Composable
      import lib.shout
      @Composable fun Banner(name: String) { println(shout("$greeting " + name)) }
      """
        .trimIndent()
    val bannerFile = File(appSrc, "Banner.kt").apply { writeText(callerSource("hello")) }
    val appCp = listOf(libClasses.path) + composeRuntime
    stockCompile(
      listOf(bannerFile),
      appClasses,
      jvmTarget = "21",
      moduleName = "app",
      classpath = appCp,
    )

    // With jvmTarget = "21", editing the caller stays in process and writes Java 21 bytecode (65).
    val entry21 =
      newEntry(
        compileClasspath = appCp,
        classesDir = appClasses,
        moduleName = "app",
        jvmTarget = "21",
      )
    try {
      bannerFile.writeText(callerSource("bonjour"))
      val okResult = compileWith(entry21, bannerFile)
      assertEquals(okResult.toString(), "ok", okResult["status"])
      val classBytes = File(appClasses, "app/BannerKt.class").readBytes()
      val majorVersion =
        ((classBytes[6].toInt() and 0xFF) shl 8) or (classBytes[7].toInt() and 0xFF)
      assertEquals("expected Java 21 classfile major version (65)", 65, majorVersion)
    } finally {
      entry21.close()
    }

    // With omitted / unrecognized jvmTarget, WarmCompilerEntry falls back to 17 and reproduces the
    // JVM 21 inline error rather than crashing on argument parsing.
    val entryDefault =
      newEntry(
        compileClasspath = appCp,
        classesDir = appClasses,
        moduleName = "app",
        jvmTarget = "not-a-target",
      )
    try {
      bannerFile.writeText(callerSource("hola"))
      val errResult = compileWith(entryDefault, bannerFile)
      assertEquals(errResult.toString(), "errors", errResult["status"])
      @Suppress("UNCHECKED_CAST")
      val messages =
        (errResult["errors"] as List<Map<String, Any?>>).joinToString(" ") {
          it["message"].toString()
        }
      assertTrue(
        "expected JVM target 21/17 inline error, got: $messages",
        "JVM target 21" in messages,
      )
    } finally {
      entryDefault.close()
    }
  }

  private fun compileWith(target: AutoCloseable, vararg files: File): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return target.javaClass
      .getMethod("compile", List::class.java)
      .invoke(target, files.map { it.path }) as Map<String, Any?>
  }

  @Suppress("UNCHECKED_CAST")
  private fun timings(result: Map<String, Any?>) = result["timings"] as Map<String, Long>

  /**
   * Full compile with the stock CLI, standing in for the Gradle build that produced the classes.
   */
  private fun stockCompile(
    sources: List<File>,
    out: File,
    jvmTarget: String = "17",
    moduleName: String = "demo",
    classpath: List<String> = composeRuntime,
  ) {
    val compiler =
      loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler").getConstructor().newInstance()
    val log = ByteArrayOutputStream()
    val args =
      listOf(
        "-classpath",
        classpath.joinToString(File.pathSeparator),
        "-d",
        out.path,
        "-jvm-target",
        jvmTarget,
        "-module-name",
        moduleName,
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
