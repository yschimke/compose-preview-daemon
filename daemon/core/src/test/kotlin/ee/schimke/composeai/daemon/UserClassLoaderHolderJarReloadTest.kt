package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.function.Supplier
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A jar rewritten at the same path must be read afresh by the next child loader
 * (compose-ai-tools#6140). Bazel rewrites a target's jar in place; the JDK's process-wide jar-URL
 * cache kept the first `JarFile` it opened for that path, so a `URL.openStream()` read of a class
 * after the swap still saw the old bytecode.
 */
class UserClassLoaderHolderJarReloadTest {

  /** Replaces the jar by writing a sibling and renaming it over the original (a new inode). */
  @Test
  fun `transformer path reads a jar replaced at the same path`() {
    assertReloadsEditedJar(transformer = IdentityTransformer, rewrite = ::replaceByRename)
  }

  /** Truncates and rewrites the original file (the same inode). */
  @Test
  fun `transformer path reads a jar overwritten in place`() {
    assertReloadsEditedJar(transformer = IdentityTransformer, rewrite = ::overwriteInPlace)
  }

  @Test
  fun `plain path reads a jar replaced at the same path`() {
    assertReloadsEditedJar(transformer = null, rewrite = ::replaceByRename)
  }

  @Test
  fun `plain path reads a jar overwritten in place`() {
    assertReloadsEditedJar(transformer = null, rewrite = ::overwriteInPlace)
  }

  @Test
  fun `resource streams read a jar replaced at the same path`() {
    val work = Files.createTempDirectory("jar-reload-res-").toFile()
    val jar = File(work, "user.jar")
    writeJar(jar, mapOf(RESOURCE to "Default".toByteArray()))
    val holder = UserClassLoaderHolder(urls = listOf(jar.toURI().toURL()))

    assertEquals("Default", readResource(holder))
    replaceByRename(jar) { writeJar(it, mapOf(RESOURCE to "Edited".toByteArray())) }
    holder.swap()
    assertEquals("Edited", readResource(holder))
  }

  /** The transformer sees the runtime-versioned class of a multi-release jar, as URLClassLoader. */
  @Test
  fun `transformer path loads multi-release versioned classes`() {
    val work = Files.createTempDirectory("jar-reload-mr-class-").toFile()
    val jar = File(work, "user.jar")
    val base = compileGreeting(work, "Default")
    val versioned = compileGreeting(work, "Versioned")
    writeJar(
      jar,
      mapOf(
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nMulti-Release: true\n\n".toByteArray(),
        CLASS_ENTRY to base.getValue(CLASS_ENTRY),
        "META-INF/versions/9/$CLASS_ENTRY" to versioned.getValue(CLASS_ENTRY),
      ),
    )
    val holder =
      UserClassLoaderHolder(
        urls = listOf(jar.toURI().toURL()),
        parentSupplier = { UserClassLoaderHolderJarReloadTest::class.java.classLoader },
        onSwap = null,
        transformer = IdentityTransformer,
      )

    assertEquals("Versioned", greet(holder))
  }

  /** A multi-release jar answers with the runtime-versioned entry, as URLClassLoader would. */
  @Test
  fun `resource streams honour multi-release entries`() {
    val work = Files.createTempDirectory("jar-reload-mr-").toFile()
    val jar = File(work, "user.jar")
    writeJar(
      jar,
      mapOf(
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nMulti-Release: true\n\n".toByteArray(),
        RESOURCE to "Base".toByteArray(),
        "META-INF/versions/9/$RESOURCE" to "Versioned".toByteArray(),
      ),
    )
    val holder = UserClassLoaderHolder(urls = listOf(jar.toURI().toURL()))

    assertEquals("Versioned", readResource(holder))
  }

  private fun assertReloadsEditedJar(
    transformer: UserClassTransformer?,
    rewrite: (File, (File) -> Unit) -> Unit,
  ) {
    val work = Files.createTempDirectory("jar-reload-").toFile()
    val jar = File(work, "user.jar")
    writeJar(jar, compileGreeting(work, "Default"))
    val holder =
      UserClassLoaderHolder(
        urls = listOf(jar.toURI().toURL()),
        parentSupplier = { UserClassLoaderHolderJarReloadTest::class.java.classLoader },
        onSwap = null,
        transformer = transformer,
      )

    assertEquals("Default", greet(holder))

    val edited = compileGreeting(work, "Edited")
    rewrite(jar) { writeJar(it, edited) }
    holder.swap()

    assertEquals("the reloaded loader must see the rewritten jar", "Edited", greet(holder))
  }

  private fun greet(holder: UserClassLoaderHolder): String {
    val loader = holder.currentChildLoader()
    val type = loader.loadClass(CLASS_NAME)
    assertEquals("the child must define the user class itself", loader, type.classLoader)
    @Suppress("UNCHECKED_CAST")
    return (type.getDeclaredConstructor().newInstance() as Supplier<String>).get()
  }

  private fun readResource(holder: UserClassLoaderHolder): String {
    val stream = holder.currentChildLoader().getResourceAsStream(RESOURCE)
    assertNotNull(stream)
    return stream!!.use { it.readBytes().decodeToString() }
  }

  private fun replaceByRename(jar: File, write: (File) -> Unit) {
    val staged = File(jar.parentFile, jar.name + ".tmp")
    write(staged)
    staged.setLastModified(jar.lastModified() + 2_000)
    Files.move(staged.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private fun overwriteInPlace(jar: File, write: (File) -> Unit) {
    val before = jar.lastModified()
    write(jar)
    jar.setLastModified(before + 2_000)
  }

  /** `fixture.Greeting implements Supplier<String>`, returning [value], as `path -> bytes`. */
  private fun compileGreeting(work: File, value: String): Map<String, ByteArray> {
    val src = Files.createTempDirectory(work.toPath(), "src-").toFile()
    val out = Files.createTempDirectory(work.toPath(), "out-").toFile()
    val source = File(src, "fixture/Greeting.java").apply { parentFile.mkdirs() }
    // Padding keeps the two versions' entry sizes apart, so offsets read through a stale central
    // directory cannot line up by accident.
    val padding = if (value == "Default") "" else "x".repeat(64)
    source.writeText(
      """
      package fixture;
      public class Greeting implements java.util.function.Supplier<String> {
        private static final String PAD = "$padding";
        public String get() { return "$value"; }
      }
      """
        .trimIndent()
    )
    val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
    val exit = compiler.run(null, null, null, "-d", out.absolutePath, source.absolutePath)
    assertEquals("javac failed", 0, exit)
    return mapOf(CLASS_ENTRY to File(out, CLASS_ENTRY).readBytes())
  }

  private fun <T> assertNotNull(value: T?): T {
    assertNotNull(null, value)
    return value!!
  }

  private fun writeJar(jar: File, entries: Map<String, ByteArray>) {
    JarOutputStream(jar.outputStream()).use { out ->
      for ((name, bytes) in entries) {
        out.putNextEntry(JarEntry(name))
        out.write(bytes)
        out.closeEntry()
      }
    }
  }

  private object IdentityTransformer : UserClassTransformer {
    override fun appliesTo(className: String): Boolean = className.startsWith("fixture.")

    override fun transform(className: String, bytes: ByteArray): ByteArray = bytes
  }

  private companion object {
    const val CLASS_NAME = "fixture.Greeting"
    const val CLASS_ENTRY = "fixture/Greeting.class"
    const val RESOURCE = "fixture/greeting.txt"
  }
}
