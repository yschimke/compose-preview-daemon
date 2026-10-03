package ee.schimke.composeai.daemon.pool

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SandboxProcessPool.workerJvmArgs] gives each worker slot its own class-data-sharing archive, so
 * a worker halting alongside its parent never dumps into the parent's file.
 */
class SandboxProcessPoolWorkerJvmArgsTest {

  @Test
  fun `the shared archive is re-pointed at a per-slot file`() {
    val inherited =
      listOf(
        "-XX:MaxRAMPercentage=70",
        "-XX:+AutoCreateSharedArchive",
        "-XX:SharedArchiveFile=/cache/cds/android-daemon-abc.jsa",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
      )
    assertEquals(
      listOf(
        "-XX:MaxRAMPercentage=70",
        "-XX:+AutoCreateSharedArchive",
        "-XX:SharedArchiveFile=/cache/cds/android-daemon-abc-worker0.jsa",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
      ),
      SandboxProcessPool.workerJvmArgs(inherited, 0),
    )
    assertEquals(
      "-XX:SharedArchiveFile=/cache/cds/android-daemon-abc-worker3.jsa",
      SandboxProcessPool.workerJvmArgs(inherited, 3)[2],
    )
  }

  @Test
  fun `an archive path without the jsa suffix still gets the slot suffix`() {
    assertEquals(
      listOf("-XX:SharedArchiveFile=/tmp/archive-worker1"),
      SandboxProcessPool.workerJvmArgs(listOf("-XX:SharedArchiveFile=/tmp/archive"), 1),
    )
  }

  @Test
  fun `args without an archive pass through untouched`() {
    val args = listOf("-Xmx1g", "-XX:+UseG1GC", "--enable-native-access=ALL-UNNAMED")
    assertEquals(args, SandboxProcessPool.workerJvmArgs(args, 2))
  }

  @Test
  fun `a classpath with a non-empty directory gets no archive at all`() {
    // HotSpot fails the launch rather than run without CDS: `Cannot have non-empty directory in
    // paths`. A worker that cannot boot is worse than one that boots without an archive.
    val inherited =
      listOf(
        "-XX:MaxRAMPercentage=70",
        "-XX:+AutoCreateSharedArchive",
        "-XX:SharedArchiveFile=/cache/cds/android-daemon-abc.jsa",
        "-Xlog:cds*=off:stderr",
      )
    assertEquals(
      listOf("-XX:MaxRAMPercentage=70", "-Xlog:cds*=off:stderr"),
      SandboxProcessPool.workerJvmArgs(inherited, 0, classpathHasNonEmptyDirectory = true),
    )
  }

  @Test
  fun `only a directory with contents counts`() {
    val empty = Files.createTempDirectory("cp-empty").toFile()
    val full =
      Files.createTempDirectory("cp-full").toFile().also {
        it.resolve("a.properties").writeText("x")
      }
    val jar = Files.createTempFile("cp", ".jar").toFile()
    val sep = java.io.File.pathSeparator
    assertFalse(SandboxProcessPool.hasNonEmptyDirectory("${jar.path}$sep${empty.path}$sep/missing"))
    assertTrue(SandboxProcessPool.hasNonEmptyDirectory("${jar.path}$sep${full.path}"))
    assertFalse(SandboxProcessPool.hasNonEmptyDirectory(""))
  }
}
