package ee.schimke.composeai.daemon.warm.impl

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Which compilers of its line a warm compiler jar serves, against the compiler of the run. */
class WarmCompilerVersionTest {

  private fun entryClass(loader: ClassLoader) =
    loader.loadClass("ee.schimke.composeai.daemon.warm.impl.WarmCompilerEntry")

  private fun loader() =
    URLClassLoader(
      (paths("warm.test.compiler") + System.getProperty("warm.test.implJar"))
        .map { File(it).toURI().toURL() }
        .toTypedArray(),
      ClassLoader.getPlatformClassLoader(),
    )

  @Test
  fun `a compiler older than the line's oldest tested one is refused at load`() {
    val refused = System.getProperty("warm.test.expectRefused").toBoolean()
    loader().use { loader ->
      try {
        entryClass(loader).getConstructor(Map::class.java).newInstance(emptyMap<String, Any?>())
        if (refused) fail("the warm compiler loaded on a compiler it should refuse")
      } catch (e: InvocationTargetException) {
        val message = e.targetException.message.orEmpty()
        if (refused) assertTrue(message, message.contains("supports Kotlin"))
        // Otherwise the version check passed and the empty config failed after it.
        else assertTrue(message, !message.contains("supports Kotlin"))
      }
    }
  }

  @Test
  fun `patch releases order by number, pre-releases by kind and number before the release`() {
    loader().use { loader ->
      val isOlder = entryClass(loader).getMethod("isOlder", String::class.java, String::class.java)
      fun older(a: String, b: String) = isOlder.invoke(null, a, b) as Boolean
      assertEquals(true, older("2.4.10", "2.4.20"))
      assertEquals(true, older("2.4.0", "2.4.20"))
      assertEquals(true, older("2.4.20-RC", "2.4.20"))
      assertEquals(false, older("2.4.20", "2.4.20"))
      assertEquals(false, older("2.4.21", "2.4.20"))
      assertEquals(false, older("2.4.30", "2.4.20"))
      assertEquals(true, older("2.5.0-Beta1", "2.5.0-Beta2"))
      assertEquals(true, older("2.5.0-Alpha3", "2.5.0-Beta2"))
      assertEquals(true, older("2.5.0-dev-1234", "2.5.0-Beta2"))
      assertEquals(false, older("2.5.0-Beta2", "2.5.0-Beta2"))
      assertEquals(false, older("2.5.0-RC", "2.5.0-Beta2"))
      assertEquals(false, older("2.5.0", "2.5.0-Beta2"))
      assertEquals(true, older("2.5.0-RC", "2.5.0-RC2"))
    }
  }

  private fun paths(property: String): List<String> =
    System.getProperty(property).split(File.pathSeparator).filter { it.isNotBlank() }
}
