package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.bta.BtaCompileSession
import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption

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
 *
 * Where no warm compiler can run, [warmUp] warms [fallback] instead, through [fallbackWarmUp].
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
  /**
   * Warms [fallback] — loads the Build Tools API toolchain, snapshots the class path — for a
   * [warmUp] no warm compiler can serve. Returns null when warmed, otherwise why not. Null when
   * nothing can warm the fallback (it was built elsewhere).
   */
  private val fallbackWarmUp: (() -> String?)? = null,
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

  /** Whether [fallback] has been warmed, or has compiled, so a warm-up has nothing left to do. */
  private var fallbackWarm = false

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
   * Where no warm compiler can run — none bundled for this Kotlin, one this compiler refuses, or
   * `composeai.daemon.warmCompile=false` — every edit goes to [fallback], so it warms that instead
   * ([warmFallback]).
   *
   * @return [WarmUp.Ready]; [WarmUp.FallbackReady] when it warmed the Build Tools API instead; or
   *   [WarmUp.Unavailable] when neither could be warmed — the first edit then goes to the Build
   *   Tools API cold, as without a warm-up — or no pass compiled.
   */
  public fun warmUp(moduleSources: List<String> = emptyList()): WarmUp {
    val start = System.nanoTime()
    fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
    val budgetNs = warmUpBudgetMs * 1_000_000
    val warm =
      synchronized(this) {
        disabledReason?.let {
          return warmFallback(it, start)
        }
        compiler ?: loadCompiler() ?: return warmFallback(disabledReason!!, start)
      }
    val candidates =
      moduleSources.distinct().filterTo(ArrayList()) { it.endsWith(".kt") && File(it).isFile }
    val generated by lazy { warmUpSources() }
    val warmedWith = LinkedHashSet<String>()
    var passes = 0
    var next = 0
    var failure: String? = null
    var interrupted = false
    // The budget runs from the end of the first pass. That pass builds the class-path state,
    // which costs whatever the module's class path costs (12-22 s on a 750-entry Android one);
    // counting it would leave a large module a single pass and a cold JIT.
    var jitPassesFrom = 0L
    val passMs = ArrayList<Long>()
    while (
      passes < warmUpMaxPasses && (passes == 0 || System.nanoTime() - jitPassesFrom < budgetNs)
    ) {
      if (waitingCompiles.get() > 0) {
        interrupted = true
        break
      }
      val files =
        if (candidates.isEmpty()) generated else listOf(candidates[next++ % candidates.size])
      val passStart = System.nanoTime()
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
      passMs += (System.nanoTime() - passStart) / 1_000_000
      if (passes++ == 0) jitPassesFrom = System.nanoTime()
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
      // A link error turned the warm compiler off for good: the edits go to the fallback now.
      synchronized(this) { disabledReason }
        ?.let { if (!interrupted) return warmFallback(it, start) }
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
        "(${ready.passes} passes over ${ready.files} files; ${describePasses(passMs)}" +
        (if (interrupted) "; stopped for an edit)" else ")")
    )
    return ready
  }

  /**
   * Warms [fallback] for a module no warm compiler serves ([reason] says why): loads the Build
   * Tools API toolchain, snapshots every class-path entry and JIT-warms its compile, all of which
   * the first edit pays for otherwise — 20 s on a 377-entry Android class path. One compile of a
   * generated file, into a scratch directory, installing nothing ([BtaFallbackWarmUp]).
   *
   * It is a single pass, under the lock: an edit that arrives meanwhile waits for it, and then
   * finds the fallback warm, which is no later than the edit would have finished compiling cold.
   * Done once; a warm-up after it, or after any fallback compile, answers at once.
   */
  private fun warmFallback(reason: String, start: Long): WarmUp {
    fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
    val warmUp = fallbackWarmUp ?: return WarmUp.Unavailable(reason, elapsedMs())
    synchronized(this) {
      if (!fallbackWarm) {
        val failure =
          try {
            warmUp()
          } catch (t: Throwable) {
            t.toString()
          }
        if (failure != null) {
          log("compose-ai-daemon: Build Tools API warm-up failed: $failure")
          return WarmUp.Unavailable(
            "$reason; warming the Build Tools API failed: $failure",
            elapsedMs(),
          )
        }
        fallbackWarm = true
        log("compose-ai-daemon: Build Tools API warmed in ${elapsedMs()} ms ($reason)")
      }
    }
    return WarmUp.FallbackReady(elapsedMs(), reason)
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

    /**
     * No warm compiler can run here, for [reason], so the Build Tools API fallback every edit goes
     * to was warmed instead.
     */
    public data class FallbackReady(override val durationMs: Long, val reason: String) : WarmUp

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
    // Whatever the outcome, the compile loaded the toolchain and snapshotted the class path.
    fallbackWarm = true
    if (outcome is BtaCompileService.Outcome.Ok) compiledSinceFallback.clear()
    // The Build Tools API rewrote classes the warm compiler's library session may have read.
    compiler?.let { runCatching { it.invalidate() } }
    return outcome
  }

  /**
   * The first pass, which builds the class-path state, apart from the rest, which cost what an edit
   * costs: a warm-up's [WarmUp.durationMs] over its pass count reads several times an edit's cost
   * on a large class path otherwise. Times include waiting for the lock.
   */
  private fun describePasses(passMs: List<Long>): String {
    val first = "first pass ${passMs.firstOrNull() ?: 0} ms"
    val rest = passMs.drop(1).sorted()
    if (rest.isEmpty()) return first
    return "$first, then median ${rest[rest.size / 2]} ms, last ${passMs.last()} ms"
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

    /**
     * No new warm-up pass once the passes after the first have run this long, so a slow machine is
     * not busy for long. The first pass, which builds the class-path state, is not counted.
     */
    internal const val WARM_UP_BUDGET_MS: Long = 6_000

    /** The Compose option the Build Tools API path passes too; see `composeCompilerPlugins`. */
    private const val COMPOSE_SOURCE_INFORMATION: String =
      "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"

    /**
     * The in-process compile service for the module the `composeai.daemon.bta.*` system properties
     * describe, or null when they configure none: the Build Tools API compile
     * ([DefaultBtaCompileService]), with the warm compiler in front when
     * [DaemonProperties.warmCompile] is on. What `DaemonMain` uses.
     *
     * Unlike [wrapIfEnabled], it builds the fallback's [BtaCompileSession] itself, so a
     * `compileWarmUp` on a module no warm compiler serves can warm that very session
     * ([BtaFallbackWarmUp]). That holds with the warm compiler turned off too: the service is then
     * the fallback behind a warm-up, and compiles exactly as the fallback alone. Nothing is loaded
     * until the first `compileSources` or `compileWarmUp`.
     */
    public fun fromSysprops(
      sysprops: (String) -> String? = BtaSysprops::lookup
    ): BtaCompileService? {
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
        return null
      }
      val compileClasspath =
        paths(DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH).map { Path.of(it) }
      val plugins = composeCompilerPlugins(paths(DefaultBtaCompileService.SYSPROP_COMPILER_PLUGINS))
      val ineligibility =
        sysprops(DefaultBtaCompileService.SYSPROP_INELIGIBILITY_REASON)?.takeIf { it.isNotEmpty() }
      val session =
        BtaCompileSession(
          implClasspath = implClasspath.map { Path.of(it) },
          icWorkingDir = Path.of(icWorkingDir),
          moduleName = moduleName,
        )
      val fallback =
        DefaultBtaCompileService.forSession(
          session = session,
          compileClasspath = compileClasspath,
          outputDir = Path.of(outputDir),
          compilerPlugins = plugins,
          ineligibilityReason = ineligibility,
        )
      // An ineligible module never compiles in process: nothing to warm.
      if (ineligibility != null) return fallback
      val warmUp =
        BtaFallbackWarmUp(session, compileClasspath, plugins, Path.of(icWorkingDir))::warmUp
      return wrap(fallback, sysprops, warmUp)
        ?: WarmCompileService(
          fallback,
          { error("the warm compiler is off (${DaemonProperties.Names.WARM_COMPILE}=false)") },
          fallbackWarmUp = warmUp,
        )
    }

    /**
     * [fallback] wrapped in a warm compiler when [DaemonProperties.warmCompile] is on and the
     * launch descriptor configured in-process compile for an eligible module; [fallback] itself
     * otherwise. Nothing is loaded until the first `compileSources`.
     *
     * A `compileWarmUp` on a module no warm compiler serves cannot warm a [fallback] built
     * elsewhere; [fromSysprops] builds one it can.
     */
    public fun wrapIfEnabled(
      fallback: BtaCompileService,
      sysprops: (String) -> String? = BtaSysprops::lookup,
    ): BtaCompileService = wrap(fallback, sysprops, fallbackWarmUp = null) ?: fallback

    /** The warm compiler in front of [fallback], or null when it is off or cannot be configured. */
    private fun wrap(
      fallback: BtaCompileService,
      sysprops: (String) -> String?,
      fallbackWarmUp: (() -> String?)?,
    ): WarmCompileService? {
      if (!DaemonProperties.warmCompile.read()) return null
      if (!sysprops(DefaultBtaCompileService.SYSPROP_INELIGIBILITY_REASON).isNullOrEmpty()) {
        return null
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
        return null
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
        fallbackWarmUp = fallbackWarmUp,
      )
    }

    /**
     * The Compose compiler plugin as the Build Tools API compile takes it, with the
     * `sourceInformation=true` option KGP passes (`DefaultBtaCompileService.fromSysprops` builds
     * the same); none for a module without the plugin's jars.
     */
    internal fun composeCompilerPlugins(pluginJars: List<String>): List<CompilerPlugin> =
      if (pluginJars.isEmpty()) emptyList()
      else
        listOf(
          CompilerPlugin(
            "androidx.compose.compiler.plugins.kotlin",
            pluginJars.map { Path.of(it) },
            listOf(CompilerPluginOption("sourceInformation", "true")),
            emptySet(),
          )
        )
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
