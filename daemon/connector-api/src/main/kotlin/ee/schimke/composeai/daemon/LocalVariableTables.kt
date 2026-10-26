package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Drops duplicate `LocalVariableTable` / `LocalVariableTypeTable` entries from a class file
 * (compose-ai-tools#6161).
 *
 * Some prebuilt AndroidX classes were written by Kotlin compilers that emitted the same entry twice
 * for an inlined getter — `$i$f$getDp` in Wear Compose Material's `Shapes` is the reported one.
 * HotSpot's class file parser rejects that with `ClassFormatError: Duplicated LocalVariableTable
 * attribute entry` while verification is on, so a preview depending on such a class could not
 * render at all. The tables are debug information only; removing the repeated rows changes nothing
 * the class does, and leaves verification on for everything else.
 *
 * A duplicate is what HotSpot calls one: the same start, length, name and slot (the descriptor is
 * not part of its key). The first row is kept. A class with no duplicate is returned as the very
 * same array, so the common case costs one read-only walk of the class file.
 *
 * Duplicates do not only arrive prebuilt. Robolectric's `ClassInstrumentor` moves the code before a
 * constructor's `this(...)` / `super(...)` call into the new `<init>` and leaves the original
 * body's local-variable rows pointing at labels that no longer exist; ASM writes each of them as
 * start 0, length 0, so two inlined `.dp` getters in one Kotlin default-arguments constructor
 * become two identical rows. That is why `:daemon:android` also runs this on Robolectric's output.
 */
public object LocalVariableTables {

  /** [bytes] without duplicate local-variable rows; [bytes] itself when there are none. */
  public fun dedupe(bytes: ByteArray): ByteArray =
    try {
      if (!ClassWalk(bytes, null).walk()) bytes
      else {
        val out = ByteArrayOutputStream(bytes.size)
        ClassWalk(bytes, DataOutputStream(out)).walk()
        out.toByteArray()
      }
    } catch (_: RuntimeException) {
      // Not a class file this walk understands: hand it on unchanged and let the JVM decide.
      bytes
    }

  /**
   * One pass over a class file. With no [out] it only reports whether any table has a duplicate;
   * with one it copies the class to [out], dropping duplicates and fixing the attribute lengths.
   */
  private class ClassWalk(bytes: ByteArray, private val out: DataOutputStream?) {
    private val buf = ByteBuffer.wrap(bytes)
    private val utf8 = HashMap<Int, String>()
    private var found = false

    fun walk(): Boolean {
      copy(8) // magic, minor, major
      val poolCount = u2()
      var index = 1
      while (index < poolCount) {
        val tag = u1()
        when (tag) {
          1 -> {
            val length = u2()
            val start = buf.position()
            utf8[index] = String(buf.array(), start, length, Charsets.UTF_8)
            copy(length)
          }
          3,
          4 -> copy(4)
          5,
          6 -> {
            copy(8)
            index++
          }
          7,
          8,
          16,
          19,
          20 -> copy(2)
          9,
          10,
          11,
          12,
          17,
          18 -> copy(4)
          15 -> copy(3)
          else -> error("constant pool tag $tag")
        }
        index++
      }
      copy(6) // access, this, super
      copy(2 * u2()) // interfaces
      repeat(u2()) { member() } // fields
      repeat(u2()) { member() } // methods
      attributes { _, length -> copy(length) }
      return found
    }

    private fun member() {
      copy(6) // access, name, descriptor
      attributes { name, length -> if (name == "Code") code(length) else copy(length) }
    }

    /** An attribute table, each body handed to [body] (which must consume exactly its length). */
    private fun attributes(body: (name: String?, length: Int) -> Unit) {
      repeat(u2()) {
        val name = utf8[u2()]
        val length = u4Raw()
        if (out == null) {
          body(name, length)
        } else {
          // The body may shrink, so it is written to a side buffer and its length patched.
          val saved = this.target
          val side = ByteArrayOutputStream(length)
          this.target = DataOutputStream(side)
          body(name, length)
          this.target = saved
          target!!.writeInt(side.size())
          side.writeTo(target!!)
        }
      }
    }

    private fun code(length: Int) {
      copy(4) // max_stack, max_locals
      copy(u4())
      copy(8 * u2()) // exception table
      attributes { name, attrLength ->
        if (name == "LocalVariableTable" || name == "LocalVariableTypeTable") table(attrLength)
        else copy(attrLength)
      }
    }

    private fun table(length: Int) {
      val count = buf.short.toInt() and 0xFFFF
      val rows = (0 until count).map { ByteArray(10).also(buf::get) }
      val seen = HashSet<List<Int>>()
      val kept = rows.filter { row ->
        val r = ByteBuffer.wrap(row)
        val start = r.short.toInt() and 0xFFFF
        val len = r.short.toInt() and 0xFFFF
        val name = r.short.toInt() and 0xFFFF
        r.short // descriptor or signature: not part of HotSpot's key
        val slot = r.short.toInt() and 0xFFFF
        seen.add(listOf(start, len, name, slot))
      }
      if (kept.size != rows.size) found = true
      check(length == 2 + 10 * count) { "malformed local variable table" }
      target?.let { o ->
        o.writeShort(kept.size)
        kept.forEach(o::write)
      }
    }

    private var target: DataOutputStream? = out

    private fun u1(): Int = (buf.get().toInt() and 0xFF).also { target?.writeByte(it) }

    private fun u2(): Int = (buf.short.toInt() and 0xFFFF).also { target?.writeShort(it) }

    private fun u4(): Int = buf.int.also { target?.writeInt(it) }

    /** An attribute length: read, not copied — [attributes] writes the (possibly new) one. */
    private fun u4Raw(): Int = buf.int

    private fun copy(count: Int) {
      val start = buf.position()
      buf.position(start + count)
      target?.write(buf.array(), start, count)
    }
  }
}
