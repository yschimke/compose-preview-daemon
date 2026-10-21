package ee.schimke.composeai.daemon.warm.impl

import java.io.File
import java.security.MessageDigest
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

/**
 * The module's compiled classes, grouped by the source file that produced them: the key is the
 * package directory plus the class file's `SourceFile` attribute (`bench/Screen7.kt`), which is
 * unique within a module and needs no source path.
 */
internal class ModuleClasses
private constructor(
  private val root: File,
  private val bySource: MutableMap<String, MutableSet<String>>,
) {
  /** Class files (relative to [root]) a source produced, or null when no class came from it. */
  fun classesOf(sourceKey: String): Set<String>? = bySource[sourceKey]

  /** Replaces what [sourceKey] produced: deletes the stale class files, copies the new ones in. */
  fun install(sourceKey: String, staging: File, newClasses: Set<String>) {
    val old = bySource[sourceKey].orEmpty()
    for (stale in old - newClasses) File(root, stale).delete()
    for (relative in newClasses) {
      val target = File(root, relative)
      target.parentFile.mkdirs()
      File(staging, relative).copyTo(target, overwrite = true)
    }
    bySource[sourceKey] = newClasses.toMutableSet()
  }

  companion object {
    fun scan(root: File): ModuleClasses {
      val bySource = HashMap<String, MutableSet<String>>()
      if (root.isDirectory) {
        root
          .walkTopDown()
          .filter { it.isFile && it.name.endsWith(".class") }
          .forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            sourceKey(relative, file.readBytes())?.let {
              bySource.getOrPut(it) { HashSet() }.add(relative)
            }
          }
      }
      return ModuleClasses(root, bySource)
    }

    /** `pkg/dir/File.kt` for a class file at [relativePath], or null when it names no source. */
    fun sourceKey(relativePath: String, bytes: ByteArray): String? {
      var source: String? = null
      ClassReader(bytes)
        .accept(
          object : ClassVisitor(Opcodes.ASM9) {
            override fun visitSource(file: String?, debug: String?) {
              source = file
            }
          },
          // Not SKIP_DEBUG: that would skip the SourceFile attribute too.
          ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
      val name = source ?: return null
      val dir = relativePath.substringBeforeLast('/', "")
      return if (dir.isEmpty()) name else "$dir/$name"
    }
  }
}

/**
 * A cheap fingerprint of every class file under a directory: count, total size and newest
 * modification time. Detects a Gradle build or a Build Tools API compile writing classes behind the
 * warm compiler's back, which invalidates its cached library session.
 */
internal fun classesFingerprint(root: File): String {
  var count = 0L
  var size = 0L
  var newest = 0L
  var hash = 0L
  if (root.isDirectory) {
    root
      .walkTopDown()
      .filter { it.isFile && it.name.endsWith(".class") }
      .forEach {
        count++
        val length = it.length()
        val modified = it.lastModified()
        size += length
        if (modified > newest) newest = modified
        // Order-independent mix so a same-size, same-mtime swap of two files still differs.
        hash += (it.path.hashCode().toLong() * 31 + length) * 31 + modified
      }
  }
  return "$count:$size:$newest:$hash"
}

/** What a class file exposes to other source files: Kotlin metadata and non-private members. */
internal object ClassAbi {

  /** SHA-256 of [bytes]' ABI, or null when no other source file can reference the class. */
  fun of(bytes: ByteArray): String? {
    val visitor = AbiVisitor()
    ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
    if (visitor.unreferenceable) return null
    val digest = MessageDigest.getInstance("SHA-256")
    for (line in visitor.lines.sorted()) {
      digest.update(line.toByteArray())
      digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Kotlin metadata kind (`k`) of a class: 4 is a multi-file facade, 5 a multi-file part. Neither
   * can be recompiled one file at a time, since the facade aggregates every part.
   */
  fun isMultifile(bytes: ByteArray): Boolean {
    var kind = 0
    ClassReader(bytes)
      .accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            if (descriptor != "Lkotlin/Metadata;") null
            else
              object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                  if (name == "k" && value is Int) kind = value
                }
              }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
      )
    return kind == 4 || kind == 5
  }

  private class AbiVisitor : ClassVisitor(Opcodes.ASM9) {
    val lines = ArrayList<String>()
    var unreferenceable = false
    private var name = ""

    override fun visit(
      version: Int,
      access: Int,
      name: String,
      signature: String?,
      superName: String?,
      interfaces: Array<out String>?,
    ) {
      this.name = name
      if (access and Opcodes.ACC_SYNTHETIC != 0) unreferenceable = true
      // Compose's per-file holder of singleton lambdas: only its own file's code reaches it.
      if (name.substringAfterLast('/').startsWith("ComposableSingletons$")) unreferenceable = true
      lines +=
        "class ${access and Opcodes.ACC_SUPER.inv()} $name $signature $superName ${interfaces?.joinToString()}"
    }

    override fun visitInnerClass(
      name: String,
      outerName: String?,
      innerName: String?,
      access: Int,
    ) {
      // A lambda or local class (no simple name) cannot be named from another file.
      if (name == this.name && innerName == null) unreferenceable = true
      if (name == this.name) lines += "inner ${access and Opcodes.ACC_PRIVATE}"
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor =
      recording("annotation $descriptor")

    override fun visitField(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      value: Any?,
    ): FieldVisitor? {
      if (access and Opcodes.ACC_PRIVATE == 0) {
        lines += "field $access $name $descriptor $signature $value"
      }
      return null
    }

    override fun visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String>?,
    ): MethodVisitor? {
      if (access and Opcodes.ACC_PRIVATE == 0) {
        lines += "method $access $name $descriptor $signature ${exceptions?.joinToString()}"
      }
      return null
    }

    private fun recording(prefix: String): AnnotationVisitor =
      object : AnnotationVisitor(Opcodes.ASM9) {
        private val values = ArrayList<String>()

        override fun visit(name: String?, value: Any?) {
          values += "$name=${render(value)}"
        }

        override fun visitEnum(name: String?, descriptor: String?, value: String?) {
          values += "$name=$descriptor.$value"
        }

        override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor =
          recording("$prefix/$name@$descriptor")

        override fun visitArray(name: String?): AnnotationVisitor = recording("$prefix/$name[]")

        override fun visitEnd() {
          lines += "$prefix ${values.joinToString(",")}"
        }
      }

    private fun render(value: Any?): String =
      when (value) {
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is Type -> value.descriptor
        else -> value.toString()
      }
  }
}
