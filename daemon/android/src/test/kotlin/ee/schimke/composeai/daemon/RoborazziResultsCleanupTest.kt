package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for `redirectRoborazziResults` and stale `composeai-roborazzi-results*` sweeping. */
class RoborazziResultsCleanupTest {

  @Test
  fun `sweepStaleRoborazziResultDirs removes dead-PID and aged legacy dirs while keeping live ones`() {
    val base = Files.createTempDirectory("roborazzi-sweep-test").toFile()
    try {
      val now = 2_000_000_000L
      val maxAge = TimeUnit.HOURS.toMillis(24)

      val deadPidDir = File(base, "composeai-roborazzi-results-pid111-abc").apply { mkdirs() }
      File(deadPidDir, "result.json").writeText("{}")
      val livePidDir = File(base, "composeai-roborazzi-results-pid222-def").apply { mkdirs() }
      val oldLegacyDir =
        File(base, "composeai-roborazzi-results999").apply {
          mkdirs()
          setLastModified(now - maxAge - 1_000L)
        }
      val freshLegacyDir =
        File(base, "composeai-roborazzi-results888").apply {
          mkdirs()
          setLastModified(now - TimeUnit.HOURS.toMillis(1))
        }
      val unrelatedDir = File(base, "other-temp-dir").apply { mkdirs() }

      val removed =
        sweepStaleRoborazziResultDirs(
          baseDir = base,
          isPidAlive = { pid -> pid == 222L },
          legacyMaxAgeMs = maxAge,
          nowMs = now,
        )

      assertEquals(2, removed)
      assertFalse(deadPidDir.exists())
      assertFalse(oldLegacyDir.exists())
      assertTrue(livePidDir.exists())
      assertTrue(freshLegacyDir.exists())
      assertTrue(unrelatedDir.exists())
    } finally {
      SystemPropertyArgs.deleteRecursivelyNoFollow(base)
    }
  }

  @Test
  fun `sweepStaleRoborazziResultDirs skips top-level symlinks and never follows nested symlinks`() {
    val base = Files.createTempDirectory("roborazzi-sweep-symlink-test").toFile()
    val outsideTop = Files.createTempDirectory("roborazzi-outside-top").toFile()
    val outsidePid = Files.createTempDirectory("roborazzi-outside-pid").toFile()
    val outsideLegacy = Files.createTempDirectory("roborazzi-outside-legacy").toFile()
    val topKeep = File(outsideTop, "keep.txt").apply { writeText("keep-top") }
    val pidKeep = File(outsidePid, "keep.txt").apply { writeText("keep-pid") }
    val legacyKeep = File(outsideLegacy, "keep.txt").apply { writeText("keep-legacy") }
    try {
      val now = 2_000_000_000L
      val maxAge = TimeUnit.HOURS.toMillis(24)

      val topLink = File(base, "composeai-roborazzi-results-pid111-link")
      Files.createSymbolicLink(topLink.toPath(), outsideTop.toPath())

      val deadPidDir = File(base, "composeai-roborazzi-results-pid111-real").apply { mkdirs() }
      File(deadPidDir, "result.json").writeText("{}")
      val pidNestedLink = File(deadPidDir, "nested-link")
      Files.createSymbolicLink(pidNestedLink.toPath(), outsidePid.toPath())

      val oldLegacyDir = File(base, "composeai-roborazzi-results-legacy").apply { mkdirs() }
      val legacySubDir = File(oldLegacyDir, "sub").apply { mkdirs() }
      val legacyNestedLink = File(legacySubDir, "nested-link")
      Files.createSymbolicLink(legacyNestedLink.toPath(), outsideLegacy.toPath())
      oldLegacyDir.setLastModified(now - maxAge - 1_000L)

      val removed =
        sweepStaleRoborazziResultDirs(
          baseDir = base,
          isPidAlive = { false },
          legacyMaxAgeMs = maxAge,
          nowMs = now,
        )

      assertEquals(2, removed)
      assertFalse(Files.exists(deadPidDir.toPath(), LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(pidNestedLink.toPath(), LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(oldLegacyDir.toPath(), LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(legacyNestedLink.toPath(), LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(topLink.toPath(), LinkOption.NOFOLLOW_LINKS))
      assertTrue(topKeep.exists())
      assertEquals("keep-top", topKeep.readText())
      assertTrue(pidKeep.exists())
      assertEquals("keep-pid", pidKeep.readText())
      assertTrue(legacyKeep.exists())
      assertEquals("keep-legacy", legacyKeep.readText())
    } finally {
      SystemPropertyArgs.deleteRecursivelyNoFollow(base)
      SystemPropertyArgs.deleteRecursivelyNoFollow(outsideTop)
      SystemPropertyArgs.deleteRecursivelyNoFollow(outsidePid)
      SystemPropertyArgs.deleteRecursivelyNoFollow(outsideLegacy)
    }
  }

  @Test
  fun `redirectRoborazziResults sweeps stale dirs and creates a PID-tagged directory`() {
    val base = Files.createTempDirectory("roborazzi-redirect-test").toFile()
    val previous = System.getProperty("roborazzi.result.dir")
    try {
      System.clearProperty("roborazzi.result.dir")
      val deadPidDir = File(base, "composeai-roborazzi-results-pid999999-stale").apply { mkdirs() }

      redirectRoborazziResults(baseDir = base, isPidAlive = { false })

      assertFalse("stale dead-PID dir must be swept on startup", deadPidDir.exists())
      val createdPath = System.getProperty("roborazzi.result.dir")
      val created = File(createdPath)
      assertTrue(created.isDirectory)
      assertTrue(
        "expected PID-tagged dir name, got: ${created.name}",
        created.name.startsWith("composeai-roborazzi-results-pid${ProcessHandle.current().pid()}-"),
      )
    } finally {
      if (previous == null) {
        System.clearProperty("roborazzi.result.dir")
      } else {
        System.setProperty("roborazzi.result.dir", previous)
      }
      SystemPropertyArgs.deleteRecursivelyNoFollow(base)
    }
  }
}
