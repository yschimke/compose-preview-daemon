package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/**
 * Duplicate local-variable rows, as some old Kotlin compilers wrote them into prebuilt AndroidX
 * classes (`$i$f$getDp` in Wear Compose Material's `Shapes`), fail a verified class load with
 * `ClassFormatError`. The daemon removes them on both paths a prebuilt class is defined through
 * (compose-ai-tools#6161).
 */
class LocalVariableTablesTest {
  private val root: File = Files.createTempDirectory("lvt").toFile()

  @After fun cleanUp() = root.deleteRecursively().let {}

  @Test
  fun `the JVM rejects the duplicate rows the fixture carries`() {
    try {
      define(classWithDuplicateRows(), FIXTURE)
      fail("expected ClassFormatError for a duplicated LocalVariableTable entry")
    } catch (e: ClassFormatError) {
      assertTrue(e.message, e.message!!.contains("Duplicated LocalVariableTable"))
    }
  }

  @Test
  fun `deduplicated, the class defines and runs`() {
    val deduped = LocalVariableTables.dedupe(classWithDuplicateRows())
    assertEquals(42, define(deduped, FIXTURE).getMethod("answer").invoke(null))
  }

  @Test
  fun `a class with no duplicate is returned as the same array`() {
    val clean = classWithDuplicateRows(duplicate = false)
    assertSame(clean, LocalVariableTables.dedupe(clean))
    assertEquals(42, define(clean, FIXTURE).getMethod("answer").invoke(null))
  }

  @Test
  fun `bytes that are not a class file pass through unchanged`() {
    val junk = byteArrayOf(1, 2, 3)
    assertSame(junk, LocalVariableTables.dedupe(junk))
  }

  @Test
  fun `the app-Compose-first parent serves an app AndroidX class without the duplicates`() {
    val dir = root.resolve("app").apply { writeClass(ANDROIDX_FIXTURE) }
    val loader = AppComposeFirstLoader(listOf(dir.toURI().toURL()), javaClass.classLoader)
    val url = loader.getResource(ANDROIDX_FIXTURE.replace('.', '/') + ".class")!!
    val served = url.openStream().use { it.readBytes() }
    assertNotSame(0, served.size)
    assertEquals(42, define(served, ANDROIDX_FIXTURE).getMethod("answer").invoke(null))
  }

  @Test
  fun `the user-class loader defines a prebuilt class with duplicates instead of failing`() {
    val dir = root.resolve("user").apply { writeClass(FIXTURE) }
    val holder =
      UserClassLoaderHolder(
        urls = listOf(dir.toURI().toURL()),
        parentSupplier = { ClassLoader.getPlatformClassLoader() },
      )
    val cls = holder.currentChildLoader().loadClass(FIXTURE)
    assertEquals(42, cls.getMethod("answer").invoke(null))
  }

  private fun File.writeClass(name: String) {
    resolve(name.replace('.', '/') + ".class").apply {
      parentFile.mkdirs()
      writeBytes(classWithDuplicateRows(name))
    }
  }

  private fun define(bytes: ByteArray, name: String): Class<*> =
    object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
        fun define(): Class<*> = defineClass(name, bytes, 0, bytes.size)
      }
      .define()

  private companion object {
    const val FIXTURE = "fixture.Shapes"
    const val ANDROIDX_FIXTURE = "androidx.lvtfixture.Shapes"

    /** `public static int answer() { int x = 42; return x; }`, with `x`'s row written twice. */
    fun classWithDuplicateRows(
      name: String = FIXTURE,
      duplicate: Boolean = true,
    ): ByteArray {
      val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
      cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        name.replace('.', '/'),
        null,
        "java/lang/Object",
        null,
      )
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "answer", "()I", null, null)
      mv.visitCode()
      val start = Label()
      val end = Label()
      mv.visitIntInsn(Opcodes.BIPUSH, 42)
      mv.visitVarInsn(Opcodes.ISTORE, 0)
      mv.visitLabel(start)
      mv.visitVarInsn(Opcodes.ILOAD, 0)
      mv.visitInsn(Opcodes.IRETURN)
      mv.visitLabel(end)
      mv.visitLocalVariable("\$i\$f\$getDp", "I", null, start, end, 0)
      if (duplicate) mv.visitLocalVariable("\$i\$f\$getDp", "I", null, start, end, 0)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
      cw.visitEnd()
      return cw.toByteArray()
    }
  }
}
