package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FollowedFileTest {

  private lateinit var file: File
  private var reads = 0
  private val failures = mutableListOf<Throwable>()

  @Before
  fun setUp() {
    file = Files.createTempFile("followed-file", ".txt").toFile()
  }

  @After
  fun tearDown() {
    file.delete()
  }

  private fun write(text: String, modified: Long) {
    file.writeText(text)
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified))
  }

  private fun followed(initial: String): FollowedFile<String> =
    FollowedFile(
      file = file,
      initial = initial,
      read = {
        reads++
        it.readText().also { text -> check(text != "broken") { "unparseable" } }
      },
      onReadFailed = { failures += it },
    )

  @Test
  fun `the first lookup checks the initial value against the file`() {
    write("on disk", modified = 1_000_000L)

    assertEquals("on disk", followed(initial = "handed over").current())
  }

  @Test
  fun `an unchanged file is read once`() {
    write("A", modified = 1_000_000L)
    val followed = followed(initial = "A")

    followed.current()
    followed.current()

    assertEquals(1, reads)
  }

  @Test
  fun `a rewritten file is read again`() {
    write("A", modified = 1_000_000L)
    val followed = followed(initial = "A")
    followed.current()

    write("A and B", modified = 2_000_000L)

    assertEquals("A and B", followed.current())
  }

  @Test
  fun `a file modified inside the racy window is read on every lookup`() {
    write("A", modified = System.currentTimeMillis())
    val followed = followed(initial = "A")

    followed.current()
    followed.current()

    assertEquals(2, reads)
  }

  @Test
  fun `a file that does not parse keeps the last value and is tried again`() {
    write("A", modified = 1_000_000L)
    val followed = followed(initial = "A")
    followed.current()

    write("broken", modified = 2_000_000L)
    assertEquals("A", followed.current())
    assertEquals(1, failures.size)

    write("B", modified = 3_000_000L)
    assertEquals("B", followed.current())
  }

  @Test
  fun `a deleted file keeps the last value`() {
    write("A", modified = 1_000_000L)
    val followed = followed(initial = "A")
    followed.current()

    assertTrue(file.delete())

    assertEquals("A", followed.current())
    assertTrue(failures.isEmpty())
  }
}
