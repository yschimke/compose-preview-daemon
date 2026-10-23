package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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
  /**
   * Writes the files [warmUp] compiles when the client names none of the module's; see
   * [WARM_UP_SOURCE].
   */
  private val warmUpSources: () -> List<String> = { emptyList() },
  /** At most this many warm-up passes, one file each... */
  private val warmUpMaxPasses: Int = WARM_UP_MAX_PASSES,
  /** ...and no new pass once the warm-up has run this long. */
  private val warmUpBudgetMs: Long = WARM_UP_BUDGET_MS,
) : BtaCompileService {

  /** The JDK-typed surface of the warm compiler entry, loaded in its own class loader. */
  internal interface WarmCompiler {
    fun compile(sources: List<String>): Map<String, Any?>

    /** One warm-up pass: compiles [sources] into a scratch directory and installs nothing. */
    fun warmUp(sources: List<String>): Map<String, Any?>

    fun invalidate()
  }

  private var compiler: WarmCompiler? = null
  private var disabledReason: String? = null
  private val compiledSinceFallback = LinkedHashSet<String>()

  /** Edits waiting for the lock: a warm-up stops after its current pass when there are any. */
  private val waitingCompiles = AtomicInteger()

  override fun compile(sources: List<Path>, changes: SourceChangeSet?): BtaCompileService.Outcome {
    waitingCompiles.incrementAndGet()
    try {
      return synchronized(this) { compileLocked(sources, changes) }
    } finally {
      waitingCompiles.decrementAndGet()
    }
  }

  private fun compileLocked(
    sources: List<Path>,
    changes: SourceChangeSet?,
  ): BtaCompileService.Outcome {
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
      } catch (e: LinkageError) {
        // A compiler of the bundled line whose internals moved since the jar was built: it would
        // fail the same way on every edit.
        disabledReason = e.toString()
        compiler = null
        log("compose-ai-daemon: warm compile disabled ($disabledReason); using the Build Tools API")
        return fallBack(sources, changes, null)
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
        val reason = result["reason"].toString()
        if (!disableOnLinkageError(result)) {
          log(
            "compose-ai-daemon: warm compile ${result["status"]} ($reason), using the Build Tools API"
          )
        }
        fallBack(sources, changes, null)
      }
    }
  }

  /**
   * Turns the warm compiler off for good when [result] is a link error the compiler caught and
   * reported as an internal error: as permanent as a thrown one. Whether it did.
   */
  private fun disableOnLinkageError(result: Map<String, Any?>): Boolean {
    val reason = result["reason"].toString()
    if (result["status"] != "failed" || !LINKAGE_ERROR.containsMatchIn(reason)) return false
    disabledReason = reason
    compiler = null
    log("compose-ai-daemon: warm compile disabled ($reason); using the Build Tools API")
    return true
  }

  /**
   * Loads the warm compiler and warms it ahead of the first edit, which otherwise pays for loading
   * it, building its class-path state, loading the library symbols the edited file uses and a cold
   * JIT: seconds, against a warm compile's 100-200 ms.
   *
   * Runs passes of one file each, cycling through [moduleSources] (the files the client expects to
   * edit; any that is missing, not Kotlin or does not compile is dropped) or, when there are none,
   * the generated [warmUpSources]. Most of a cold edit's cost is the JIT, which keeps improving for
   * dozens of compiles, so it runs up to [warmUpMaxPasses] passes within [warmUpBudgetMs]. Each
   * pass compiles into a scratch directory and installs nothing, so a Gradle build of the module
   * may run meanwhile. The lock is taken per pass, and an edit arriving stops the warm-up after the
   * current pass: the edit waits for one pass, never for the whole warm-up.
   *
   * @return [WarmUp.Ready], or [WarmUp.Unavailable] when no warm compiler can run here — the first
   *   edit then goes to the Build Tools API cold, as without a warm-up — or no pass compiled.
   */
  public fun warmUp(moduleSources: List<String> = emptyList()): WarmUp {
    val start = System.nanoTime()
    fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
    val warm =
      synchronized(this) {
        disabledReason?.let {
          return WarmUp.Unavailable(it, elapsedMs())
        }
        compiler ?: loadCompiler() ?: return WarmUp.Unavailable(disabledReason!!, elapsedMs())
      }
    val candidates =
      moduleSources.distinct().filterTo(ArrayList()) { it.endsWith(".kt") && File(it).isFile }
    val generated by lazy { warmUpSources() }
    val warmedWith = LinkedHashSet<String>()
    var passes = 0
    var next = 0
    var failure: String? = null
    var interrupted = false
    while (passes < warmUpMaxPasses && (passes == 0 || elapsedMs() < warmUpBudgetMs)) {
      if (waitingCompiles.get() > 0) {
        interrupted = true
        break
      }
      val files =
        if (candidates.isEmpty()) generated else listOf(candidates[next++ % candidates.size])
      val result =
        synchronized(this) {
          // Disabled, or reloaded after a failure, since the last pass.
          if (compiler !== warm) return@synchronized null
          try {
            warm.warmUp(files)
          } catch (t: Throwable) {
            runCatching { warm.invalidate() }
            mapOf("status" to "failed", "reason" to t.toString())
          }
        } ?: break
      passes++
      when (result["status"]) {
        "ok" -> warmedWith += files
        "errors" ->
          if (candidates.isNotEmpty()) {
            // A module file mid-edit: warm with the others, or the generated file.
            candidates -= files.toSet()
          } else {
            failure = "${result["reason"]}"
            break
          }
        else -> {
          failure = "${result["reason"]}"
          synchronized(this) { disableOnLinkageError(result) }
          break
        }
      }
    }
    if (warmedWith.isEmpty()) {
      // Not fatal: the first edit builds the state as it always did.
      val reason =
        if (interrupted) "an edit arrived before the first warm-up pass"
        else "warm-up failed: $failure"
      log("compose-ai-daemon: $reason")
      return WarmUp.Unavailable(reason, elapsedMs())
    }
    val ready = WarmUp.Ready(elapsedMs(), passes = passes, files = warmedWith.size)
    log(
      "compose-ai-daemon: warm compiler ready in ${ready.durationMs} ms " +
        "(${ready.passes} passes over ${ready.files} files" +
        (if (interrupted) ", stopped for an edit)" else ")")
    )
    return ready
  }

  /** What [warmUp] achieved. */
  public sealed interface WarmUp {
    public val durationMs: Long

    /** Warm, after [passes] passes over [files] distinct files. */
    public data class Ready(
      override val durationMs: Long,
      val passes: Int = 1,
      val files: Int = 1,
    ) : WarmUp

    public data class Unavailable(val reason: String, override val durationMs: Long) : WarmUp
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

    /**
     * The bundled impl jar for compiler [version]: one per Kotlin minor line, so `2.4.0` and
     * `2.4.21` share `warm-compiler-impl-2.4.jar`.
     */
    internal fun implJarResource(version: String): String =
      "warm/warm-compiler-impl-${minorOf(version)}.jar"

    /** The Kotlin minor line of compiler [version]: `2.4` for `2.4.21`, `2.5` for `2.5.0-Beta2`. */
    internal fun minorOf(version: String): String = version.split('.').take(2).joinToString(".")

    /** A link error from the compiler's internals, as the compiler reports one it caught. */
    private val LINKAGE_ERROR =
      Regex(
        "java\\.lang\\." +
          "(LinkageError|NoClassDefFoundError|IncompatibleClassChangeError|NoSuchMethodError|" +
          "NoSuchFieldError|AbstractMethodError|IllegalAccessError|InstantiationError|" +
          "VerifyError|ClassFormatError|UnsupportedClassVersionError|ClassCircularityError|" +
          "BootstrapMethodError|ExceptionInInitializerError|UnsatisfiedLinkError)\\b"
      )

    /**
     * What [warmUp] compiles: one composable, so the Compose plugin's lowerings JIT too. Private
     * and in a package of its own, so nothing it declares can meet the module's classes.
     */
    internal const val WARM_UP_SOURCE: String =
      """
      package ee.schimke.composeai.daemon.warmup

      @androidx.compose.runtime.Composable
      private fun WarmUp(items: List<String>, onClick: () -> Unit) {
        for ((index, item) in items.withIndex()) {
          if (item.isNotEmpty()) WarmUp(listOf("${'$'}index: ${'$'}item"), onClick) else onClick()
        }
      }
      """

    /**
     * Warm-up passes at most. The JIT keeps improving for dozens of compiles. On a Material 3
     * screen (4-core sandbox), the first edits after warming with that screen's own file took
     * 235-280 ms after 2 passes and 103-120 ms after 60 (8 s), steady at 86-103 ms; warming with
     * other files of the module instead left the first edit at 177-196 ms, loading the symbols only
     * it uses.
     */
    internal const val WARM_UP_MAX_PASSES: Int = 60

    /** No new warm-up pass once it has run this long, so a slow machine is not busy for long. */
    internal const val WARM_UP_BUDGET_MS: Long = 6_000

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
        warmUpSources = {
          val file = File(workDir, "warm-up/WarmUp.kt")
          file.parentFile.mkdirs()
          file.writeText(WARM_UP_SOURCE.trimIndent())
          listOf(file.path)
        },
      )
    }
  }
}

/** The warm compiler entry in a class loader over the compiler jars and the embedded impl jar. */
internal class ReflectiveWarmCompiler private constructor(private val entry: Any) :
  WarmCompileService.WarmCompiler {

  private val compileMethod = entry.javaClass.getMethod("compile", List::class.java)
  private val warmUpMethod = entry.javaClass.getMethod("warmUp", List::class.java)
  private val invalidateMethod = entry.javaClass.getMethod("invalidate")

  @Suppress("UNCHECKED_CAST")
  override fun compile(sources: List<String>): Map<String, Any?> =
    unwrap { compileMethod.invoke(entry, sources) } as Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  override fun warmUp(sources: List<String>): Map<String, Any?> =
    unwrap { warmUpMethod.invoke(entry, sources) } as Map<String, Any?>

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
          ?: error(
            "no warm compiler is bundled for Kotlin ${WarmCompileService.minorOf(version)} ($version)"
          )
      workDir.mkdirs()
      val target = File(workDir, resource.substringAfterLast('/'))
      if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
      return target
    }
  }
}
