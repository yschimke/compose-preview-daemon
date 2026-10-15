package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.ConsumerClock
import ee.schimke.composeai.renderer.PreviewClock
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Pins the wall clock the user module's **own** classes read when the daemon loads them through
 * [UserClassLoaderHolder]'s child loader (compose-ai-tools#5945).
 *
 * ## Why the daemon needs this as well as instrumentation
 *
 * [SandboxHoldingRunner] adds [ConsumerClock.packages] to Robolectric's instrumented packages and
 * registers [ee.schimke.composeai.renderer.ShadowConsumerClock]. That reaches every consumer class
 * the **sandbox** loader defines. But with `composeai.daemon.userClassDirs` set — the hot-reload
 * path every launch descriptor takes — the module's own classes are defined by the disposable child
 * `URLClassLoader` instead, and Robolectric never sees their bytes. Their
 * `System.currentTimeMillis()` call sites stay pointed at `java.lang.System`, the host clock.
 *
 * So the child rewrites them itself: each `invokestatic java/lang/System.currentTimeMillis()J` in a
 * class matching [ConsumerClock.packages] becomes `invokestatic
 * ee/schimke/composeai/renderer/PreviewClock.currentTimeMillis()J`. Same descriptor, same stack
 * effect, so no frame or max-stack recomputation is needed and the rest of the class is copied
 * through untouched. [PreviewClock] resolves through the child's parent (the sandbox loader) — the
 * same copy the shadow calls — and honours `composeai.render.fixedTime`, `off` included.
 *
 * Unlike the instrumented path there is no caller check to make: only consumer classes ever pass
 * through here. `System.nanoTime()` is left alone, matching what the shadow hands a consumer.
 */
internal class ConsumerClockRewriter(private val packages: List<String>) : UserClassTransformer {

  override fun appliesTo(className: String): Boolean =
    ConsumerClock.isConsumerClass(className, packages)

  override fun transform(className: String, bytes: ByteArray): ByteArray {
    val reader = ClassReader(bytes)
    // Passing the reader lets ASM copy every method it does not need to touch verbatim, constant
    // pool included; flags 0 because the rewrite never changes stack shape.
    val writer = ClassWriter(reader, 0)
    var rewritten = false
    reader.accept(
      object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitMethod(
          access: Int,
          name: String?,
          descriptor: String?,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor {
          val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
          return object : MethodVisitor(Opcodes.ASM9, delegate) {
            override fun visitMethodInsn(
              opcode: Int,
              owner: String?,
              name: String?,
              descriptor: String?,
              isInterface: Boolean,
            ) {
              if (
                opcode == Opcodes.INVOKESTATIC &&
                  owner == SYSTEM &&
                  name == CURRENT_TIME_MILLIS &&
                  descriptor == LONG_NO_ARGS
              ) {
                rewritten = true
                super.visitMethodInsn(
                  opcode,
                  PREVIEW_CLOCK,
                  CURRENT_TIME_MILLIS,
                  LONG_NO_ARGS,
                  false,
                )
              } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
              }
            }
          }
        }
      },
      0,
    )
    return if (rewritten) writer.toByteArray() else bytes
  }

  companion object {
    private const val SYSTEM = "java/lang/System"
    private const val CURRENT_TIME_MILLIS = "currentTimeMillis"
    private const val LONG_NO_ARGS = "()J"
    private val PREVIEW_CLOCK: String = PreviewClock::class.java.name.replace('.', '/')

    /**
     * The transformer for the current [ConsumerClock.PROPERTY], or `null` when it names no packages
     * — so an unconfigured daemon's child loader defines classes exactly as compiled.
     */
    fun fromSystemProperties(): ConsumerClockRewriter? =
      ConsumerClock.packages().takeIf { it.isNotEmpty() }?.let(::ConsumerClockRewriter)
  }
}
