package ee.schimke.composeai.daemon

import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserClassLoaderGenerationTest {
  @Test
  fun `failed compilation cannot delete classes and resources from a loaded generation`() {
    val root = Files.createTempDirectory("preview-generation-test").toFile()
    try {
      val classes = root.resolve("classes").apply { mkdirs() }
      val source = root.resolve("Later.java")
      fun compile(value: String) {
        source.writeText(
          "package sample; public class Later { public static String value() { return \"$value\"; } }"
        )
        assertEquals(
          0,
          ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", classes.path, source.path),
        )
        classes.resolve("message.txt").writeText(value)
      }
      compile("before")
      val holder = UserClassLoaderHolder(listOf(classes.toURI().toURL())).apply { tipReload = true }
      val before = holder.currentChildLoader()
      before.use {
        // No user class has loaded yet. A failed incremental compiler removes the originals.
        assertTrue(classes.deleteRecursively())
        assertEquals("before", before.loadClass("sample.Later").getMethod("value").invoke(null))
        assertEquals(
          "before",
          before.getResourceAsStream("message.txt")!!.bufferedReader().use { it.readText() },
        )

        compile("after")
        holder.swap()
        holder.currentChildLoader().use { after ->
          assertEquals("after", after.loadClass("sample.Later").getMethod("value").invoke(null))
          assertEquals(
            "after",
            after.getResourceAsStream("message.txt")!!.bufferedReader().use { it.readText() },
          )
          assertEquals(
            "before",
            before.getResourceAsStream("message.txt")!!.bufferedReader().use { it.readText() },
          )
          assertEquals(listOf(classes.toURI().toURL()), holder.urls())
        }
        // Closing the new loader must not remove the retired generation still in use.
        assertTrue(java.io.File(before.urLs.single().toURI()).exists())
      }
      assertFalse(java.io.File(before.urLs.single().toURI()).exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `dependency jars keep their original URLs`() {
    val jar = Files.createTempFile("preview-dependency", ".jar").toFile()
    try {
      val url = jar.toURI().toURL()
      val snapshot = UserClassDirectories.capture(listOf(url))
      assertEquals(listOf(url), snapshot.urls)
      snapshot.run()
      assertTrue(jar.exists())
    } finally {
      jar.delete()
    }
  }
}
