package ee.schimke.composeai.daemon

import java.io.File
import java.nio.charset.Charset

/**
 * `-D` arguments for a launched JVM, kept under the OS limits on one `execve` argument and on a
 * whole command line. Shared by the daemon client, which launches the daemon, and the daemon's
 * sandbox pool, which launches its workers with the same `-D` set.
 */
public object SystemPropertyArgs {
  /**
   * Linux refuses any single `execve` argument over `MAX_ARG_STRLEN` (32 pages, 128 KiB) with
   * `E2BIG`, whatever `ARG_MAX` allows. `composeai.daemon.userClassDirs` on a target with ~1,500
   * runtime jars is ~290 KiB, so past these sizes every `-D` goes through an argfile instead.
   */
  public const val MAX_INLINE_PROPERTY: Int = 32 * 1024
  public const val MAX_INLINE_PROPERTIES: Int = 96 * 1024

  /**
   * Windows passes one command line to `CreateProcess`, at most 32,767 characters in all. The
   * classpath is usually an argfile already, so the properties share that budget with the launcher,
   * JVM args and main class.
   */
  public const val MAX_WINDOWS_INLINE_PROPERTIES: Int = 16 * 1024

  private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

  /**
   * The `-D` arguments for [properties]: inline, or one `@argfile` holding them all when one is too
   * long for `execve` ([MAX_INLINE_PROPERTY]) or together they would crowd the limit. The argfile
   * is created in [directory] (the temp directory when null) and deleted when this JVM exits.
   */
  public fun of(properties: Map<String, String>, directory: File? = null): List<String> {
    val inline = properties.map { (k, v) -> "-D$k=$v" }
    val limit = if (isWindows) MAX_WINDOWS_INLINE_PROPERTIES else MAX_INLINE_PROPERTIES
    if (inline.none { it.length > MAX_INLINE_PROPERTY } && inline.sumOf { it.length } <= limit) {
      return inline
    }
    val file = File.createTempFile("composeai-props", ".args", directory)
    file.deleteOnExit()
    // The launcher decodes argfiles in the platform encoding, as `classpathArgFile` writes them.
    val charset = runCatching {
      Charset.forName(System.getProperty("sun.jnu.encoding"))
    }
      .getOrDefault(Charset.defaultCharset())
    file.writeText(inline.joinToString("\n", postfix = "\n") { quote(it) }, charset)
    return listOf("@" + file.absolutePath)
  }

  /** One argfile token: quoted, with `\` and `"` escaped as the launcher un-escapes them. */
  public fun quote(token: String): String =
    "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
