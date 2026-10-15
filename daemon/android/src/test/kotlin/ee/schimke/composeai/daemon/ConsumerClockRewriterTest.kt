package ee.schimke.composeai.daemon

import com.example.daemonclock.ClockReader
import ee.schimke.composeai.renderer.ConsumerClock
import ee.schimke.composeai.renderer.PreviewClock
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's child-loader half of consumer clock pinning (compose-ai-tools#5945): a user class
 * the disposable [UserClassLoaderHolder] child defines is never seen by Robolectric, so its
 * `System.currentTimeMillis()` is pinned by [ConsumerClockRewriter] instead. Pure JVM — the child
 * here is parented to the test's own loader, which is all the rewrite needs to resolve
 * [PreviewClock].
 */
class ConsumerClockRewriterTest {

  @After
  fun restoreGlobals() {
    System.clearProperty(ConsumerClock.PROPERTY)
    System.clearProperty(PreviewClock.PROPERTY)
  }

  @Test
  fun `a child-loaded consumer class reads the pinned instant at any host time`() {
    val reader = childLoadedClockReader(ConsumerClockRewriter(listOf(CONSUMER)))

    val first = reader.call("now")
    Thread.sleep(20)
    assertEquals(PreviewClock.pinnedTimeMillis()!!, first)
    assertEquals(first, reader.call("now"))
  }

  @Test
  fun `fixedTime off hands the child-loaded consumer the host clock`() {
    System.setProperty(PreviewClock.PROPERTY, "off")
    val reader = childLoadedClockReader(ConsumerClockRewriter(listOf(CONSUMER)))

    val before = System.currentTimeMillis()
    assertTrue(reader.call("now") - before in 0..60_000)
  }

  @Test
  fun `nanoTime is left on the host clock`() {
    val reader = childLoadedClockReader(ConsumerClockRewriter(listOf(CONSUMER)))

    assertTrue(abs(reader.call("nanoNow") - System.nanoTime()) < 1e9)
  }

  @Test
  fun `a class outside the configured packages is defined as compiled`() {
    val rewriter = ConsumerClockRewriter(listOf("com.example.other."))

    assertFalse(rewriter.appliesTo(ClockReader::class.java.name))
    val reader = childLoadedClockReader(rewriter)
    val before = System.currentTimeMillis()
    assertTrue(reader.call("now") - before in 0..60_000)
  }

  @Test
  fun `a class with no clock read comes back as the same bytes`() {
    val rewriter = ConsumerClockRewriter(listOf("ee.schimke.composeai.daemon."))
    val bytes = bytesOf(UserClassTransformer::class.java)

    assertSame(bytes, rewriter.transform(UserClassTransformer::class.java.name, bytes))
  }

  @Test
  fun `no transformer when the property names no packages`() {
    assertNull(ConsumerClockRewriter.fromSystemProperties())
    System.setProperty(ConsumerClock.PROPERTY, " , ")
    assertNull(ConsumerClockRewriter.fromSystemProperties())
    System.setProperty(ConsumerClock.PROPERTY, CONSUMER)
    assertNotNull(ConsumerClockRewriter.fromSystemProperties())
  }

  private class LoadedReader(private val type: Class<*>) {
    fun call(method: String): Long = type.getMethod(method).invoke(null) as Long
  }

  /** Writes [ClockReader]'s compiled bytes to a fresh class dir and loads it through a holder. */
  private fun childLoadedClockReader(transformer: UserClassTransformer): LoadedReader {
    val dir = Files.createTempDirectory("consumer-clock-").toFile().also { it.deleteOnExit() }
    val target = File(dir, ClockReader::class.java.name.replace('.', '/') + ".class")
    target.parentFile.mkdirs()
    target.writeBytes(classBytes())
    val holder =
      UserClassLoaderHolder(
        urls = listOf(dir.toURI().toURL()),
        parentSupplier = { javaClass.classLoader },
        transformer = transformer,
      )
    val loader = holder.currentChildLoader()
    val type = loader.loadClass(ClockReader::class.java.name)
    // Child-first: a copy defined by the parent would make every assertion here vacuous.
    assertSame(loader, type.classLoader)
    assertEquals(dir.toURI().toURL(), type.protectionDomain.codeSource.location)
    return LoadedReader(type)
  }

  private fun classBytes(): ByteArray = bytesOf(ClockReader::class.java)

  private fun bytesOf(type: Class<*>): ByteArray =
    type.getResourceAsStream(type.simpleName + ".class")!!.use { it.readBytes() }

  private companion object {
    const val CONSUMER = "com.example.daemonclock."
  }
}
