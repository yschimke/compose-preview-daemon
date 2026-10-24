@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.compile.BtaCompileSession
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin

/**
 * Warms the Build Tools API fallback for a `compileWarmUp` on a module no warm compiler can serve
 * (docs/daemon/WARM-COMPILE.md § Warming up): one compile of a generated file through the very
 * [session] the fallback compiles with, so the fallback's first edit finds the toolchain loaded and
 * every class-path snapshot cached.
 *
 * Installs nothing. The compile goes to a scratch output and incremental-compilation directory
 * under [icWorkingDir], never the module's: the module's IC caches must only ever see the module's
 * own compiles. Only the class-path snapshots are shared. They are content-addressed (one
 * `<sha-256>.bin` per class-path entry), so the scratch directory is seeded with links to the
 * module's existing ones, and the ones the warm-up adds are moved back into
 * [icWorkingDir]`/cp-snapshots`, each atomically and never over an existing one.
 */
internal class BtaFallbackWarmUp(
  private val session: BtaCompileSession,
  private val compileClasspath: List<Path>,
  private val compilerPlugins: List<CompilerPlugin>,
  private val icWorkingDir: Path,
) {

  /** Runs the warm-up; null when it compiled, otherwise why not. Never throws. */
  fun warmUp(): String? {
    val scratch = icWorkingDir.resolve(SCRATCH_DIR)
    val shared = icWorkingDir.resolve(SNAPSHOTS_DIR)
    val scratchSnapshots = scratch.resolve(SNAPSHOTS_DIR)
    return try {
      scratch.toFile().deleteRecursively()
      Files.createDirectories(shared)
      Files.createDirectories(scratchSnapshots)
      seedSnapshots(from = shared, into = scratchSnapshots)
      val source = scratch.resolve("src/WarmUp.kt")
      Files.createDirectories(source.parent)
      Files.writeString(
        source,
        (if (compilerPlugins.isEmpty()) WARM_UP_SOURCE_PLAIN else WarmCompileService.WARM_UP_SOURCE)
          .trimIndent(),
      )
      val errors = ErrorCollector()
      try {
        session.compileIncremental(
          sources = listOf(source),
          compileClasspath = compileClasspath,
          outputDir = scratch.resolve("classes"),
          compilerPlugins = compilerPlugins,
          sourcesChanges = SourcesChanges.ToBeCalculated,
          diagnosticListener = errors,
          workingDir = scratch,
        )
        null
      } catch (t: Throwable) {
        errors.first ?: (t.message ?: t.javaClass.name)
      } finally {
        // Snapshotting precedes the compile, so even a failed one leaves valid snapshots.
        publishSnapshots(from = scratchSnapshots, into = shared)
      }
    } catch (t: Throwable) {
      t.toString()
    } finally {
      scratch.toFile().deleteRecursively()
    }
  }

  private class ErrorCollector : KotlinLogger {
    var first: String? = null

    override val isDebugEnabled: Boolean = false

    override fun error(msg: String, throwable: Throwable?) {
      if (first == null) first = msg
    }

    override fun warn(msg: String) {}

    override fun warn(msg: String, throwable: Throwable?) {}

    override fun info(msg: String) {}

    override fun debug(msg: String) {}

    override fun lifecycle(msg: String) {}
  }

  internal companion object {
    /** Under the module's IC working directory, next to the fallback's own `ic/`. */
    internal const val SCRATCH_DIR: String = "warm-up-bta"

    /** Where `BtaCompileSession` keeps the class-path snapshots, under its working directory. */
    internal const val SNAPSHOTS_DIR: String = "cp-snapshots"

    /** The warm-up file for a module without the Compose compiler plugin. */
    internal const val WARM_UP_SOURCE_PLAIN: String =
      """
      package ee.schimke.composeai.daemon.warmup

      private fun warmUp(items: List<String>): Int = items.sumOf { it.length }
      """

    /** Links (or, where links fail, copies) each snapshot in [from] into [into]. */
    internal fun seedSnapshots(from: Path, into: Path) {
      for (file in from.listDirectoryEntries("*.bin")) {
        if (!file.isRegularFile()) continue
        val target = into.resolve(file.name)
        try {
          Files.createLink(target, file)
        } catch (_: IOException) {
          Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
          Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }

    /**
     * Moves each snapshot of [from] that [into] lacks into it: copied beside its final name first,
     * then renamed atomically, so a compile reading [into] never sees a partial file. An existing
     * snapshot is never replaced; the content address makes it the same bytes.
     */
    internal fun publishSnapshots(from: Path, into: Path) {
      if (!Files.isDirectory(from)) return
      for (file in from.listDirectoryEntries("*.bin")) {
        val target = into.resolve(file.name)
        if (Files.exists(target)) continue
        val partial = into.resolve(".${file.name}.warm-up")
        try {
          Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING)
          Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
          // A compile wrote it meanwhile.
        } catch (_: IOException) {
          // Not fatal: the fallback snapshots it on its first compile, as without a warm-up.
        } finally {
          Files.deleteIfExists(partial)
        }
      }
    }
  }
}
