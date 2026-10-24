@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package ee.schimke.composeai.daemon.warm.impl

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.KotlinCompilerVersion

/**
 * The warm compiler's only entry point. `:daemon:core`'s `WarmCompileService` loads this class
 * reflectively in a class loader over the Build Tools API impl jars (which carry
 * kotlin-compiler-embeddable) plus this module's jar, so its surface uses JDK types only.
 *
 * One instance per module. [compile] compiles just the given sources against the module's class
 * path and its existing classes, reusing the class-path state across calls, and installs the result
 * into the module's class directory only when every recompiled class keeps its ABI. Any edit it
 * cannot prove local comes back as `unsupported`, and the caller compiles it through the Build
 * Tools API's incremental compiler instead, which also recompiles dependents.
 *
 * Config keys: `compileClasspath` (List<String>), `classesDir`, `moduleName`, `stagingDir`,
 * `jvmTarget` (default 17), `pluginClasspath` (List<String>), `pluginOptions` (List<String>, each
 * `plugin:<id>:<key>=<value>`).
 *
 * [compile] returns a map with `status` — `ok`, `errors` (with `errors`: maps of `file`, `line`,
 * `column`, `message`), `unsupported` (with `reason`) or `failed` (with `reason`) — and `timings`
 * (phase name to nanoseconds).
 */
public class WarmCompilerEntry(config: Map<String, Any?>) : AutoCloseable {

  // First, before anything else here touches the compiler.
  init {
    val runtime = runtimeCompilerVersion()
    check(minorOf(runtime) == minorOf(BUILT_AGAINST) && !isOlder(runtime, SUPPORTED_FROM)) {
      "warm compiler supports Kotlin $SUPPORTED_FROM and later ${minorOf(BUILT_AGAINST)} " +
        "compilers, but the compiler on the class path is $runtime"
    }
  }

  private val compileClasspath: List<String> = config.stringList("compileClasspath")
  private val classesDir = File(config["classesDir"] as String)
  private val moduleName = config["moduleName"] as String
  private val stagingDir = File(config["stagingDir"] as String)
  private val jvmTarget = config["jvmTarget"] as? String ?: "17"

  private val pluginLoader: URLClassLoader
  private val plugins: LoadedPlugins

  private var keepAlive: Disposable = Disposer.newDisposable("warm-compiler")
  private val frontend: WarmFrontendPhase
  private var moduleClasses: ModuleClasses? = null
  private var fingerprint: String? = null

  init {
    pluginLoader =
      URLClassLoader(
        config.stringList("pluginClasspath").map { File(it).toURI().toURL() }.toTypedArray(),
        WarmCompilerEntry::class.java.classLoader,
      )
    plugins =
      LoadedPlugins(
        registrars = loadOwn(CompilerPluginRegistrar::class.java),
        processors = loadOwn(CommandLineProcessor::class.java),
        options = config.stringList("pluginOptions"),
      )
    frontend = warmFrontendPhase({ keepAlive }, plugins)
  }

  /**
   * One warm-up pass: compiles [sources] — the module's own files, or a generated one outside it —
   * into the staging directory and installs nothing, building the class-path state, loading the
   * library symbols they use and JIT-warming the pipeline. The class directory is only read, so a
   * Gradle build may run alongside; one that writes classes drops the state again at the next pass
   * or [compile], keeping only the warm JIT.
   *
   * Returns `ok`; `errors` (with `reason`) when [sources] do not compile, which keeps the state, as
   * the library session is the same whatever the sources say; or `failed` (with `reason`) when the
   * compiler itself did, which drops it. Always with `timings`.
   */
  @Synchronized
  public fun warmUp(sources: List<String>): Map<String, Any?> {
    val start = System.nanoTime()
    // Taken before compiling, so a build that writes classes meanwhile still invalidates.
    val current = classesFingerprint(classesDir)
    if (current != fingerprint) {
      dropState()
      fingerprint = current
    }
    if (moduleClasses == null) moduleClasses = ModuleClasses.scan(classesDir)
    stagingDir.deleteRecursively()
    stagingDir.mkdirs()
    val messages = Collector()
    val exitCode = runPipeline(sources, messages)
    stagingDir.deleteRecursively()
    val timings = mapOf("warmUp" to System.nanoTime() - start)
    if (messages.errors.isNotEmpty()) {
      val errors = messages.errors.joinToString("; ") { "${it["message"]}" }
      return result("errors", timings, "reason" to errors)
    }
    if (exitCode != ExitCode.OK) {
      dropState()
      return result("failed", timings, "reason" to "compiler exited $exitCode: ${messages.other}")
    }
    return result("ok", timings)
  }

  private fun runPipeline(sources: List<String>, messages: Collector): ExitCode {
    val arguments =
      parseCommandLineArguments<K2JVMCompilerArguments>(
        listOf(
          "-classpath",
          (compileClasspath + classesDir.path).joinToString(File.pathSeparator),
          "-d",
          stagingDir.path,
          "-jvm-target",
          jvmTarget,
          "-module-name",
          moduleName,
          "-no-stdlib",
          "-no-reflect",
        ) + sources
      )
    return runWarmPipeline(arguments, plugins, frontend, messages, keepAlive)
  }

  @Synchronized
  public fun compile(sources: List<String>): Map<String, Any?> {
    val timings = LinkedHashMap<String, Long>()
    fun <T> timed(phase: String, block: () -> T): T {
      val start = System.nanoTime()
      try {
        return block()
      } finally {
        timings[phase] = System.nanoTime() - start
      }
    }

    // Anything that wrote classes since our last install (a Gradle build, a Build Tools API
    // compile) may have changed an ABI the cached library session already deserialized.
    val current = timed("fingerprint") { classesFingerprint(classesDir) }
    if (current != fingerprint) dropState()
    val classes =
      moduleClasses ?: timed("index") { ModuleClasses.scan(classesDir) }.also { moduleClasses = it }

    stagingDir.deleteRecursively()
    stagingDir.mkdirs()
    val messages = Collector()
    val exitCode = timed("compile") { runPipeline(sources, messages) }
    if (messages.errors.isNotEmpty()) return result("errors", timings, "errors" to messages.errors)
    if (exitCode != ExitCode.OK) {
      // An internal error may have left the cached sessions half-updated; start over next time.
      dropState()
      return result("failed", timings, "reason" to "compiler exited $exitCode: ${messages.other}")
    }
    if (frontend.declaresNonPrivateInline) {
      return unsupported(
        timings,
        "declares a non-private inline function, whose body callers inline",
      )
    }

    return timed("install") {
      val staged = HashMap<String, MutableSet<String>>()
      stagingDir
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { file ->
          val relative = file.relativeTo(stagingDir).invariantSeparatorsPath
          val bytes = file.readBytes()
          if (ClassAbi.isMultifile(bytes)) {
            return@timed unsupported(timings, "$relative is part of a multi-file class")
          }
          val key =
            ModuleClasses.sourceKey(relative, bytes)
              ?: return@timed unsupported(timings, "$relative names no source file")
          staged.getOrPut(key) { HashSet() }.add(relative)
        }
      for ((key, newClasses) in staged) {
        // A new file's facade is missing from the module's .kotlin_module, which a one-file
        // compile cannot rewrite, so other files could not see its top-level declarations.
        val oldClasses =
          classes.classesOf(key) ?: return@timed unsupported(timings, "$key is a new source file")
        val before = abiOf(classesDir, oldClasses)
        val after = abiOf(stagingDir, newClasses)
        if (before != after) {
          val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
          return@timed unsupported(timings, "ABI changed in $key: ${changed.joinToString()}")
        }
      }
      for ((key, newClasses) in staged) classes.install(key, stagingDir, newClasses)
      fingerprint = classesFingerprint(classesDir)
      result("ok", timings, "classes" to staged.values.sumOf { it.size })
    }
  }

  /** Forget everything cached; the next compile rebuilds it from the class path. */
  @Synchronized
  public fun invalidate() {
    dropState()
  }

  @Synchronized
  override fun close() {
    dropState()
    pluginLoader.close()
  }

  private fun dropState() {
    frontend.state = null
    moduleClasses = null
    fingerprint = null
    Disposer.dispose(keepAlive)
    keepAlive = Disposer.newDisposable("warm-compiler")
  }

  private fun abiOf(root: File, classes: Set<String>): Map<String, String> =
    classes
      .mapNotNull { relative ->
        ClassAbi.of(File(root, relative).readBytes())?.let { relative to it }
      }
      .toMap()

  private fun <T : Any> loadOwn(type: Class<T>): List<T> =
    ServiceLoader.load(type, pluginLoader).filter { it.javaClass.classLoader === pluginLoader }

  private fun unsupported(timings: Map<String, Long>, reason: String) =
    result("unsupported", timings, "reason" to reason)

  private fun result(status: String, timings: Map<String, Long>, vararg extra: Pair<String, Any?>) =
    // The live map, not a copy: `timed` adds the enclosing phase after this map is built.
    mapOf("status" to status, "timings" to timings) + extra

  private class Collector : MessageCollector {
    val errors = ArrayList<Map<String, Any?>>()
    val other = StringBuilder()

    override fun clear() {}

    override fun hasErrors(): Boolean = errors.isNotEmpty() || other.isNotEmpty()

    override fun report(
      severity: CompilerMessageSeverity,
      message: String,
      location: CompilerMessageSourceLocation?,
    ) {
      if (!severity.isError) return
      if (location != null && severity == CompilerMessageSeverity.ERROR) {
        errors +=
          mapOf(
            "file" to location.path,
            "line" to location.line,
            "column" to location.column,
            "message" to message,
          )
      } else {
        other.append(message).append('\n')
      }
    }
  }

  public companion object {
    /** Bumped whenever the facade's config or result shape changes. */
    public const val PROTOCOL: Int = 1

    /**
     * The compiler version this module was built against, written into a resource at build time.
     */
    @JvmField
    public val BUILT_AGAINST: String =
      WarmCompilerEntry::class.java.getResource("built-against.txt")!!.readText().trim()

    /** The oldest compiler of its line this jar is tested against, written in at build time. */
    @JvmField
    public val SUPPORTED_FROM: String =
      WarmCompilerEntry::class.java.getResource("supported-from.txt")!!.readText().trim()

    /** The Kotlin minor line of compiler [version]: `2.4` for `2.4.21`, `2.5` for `2.5.0-Beta2`. */
    @JvmStatic
    public fun minorOf(version: String): String = version.split('.').take(2).joinToString(".")

    /**
     * Whether compiler [version] predates [other] of the same line: `2.4.10` predates `2.4.20`, and
     * pre-releases order dev < Alpha < Beta < RC < release, by number within a kind (`2.5.0-Beta1`
     * predates `2.5.0-Beta2`; a bare `RC` is `RC1`).
     */
    @JvmStatic
    public fun isOlder(version: String, other: String): Boolean {
      fun key(v: String): List<Int> {
        val patch = v.split('.').getOrNull(2).orEmpty()
        val number = patch.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        val suffix = patch.substringAfter('-', "")
        val kind = suffix.takeWhile { it.isLetter() }.lowercase()
        val rank =
          when (kind) {
            "" -> 4
            "rc" -> 3
            "beta" -> 2
            "alpha" -> 1
            else -> 0
          }
        val sequence = suffix.drop(kind.length).filter { it.isDigit() }.toIntOrNull() ?: 1
        return listOf(number, rank, if (rank == 4) 0 else sequence)
      }
      val a = key(version)
      val b = key(other)
      return a.indices.firstOrNull { a[it] != b[it] }?.let { a[it] < b[it] } ?: false
    }

    /** The compiler on the class path at run time, read reflectively so it is not inlined. */
    @JvmStatic
    public fun runtimeCompilerVersion(): String =
      KotlinCompilerVersion::class.java.getField("VERSION").get(null) as String

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.stringList(key: String): List<String> =
      (this[key] as? List<String>).orEmpty()
  }
}
