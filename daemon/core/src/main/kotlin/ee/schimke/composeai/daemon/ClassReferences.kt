package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * The internal names a class file can link against: its `CONSTANT_Class` entries plus every
 * `Lname;` in a descriptor or signature. Over-approximates (a string literal shaped like a
 * descriptor counts too), which only widens a tip reload, never narrows it.
 */
internal object ClassReferences {

  private val descriptorName = Regex("L([^;<>\\[]+)[;<]")

  /** Referenced internal names, or `null` when [bytes] is not a parseable class file. */
  fun of(bytes: ByteArray): Set<String>? =
    try {
      parse(DataInputStream(ByteArrayInputStream(bytes)))
    } catch (_: Exception) {
      null
    }

  private fun parse(input: DataInputStream): Set<String>? {
    if (input.readInt() != 0xCAFEBABE.toInt()) return null
    input.readUnsignedShort() // minor
    input.readUnsignedShort() // major
    val count = input.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(count)
    val classIndexes = ArrayList<Int>()
    var index = 1
    while (index < count) {
      when (input.readUnsignedByte()) {
        1 -> utf8[index] = input.readUTF()
        7 -> classIndexes += input.readUnsignedShort()
        8,
        16,
        19,
        20 -> input.skipNBytes(2)
        15 -> input.skipNBytes(3)
        3,
        4,
        9,
        10,
        11,
        12,
        17,
        18 -> input.skipNBytes(4)
        5,
        6 -> {
          input.skipNBytes(8)
          index++
        }
        else -> return null
      }
      index++
    }
    val names = HashSet<String>()
    for (classIndex in classIndexes) {
      val name = utf8.getOrNull(classIndex) ?: continue
      if (name.startsWith("[")) descriptorName.findAll(name).forEach { names += it.groupValues[1] }
      else names += name
    }
    for (value in utf8) {
      if (value == null || 'L' !in value) continue
      descriptorName.findAll(value).forEach { names += it.groupValues[1] }
    }
    return names
  }
}
