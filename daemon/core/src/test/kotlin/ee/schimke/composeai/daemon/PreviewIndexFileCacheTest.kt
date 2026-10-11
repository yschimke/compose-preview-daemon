package ee.schimke.composeai.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreviewIndexFileCacheTest {

  private lateinit var file: Path

  @Before
  fun setUp() {
    PreviewIndexFileCache.clear()
    file = Files.createTempFile("previews-cache", ".json")
  }

  @After
  fun tearDown() {
    PreviewIndexFileCache.clear()
    Files.deleteIfExists(file)
  }

  private fun write(vararg ids: String, modified: Long) {
    Files.writeString(
      file,
      ids.joinToString(prefix = """{"previews":[""", postfix = "]}") {
        """{"id":"$it","className":"com.example.PreviewsKt","functionName":"$it"}"""
      },
    )
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified))
  }

  @Test
  fun `an unchanged file is decoded once and the parse is reused`() {
    write("A", "B", modified = 1_000_000L)

    val first = PreviewIndexFileCache.load(file)
    val second = PreviewIndexFileCache.load(file)

    assertSame(first, second)
    assertEquals(setOf("A", "B"), second.ids())
  }

  @Test
  fun `a rewritten file is reloaded`() {
    write("A", modified = 1_000_000L)
    val before = PreviewIndexFileCache.load(file)

    // Same mtime, different size: still a change.
    write("A", "B", modified = 1_000_000L)
    val resized = PreviewIndexFileCache.load(file)
    assertNotSame(before, resized)
    assertEquals(setOf("A", "B"), resized.ids())

    // Same size, different mtime: still a change.
    write("A", "C", modified = 2_000_000L)
    val touched = PreviewIndexFileCache.load(file)
    assertNotSame(resized, touched)
    assertEquals(setOf("A", "C"), touched.ids())
  }

  @Test
  fun `a just-written file is re-read until its modified time settles`() {
    val now = System.currentTimeMillis()
    write("A", "B", modified = now)
    val first = PreviewIndexFileCache.load(file)

    // Same length and same modified time, as on a coarse-timestamp filesystem: not trusted yet.
    write("A", "C", modified = now)
    val rewritten = PreviewIndexFileCache.load(file)
    assertNotSame(first, rewritten)
    assertEquals(setOf("A", "C"), rewritten.ids())
  }

  @Test
  fun `a missing file is not cached`() {
    Files.delete(file)
    assertTrue(PreviewIndexFileCache.load(file).ids().isEmpty())

    write("Later", modified = 1_000_000L)
    assertEquals(setOf("Later"), PreviewIndexFileCache.load(file).ids())
  }

  @Test
  fun `the system property selects the file and an unset property is empty`() {
    write("A", modified = 1_000_000L)
    val previous = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    try {
      System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
      assertTrue(PreviewIndexFileCache.fromSystemProperty().ids().isEmpty())

      System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, file.toString())
      assertSame(PreviewIndexFileCache.load(file), PreviewIndexFileCache.fromSystemProperty())
    } finally {
      if (previous == null) System.clearProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
      else System.setProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP, previous)
    }
  }
}
