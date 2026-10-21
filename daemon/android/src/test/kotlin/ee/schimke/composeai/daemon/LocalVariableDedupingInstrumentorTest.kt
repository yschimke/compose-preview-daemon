package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.robolectric.internal.bytecode.ClassDetails
import org.robolectric.internal.bytecode.ClassInstrumentor
import org.robolectric.internal.bytecode.ClassNodeProvider
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * Robolectric's own instrumentation writes duplicate `LocalVariableTable` rows into a class whose
 * prebuilt bytes have none, and the sandbox defines what it writes (compose-ai-tools#6161).
 *
 * The fixture is the shape of Wear Compose Material's `Shapes` default-arguments constructor: it
 * inlines the same getter twice, each with its own `$i$f$getDp` row at a different offset, before
 * delegating to `this(...)`. Valid as written; instrumented, both rows land at start 0, length 0.
 */
class LocalVariableDedupingInstrumentorTest {

  @Test
  fun `the fixture is valid before instrumentation`() {
    define(shapes())
  }

  @Test
  fun `the stock instrumentor writes rows the JVM rejects`() {
    try {
      define(ClassInstrumentor().instrument(ClassDetails(shapes()), CONFIG, NO_CLASSES))
      fail("expected ClassFormatError for a duplicated LocalVariableTable entry")
    } catch (e: ClassFormatError) {
      assertTrue(e.message, e.message!!.contains("Duplicated LocalVariableTable"))
    }
  }

  @Test
  fun `the daemon's instrumentor writes a class the JVM defines`() {
    define(
      LocalVariableDedupingInstrumentor().instrument(ClassDetails(shapes()), CONFIG, NO_CLASSES)
    )
  }

  /** Parses [bytes] as a class: `ClassFormatError` is raised here, before any linking. */
  private fun define(bytes: ByteArray): Class<*> =
    object : ClassLoader(javaClass.classLoader) {
        fun define(): Class<*> = defineClass(FIXTURE, bytes, 0, bytes.size)
      }
      .define()

  private companion object {
    const val FIXTURE = "lvtfixture.Shapes"
    val INTERNAL = FIXTURE.replace('.', '/')

    val CONFIG: InstrumentationConfiguration =
      InstrumentationConfiguration.newBuilder().addInstrumentedPackage("lvtfixture").build()

    val NO_CLASSES =
      object : ClassNodeProvider() {
        override fun getClassBytes(internalClassName: String): ByteArray =
          throw ClassNotFoundException(internalClassName)
      }

    /**
     * `class Shapes(val small: Int, val large: Int)` plus the delegating constructor `Shapes(mask:
     * Int)`, which computes both arguments through an inlined getter — one `$i$f$getDp` row per
     * inlining — before calling `this(small, large)`.
     */
    fun shapes(): ByteArray {
      val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INTERNAL, null, "java/lang/Object", null)
      cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "small", "I", null, null).visitEnd()
      cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "large", "I", null, null).visitEnd()

      cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null).apply {
        visitCode()
        visitVarInsn(Opcodes.ALOAD, 0)
        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ILOAD, 1)
        visitFieldInsn(Opcodes.PUTFIELD, INTERNAL, "small", "I")
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ILOAD, 2)
        visitFieldInsn(Opcodes.PUTFIELD, INTERNAL, "large", "I")
        visitInsn(Opcodes.RETURN)
        visitMaxs(0, 0)
        visitEnd()
      }

      cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null).apply {
        visitCode()
        // Two inlinings of the same getter, each with its own row for the inline marker in slot 3.
        fun inlinedGetter(value: Int, into: Int) {
          val start = Label()
          val end = Label()
          visitInsn(Opcodes.ICONST_0)
          visitVarInsn(Opcodes.ISTORE, 3)
          visitLabel(start)
          visitIntInsn(Opcodes.BIPUSH, value)
          visitVarInsn(Opcodes.ISTORE, into)
          visitLabel(end)
          visitLocalVariable("\$i\$f\$getDp", "I", null, start, end, 3)
        }
        inlinedGetter(4, 2)
        visitInsn(Opcodes.NOP)
        inlinedGetter(26, 4)
        visitVarInsn(Opcodes.ALOAD, 0)
        visitVarInsn(Opcodes.ILOAD, 2)
        visitVarInsn(Opcodes.ILOAD, 4)
        visitMethodInsn(Opcodes.INVOKESPECIAL, INTERNAL, "<init>", "(II)V", false)
        visitInsn(Opcodes.RETURN)
        visitMaxs(0, 0)
        visitEnd()
      }
      cw.visitEnd()
      return cw.toByteArray()
    }
  }
}
