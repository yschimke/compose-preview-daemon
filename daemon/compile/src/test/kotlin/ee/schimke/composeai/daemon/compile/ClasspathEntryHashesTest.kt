package ee.schimke.composeai.daemon.compile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClasspathEntryHashesTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `an unchanged jar is hashed once across compiles`() {
    val jar = tmp.newFile("lib.jar").toPath().also { it.writeText("jar-bytes") }
    val hashes = ClasspathEntryHashes()

    val first = hashes.sha256(jar)
    repeat(5) { assertEquals(first, hashes.sha256(jar)) }
    // The same entry spelled differently is the same key.
    assertEquals(first, hashes.sha256(jar.parent.resolve("./lib.jar")))

    assertEquals(1, hashes.contentHashesComputed)
  }

  @Test
  fun `a path through a symlink is stamped as the file the filesystem resolves`() {
    val deps = tmp.newFolder("deps").toPath()
    val nested = deps.resolve("nested").createDirectories()
    val realJar = deps.resolve("lib.jar").also { it.writeText("real") }
    val workspace = tmp.newFolder("workspace").toPath()
    // A decoy where lexical normalization of `link/../lib.jar` would point.
    workspace.resolve("lib.jar").writeText("decoy")
    Files.createSymbolicLink(workspace.resolve("link"), nested)
    val throughLink = workspace.resolve("link/../lib.jar")
    val hashes = ClasspathEntryHashes()

    val first = hashes.sha256(throughLink)
    assertEquals(ClasspathEntryHashes().sha256(realJar), first)

    // Rewriting the real file re-hashes, though the decoy beside the link did not move.
    realJar.writeText("real v2")
    bumpMtime(realJar)
    assertNotEquals(first, hashes.sha256(throughLink))
    assertEquals(2, hashes.contentHashesComputed)
  }

  @Test
  fun `the memoized hash is the content hash the snapshot cache always used`() {
    val bytes = "some jar content".toByteArray()
    val jar = tmp.newFile("dep.jar").toPath().also { it.writeBytes(bytes) }

    val md = MessageDigest.getInstance("SHA-256")
    md.update("dep.jar".toByteArray())
    md.update(0)
    md.update(bytes)
    val expected = md.digest().joinToString("") { "%02x".format(it) }

    assertEquals(expected, ClasspathEntryHashes().sha256(jar))
  }

  @Test
  fun `a jar rebuilt in place is re-hashed`() {
    val jar = tmp.newFile("lib.jar").toPath().also { it.writeText("v1") }
    val hashes = ClasspathEntryHashes()
    val v1 = hashes.sha256(jar)

    // Same size, new content, new mtime: the stamp moves, so the hash is recomputed.
    jar.writeText("v2")
    bumpMtime(jar)
    val v2 = hashes.sha256(jar)

    assertNotEquals(v1, v2)
    assertEquals(2, hashes.contentHashesComputed)
  }

  @Test
  fun `touching a jar without changing it re-hashes to the same value`() {
    val jar = tmp.newFile("lib.jar").toPath().also { it.writeText("same") }
    val hashes = ClasspathEntryHashes()
    val before = hashes.sha256(jar)

    bumpMtime(jar)

    assertEquals(before, hashes.sha256(jar))
    assertEquals(2, hashes.contentHashesComputed)
  }

  @Test
  fun `a classes directory is re-hashed when any file under it changes`() {
    val dir = tmp.newFolder("classes").toPath()
    val nested = dir.resolve("com/example").createDirectories()
    nested.resolve("A.class").writeText("A1")
    val hashes = ClasspathEntryHashes()

    val initial = hashes.sha256(dir)
    assertEquals(initial, hashes.sha256(dir))
    assertEquals("unchanged directory reuses its hash", 1, hashes.contentHashesComputed)

    // A file rewritten in a subdirectory leaves the directory's own mtime alone; the stamp
    // still has to move.
    nested.resolve("A.class").writeText("A2")
    bumpMtime(nested.resolve("A.class"))
    val rewritten = hashes.sha256(dir)
    assertNotEquals(initial, rewritten)
    assertEquals(2, hashes.contentHashesComputed)

    nested.resolve("B.class").writeText("B")
    val added = hashes.sha256(dir)
    assertNotEquals(rewritten, added)
    assertEquals(3, hashes.contentHashesComputed)

    Files.delete(nested.resolve("B.class"))
    assertEquals(rewritten, hashes.sha256(dir))
    assertEquals(4, hashes.contentHashesComputed)

    assertEquals(rewritten, hashes.sha256(dir))
    assertEquals(4, hashes.contentHashesComputed)
  }

  private fun bumpMtime(path: Path) {
    val now = Files.getLastModifiedTime(path).toMillis()
    Files.setLastModifiedTime(path, FileTime.fromMillis(now + 2_000))
  }
}
