package ee.schimke.composeai.data.remotecompose

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile

/**
 * Whether code compiled against one Remote Compose line can run against the line on this classpath.
 *
 * Remote Compose is alpha, and nothing on the daemon side pins it: a player or capture path in the
 * connector links against whatever `androidx.compose.remote` the consumer brings. When that line
 * has moved, the first sign used to be a `NoSuchMethodError` from inside a composition — no way to
 * catch it, and nothing naming which side moved (yschimke/wear-m3-catalog#652). This reads the
 * bytecode of the classes that bind to the library *before* they run, resolves every field and
 * method they reference in it against the classes this class loader actually hands out, and names
 * what is missing.
 *
 * **Conservative by construction**, because a false report would refuse a render that works. A
 * reference is reported missing only when its owner class cannot be loaded at all, or loads and
 * reflects cleanly without the member. An owner that loads but cannot be reflected (one of its
 * signatures names an absent class) is skipped, not reported.
 */
public object RemoteComposeLinkage {
  /** The packages whose members are checked: the Remote Compose family and its embedded player. */
  public val LIBRARY_PACKAGES: List<String> =
    listOf(
      "androidx.compose.remote.",
      "androidx.wear.compose.remote.",
      "ee.schimke.composeai.rcembedded.",
    )

  /**
   * Checks every class directly in [packages] (not their subpackages), found next to [anchor], for
   * references into [libraryPackages] that [anchor]'s class loader cannot satisfy.
   *
   * [anchor] locates the classes — its jar or class directory is listed — and supplies the loader
   * the references resolve through, so pass a class from the code being checked.
   */
  public fun check(
    anchor: Class<*>,
    packages: List<String>,
    libraryPackages: List<String> = LIBRARY_PACKAGES,
  ): RemoteComposeLinkageReport {
    val loader = anchor.classLoader ?: ClassLoader.getSystemClassLoader()
    val prefixes = libraryPackages.map { it.replace('.', '/') }
    val owners = HashMap<String, Class<*>?>()
    val missing = sortedSetOf<String>()
    var classes = 0
    var references = 0
    for (resource in packages.flatMap { classResources(anchor, it) }) {
      val bytes = loader.getResourceAsStream(resource)?.use { it.readBytes() } ?: continue
      classes++
      for (ref in memberReferences(bytes)) {
        if (prefixes.none { ref.owner.startsWith(it) }) continue
        references++
        val owner =
          owners.getOrPut(ref.owner) {
            try {
              Class.forName(ref.owner.replace('/', '.'), false, loader)
            } catch (_: ClassNotFoundException) {
              null
            } catch (_: LinkageError) {
              null
            }
          }
        if (owner == null) {
          missing += "${ref.owner} (class not found)"
          continue
        }
        if (declares(owner, ref) == false) missing += "${ref.owner}.${ref.name}${ref.descriptor}"
      }
    }
    return RemoteComposeLinkageReport(classes, references, missing.toList())
  }

  /** The `.class` resources directly in [packageName], listed from wherever [anchor] was loaded. */
  private fun classResources(anchor: Class<*>, packageName: String): List<String> {
    val dir = packageName.replace('.', '/')
    val anchorResource = anchor.name.replace('.', '/') + ".class"
    val url: URL = anchor.classLoader?.getResource(anchorResource) ?: return emptyList()
    val names: List<String> =
      when (url.protocol) {
        "jar" ->
          (url.openConnection() as JarURLConnection).jarFileURL.let { jarUrl ->
            JarFile(File(jarUrl.toURI())).use { jar ->
              jar.entries().asSequence().map { it.name }.toList()
            }
          }
        "file" -> {
          val root =
            File(url.toURI()).path.removeSuffix(anchorResource.replace('/', File.separatorChar))
          File(root, dir).listFiles().orEmpty().map { "$dir/${it.name}" }
        }
        else -> emptyList()
      }
    return names.filter {
      it.startsWith("$dir/") && it.endsWith(".class") && '/' !in it.removePrefix("$dir/")
    }
  }

  /**
   * True or false when [owner] (or a supertype) does or does not declare [ref]; null when
   * reflection itself fails, which says nothing about the member.
   */
  private fun declares(owner: Class<*>, ref: MemberReference): Boolean? =
    try {
      val queue = ArrayDeque<Class<*>>().apply { add(owner) }
      val seen = HashSet<Class<*>>()
      var found = false
      while (queue.isNotEmpty() && !found) {
        val type = queue.removeFirst()
        if (!seen.add(type)) continue
        found =
          when {
            ref.isField ->
              type.declaredFields.any {
                it.name == ref.name && descriptor(it.type) == ref.descriptor
              }
            ref.name == "<init>" ->
              type == owner &&
                type.declaredConstructors.any {
                  methodDescriptor(it.parameterTypes, Void.TYPE) == ref.descriptor
                }
            else ->
              type.declaredMethods.any {
                it.name == ref.name &&
                  methodDescriptor(it.parameterTypes, it.returnType) == ref.descriptor &&
                  (type == owner || !Modifier.isPrivate(it.modifiers))
              }
          }
        type.superclass?.let(queue::add)
        queue.addAll(type.interfaces)
        // An interface's Object methods (hashCode, equals, …) resolve to Object's.
        if (type.isInterface) queue.add(Any::class.java)
      }
      found
    } catch (_: LinkageError) {
      null
    } catch (_: SecurityException) {
      null
    }

  private fun methodDescriptor(parameters: Array<Class<*>>, returnType: Class<*>): String =
    parameters.joinToString("", "(", ")") { descriptor(it) } + descriptor(returnType)

  private fun descriptor(type: Class<*>): String =
    when {
      type == Void.TYPE -> "V"
      type == Integer.TYPE -> "I"
      type == java.lang.Boolean.TYPE -> "Z"
      type == java.lang.Long.TYPE -> "J"
      type == java.lang.Float.TYPE -> "F"
      type == java.lang.Double.TYPE -> "D"
      type == java.lang.Short.TYPE -> "S"
      type == java.lang.Byte.TYPE -> "B"
      type == Character.TYPE -> "C"
      type.isArray -> "[" + descriptor(type.componentType)
      else -> "L" + type.name.replace('.', '/') + ";"
    }

  private class MemberReference(
    val owner: String,
    val name: String,
    val descriptor: String,
    val isField: Boolean,
  )

  /** Every `Fieldref` / `Methodref` / `InterfaceMethodref` in a class file's constant pool. */
  private fun memberReferences(classFile: ByteArray): List<MemberReference> {
    val input = DataInputStream(ByteArrayInputStream(classFile))
    if (input.readInt() != 0xCAFEBABE.toInt()) throw IOException("not a class file")
    input.skipBytes(4)
    val count = input.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(count)
    val first = IntArray(count)
    val second = IntArray(count)
    val tags = IntArray(count)
    var i = 1
    while (i < count) {
      val tag = input.readUnsignedByte()
      tags[i] = tag
      when (tag) {
        1 -> utf8[i] = input.readUTF()
        3,
        4 -> input.skipBytes(4)
        5,
        6 -> {
          input.skipBytes(8)
          i++
        }
        7,
        8,
        16,
        19,
        20 -> first[i] = input.readUnsignedShort()
        9,
        10,
        11,
        12,
        17,
        18 -> {
          first[i] = input.readUnsignedShort()
          second[i] = input.readUnsignedShort()
        }
        15 -> input.skipBytes(3)
        else -> throw IOException("unknown constant pool tag $tag")
      }
      i++
    }
    val refs = ArrayList<MemberReference>()
    for (index in 1 until count) {
      val tag = tags[index]
      if (tag != 9 && tag != 10 && tag != 11) continue
      val owner = utf8[first[first[index]]] ?: continue
      val nameAndType = second[index]
      val name = utf8[first[nameAndType]] ?: continue
      val descriptor = utf8[second[nameAndType]] ?: continue
      refs += MemberReference(owner, name, descriptor, isField = tag == 9)
    }
    return refs
  }
}

/** What [RemoteComposeLinkage.check] found. */
public class RemoteComposeLinkageReport(
  /** How many class files were read. Zero means nothing was found to check. */
  public val classes: Int,
  /** How many references into the library were resolved. */
  public val references: Int,
  /** The references this classpath cannot satisfy, as `owner.name descriptor`. */
  public val missing: List<String>,
) {
  public val isLinked: Boolean
    get() = missing.isEmpty()

  override fun toString(): String =
    if (isLinked) "linked ($references references in $classes classes)"
    else "${missing.size} of $references references missing: ${missing.joinToString()}"
}
