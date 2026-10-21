package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * `compileSources` through the warm compiler first (docs/daemon/WARM-COMPILE.md), with [fallback] —
 * the Build Tools API's incremental compile — for every edit the warm compiler cannot prove local.
 *
 * The warm compiler keeps the compiler's class-path state (project environment, jar index, FIR
 * library session) across edits and compiles only the changed files, installing their classes only
 * when every class keeps its ABI. An ABI change, a new or removed file, a non-private inline
 * function, a missing change set, or any failure of the warm compiler itself goes to [fallback],
 * which recompiles dependents. Files the warm compiler compiled since the last fallback are added
 * to that fallback's change set, so the Build Tools API's incremental caches catch up with them.
 */
public class WarmCompileService
internal constructor(
  private val fallback: BtaCompileService,
  private val compilerFactory: () -> WarmCompiler,
  private val log: (String) -> Unit = { System.err.println(it) },
) : BtaCompileService {

  /** The JDK-typed surface of the warm compiler entry, loaded in its own class loader. */
  internal interface WarmCompiler {
    fun compile(sources: List<String>): Map<String, Any?>

    fun invalidate()
  }

  private var compiler: WarmCompiler? = null
  private var disabledReason: String? = null
  private val compiledSinceFallback = LinkedHashSet<String>()

  @Synchronized
  override fun compile(sources: List<Path>, changes: SourceChangeSet?): BtaCompileService.Outcome {
    val modified = changes?.modified
    val reason =
      when {
        disabledReason != null -> null
        modified == null -> "no change set"
        changes!!.removed.isNotEmpty() -> "files removed"
        modified.isEmpty() -> "nothing modified"
        modified.any { !it.endsWith(".kt") } -> "a non-Kotlin source changed"
        else -> null
      }
    if (disabledReason != null || reason != null) {
      return fallBack(sources, changes, reason)
    }
    val warm = compiler ?: loadCompiler() ?: return fallBack(sources, changes, null)

    val start = System.nanoTime()
    val result =
      try {
        warm.compile(modified!!)
      } catch (t: Throwable) {
        log("compose-ai-daemon: warm compile threw, using the Build Tools API: $t")
        runCatching { warm.invalidate() }
        return fallBack(sources, changes, null)
      }
    val wallMs = (System.nanoTime() - start) / 1_000_000
    return when (result["status"]) {
      "ok" -> {
        compiledSinceFallback += modified
        log("compose-ai-daemon: warm compile ok in $wallMs ms ${describeTimings(result)}")
        BtaCompileService.Outcome.Ok
      }
      "errors" -> BtaCompileService.Outcome.CompileError(errorsOf(result))
      else -> {
        log(
          "compose-ai-daemon: warm compile ${result["status"]} (${result["reason"]}), using the Build Tools API"
        )
        fallBack(sources, changes, null)
      }
    }
  }

  private fun loadCompiler(): WarmCompiler? =
    try {
      compilerFactory().also { compiler = it }
    } catch (t: Throwable) {
      // A Kotlin version this build was not compiled against, a missing jar: never retry.
      disabledReason = t.message ?: t.javaClass.name
      log(
        "compose-ai-daemon: warm compile unavailable ($disabledReason); using the Build Tools API"
      )
      null
    }

  private fun fallBack(
    sources: List<Path>,
    changes: SourceChangeSet?,
    reason: String?,
  ): BtaCompileService.Outcome {
    if (reason != null && compiler != null) {
      log("compose-ai-daemon: warm compile skipped ($reason), using the Build Tools API")
    }
    val widened =
      if (changes == null || compiledSinceFallback.isEmpty()) changes
      else changes.copy(modified = (compiledSinceFallback + changes.modified).toList())
    val outcome = fallback.compile(sources, widened)
    if (outcome is BtaCompileService.Outcome.Ok) compiledSinceFallback.clear()
    // The Build Tools API rewrote classes the warm compiler's library session may have read.
    compiler?.let { runCatching { it.invalidate() } }
    return outcome
  }

  private fun describeTimings(result: Map<String, Any?>): String {
    @Suppress("UNCHECKED_CAST") val timings = result["timings"] as? Map<String, Long> ?: return ""
    return timings.entries.joinToString(prefix = "(", postfix = ")") { (phase, nanos) ->
      "$phase ${nanos / 1_000_000} ms"
    }
  }

  private fun errorsOf(result: Map<String, Any?>): List<CompileErrorDetail> {
    @Suppress("UNCHECKED_CAST")
    val errors = result["errors"] as? List<Map<String, Any?>> ?: return emptyList()
    return errors.map {
      CompileErrorDetail(
        file = it["file"] as String,
        line = it["line"] as Int,
        column = it["column"] as Int,
        message = it["message"] as String,
      )
    }
  }

  public companion object {
    internal const val ENTRY_CLASS: String =
      "ee.schimke.composeai.daemon.warm.impl.WarmCompilerEntry"

    /** The bundled impl jar for compiler [version], one per supported compiler line. */
    internal fun implJarResource(version: String): String = "warm/warm-compiler-impl-$version.jar"

    /** The Compose option the Build Tools API path passes too; see `composeCompilerPlugins`. */
    private const val COMPOSE_SOURCE_INFORMATION: String =
      "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"

    /**
     * [fallback] wrapped in a warm compiler when [DaemonProperties.warmCompile] is on and the
     * launch descriptor configured in-process compile for an eligible module; [fallback] itself
     * otherwise. Nothing is loaded until the first `compileSources`.
     */
    public fun wrapIfEnabled(
      fallback: BtaCompileService,
      sysprops: (String) -> String? = BtaSysprops::lookup,
    ): BtaCompileService {
      if (!DaemonProperties.warmCompile.read()) return fallback
      if (!sysprops(DefaultBtaCompileService.SYSPROP_INELIGIBILITY_REASON).isNullOrEmpty()) {
        return fallback
      }
      fun paths(key: String) =
        sysprops(key).orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
      val implClasspath = paths(DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH)
      val outputDir = sysprops(DefaultBtaCompileService.SYSPROP_OUTPUT_DIR)
      val icWorkingDir = sysprops(DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR)
      val moduleName = sysprops(DefaultBtaCompileService.SYSPROP_MODULE_NAME)
      if (
        implClasspath.isEmpty() ||
          outputDir.isNullOrEmpty() ||
          icWorkingDir.isNullOrEmpty() ||
          moduleName.isNullOrEmpty()
      ) {
        return fallback
      }
      val pluginJars = paths(DefaultBtaCompileService.SYSPROP_COMPILER_PLUGINS)
      val workDir = File(icWorkingDir, "warm")
      val config =
        mapOf(
          "compileClasspath" to paths(DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH),
          "classesDir" to outputDir,
          "moduleName" to moduleName,
          "stagingDir" to File(workDir, "staging").path,
          "pluginClasspath" to pluginJars,
          "pluginOptions" to
            if (pluginJars.isEmpty()) emptyList() else listOf(COMPOSE_SOURCE_INFORMATION),
        )
      return WarmCompileService(
        fallback,
        { ReflectiveWarmCompiler.load(implClasspath, workDir, config) },
      )
    }
  }
}

/** The warm compiler entry in a class loader over the compiler jars and the embedded impl jar. */
internal class ReflectiveWarmCompiler private constructor(private val entry: Any) :
  WarmCompileService.WarmCompiler {

  private val compileMethod = entry.javaClass.getMethod("compile", List::class.java)
  private val invalidateMethod = entry.javaClass.getMethod("invalidate")

  @Suppress("UNCHECKED_CAST")
  override fun compile(sources: List<String>): Map<String, Any?> =
    unwrap { compileMethod.invoke(entry, sources) } as Map<String, Any?>

  override fun invalidate() {
    unwrap { invalidateMethod.invoke(entry) }
  }

  private fun unwrap(call: () -> Any?): Any? =
    try {
      call()
    } catch (e: java.lang.reflect.InvocationTargetException) {
      throw e.targetException
    }

  companion object {
    fun load(
      implClasspath: List<String>,
      workDir: File,
      config: Map<String, Any?>,
    ): ReflectiveWarmCompiler {
      val urls = implClasspath.map { File(it).toURI().toURL() }
      val implJar = extractImplJar(workDir, compilerVersion(urls))
      // Parent is the platform loader: the compiler brings its own Kotlin stdlib, and nothing of
      // the daemon's class path may leak into it.
      val loader =
        URLClassLoader(
          (urls + implJar.toURI().toURL()).toTypedArray(),
          ClassLoader.getPlatformClassLoader(),
        )
      val entry =
        try {
          loader
            .loadClass(WarmCompileService.ENTRY_CLASS)
            .getConstructor(Map::class.java)
            .newInstance(config)
        } catch (e: java.lang.reflect.InvocationTargetException) {
          loader.close()
          throw e.targetException
        }
      return ReflectiveWarmCompiler(entry)
    }

    /**
     * The compiler version the Build Tools API impl jars carry, read without keeping them loaded.
     */
    private fun compilerVersion(urls: List<java.net.URL>): String =
      URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { probe ->
        probe
          .loadClass("org.jetbrains.kotlin.config.KotlinCompilerVersion")
          .getField("VERSION")
          .get(null) as String
      }

    private fun extractImplJar(workDir: File, version: String): File {
      val resource = WarmCompileService.implJarResource(version)
      val bytes =
        WarmCompileService::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
          ?: error("no warm compiler is bundled for Kotlin $version")
      workDir.mkdirs()
      val target = File(workDir, resource.substringAfterLast('/'))
      if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
      return target
    }
  }
}
