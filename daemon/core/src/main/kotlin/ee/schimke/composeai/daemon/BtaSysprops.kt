package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import java.io.File

/**
 * Reads the `composeai.daemon.bta.*` system properties for [DefaultBtaCompileService.fromSysprops],
 * dropping compile-classpath entries that do not exist.
 *
 * The Gradle plugin writes the compile classpath from the AGP unit-test task's classpath, which
 * names directories a build may never create — `javac/debugUnitTest/…/classes` in a module with no
 * Java tests. The Kotlin Build Tools API throws on a missing classpath root, and the daemon turned
 * that into `fallback` on every `compileSources`, so no Android module ever compiled in process
 * (compose-preview-server#1189). A missing directory holds no classes, so dropping it changes
 * nothing the compiler can see.
 */
public object BtaSysprops {

  /** The lookup to hand to [DefaultBtaCompileService.fromSysprops]. */
  public fun lookup(key: String): String? = lookup(key, System::getProperty)

  internal fun lookup(key: String, source: (String) -> String?): String? {
    val value = source(key) ?: return null
    return if (key == DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH) existingEntries(value)
    else value
  }

  internal fun existingEntries(classpath: String): String =
    classpath
      .split(File.pathSeparator)
      .filter { it.isNotBlank() && File(it).exists() }
      .joinToString(File.pathSeparator)
}
