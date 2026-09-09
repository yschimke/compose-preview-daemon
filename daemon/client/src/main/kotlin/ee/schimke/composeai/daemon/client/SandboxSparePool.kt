package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.io.classpathArgFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pre-booted Android sandbox workers, kept warm ahead of demand and handed to the next daemon that
 * needs them (SANDBOX-POOL.md § "Spare workers"; the A1 item of BOOT-ROADMAP.md).
 *
 * A Robolectric sandbox does not depend on the catalog it renders: the catalog's classes ride the
 * disposable child classloader, and the boot-time warm render touches none of them. So a worker
 * booted and warm-rendered against *no* catalog can be adopted by whichever daemon needs a slot
 * next, paying that catalog's first real render (1-2 s, on a JVM where Compose, the font stack and
 * the PNG encoder are already hot) instead of a ~4 s boot plus a ~6 s warm render. The daemon
 * itself still boots its in-process sandbox — behind the adopted workers, off the request path.
 *
 * **Overlay signature.** What a worker *does* depend on is its parent classpath: a catalog's own
 * Compose/AndroidX overlay jars precede the daemon sidecar on the daemon `-cp`, and its Android
 * resource carriage rides there too. A spare is therefore generic only within one [signatureOf] —
 * the daemon classpath, the JVM flags and the boot-time system properties. Spares are pooled by
 * signature; catalogs on one Compose BOM share one, and a new signature pays a cold boot once
 * ("first can be slower") because [ensure] is demand-driven: every launch tops the signature it
 * just used back up to [Config.perSignature].
 *
 * **Lifecycle.** A spare is a child of this JVM. It halts when this JVM exits, when the pool evicts
 * it, or with the daemon that adopted it (`SandboxWorkerMain` watches both). [reserve] hands out
 * only spares that are warm and alive; a handed-out spare is the adopting daemon's from then on.
 *
 * Wired by whoever spawns daemons — [SubprocessDaemonClientFactory] takes one and reserves spares
 * for every Android launch with a pool of more than one sandbox. Threading: one boot at a time, on
 * the pool's own thread, exactly as a daemon boots its workers — two concurrent Robolectric
 * bootstraps thrash a box.
 */
public class SandboxSparePool(
  private val config: Config = Config(),
  private val log: (String) -> Unit = { System.err.println("compose-ai-spares: $it") },
  /** Seam for tests: how a spare JVM is launched. Production runs [ProcessBuilder]. */
  private val launcher: (command: List<String>, workingDirectory: File) -> Process =
    { command, dir ->
      ProcessBuilder(command)
        .directory(dir)
        .redirectErrorStream(false)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    },
) : AutoCloseable {

  public data class Config(
    /**
     * Ceiling on spares alive at once, warm or booting, across every signature. Each is a whole
     * Robolectric JVM (~450-570 MB resident once warm), so this is a memory budget first.
     */
    val maxSpares: Int = DEFAULT_MAX_SPARES,
    /** How many spares [ensure] keeps warm per signature — the workers one daemon would adopt. */
    val perSignature: Int = DEFAULT_PER_SIGNATURE,
    /** Wall-clock budget for a spare's JVM launch, sandbox boot, warm render and handshake. */
    val bootTimeoutMs: Long = DaemonProperties.sandboxBootTimeoutMs.read(),
    /** Where spare JVMs run; they write nothing there, but a daemon's own tree may be deleted. */
    val workingDirectory: File = File(System.getProperty("java.io.tmpdir")),
  )

  /**
   * One spare JVM, from the moment it is queued for launch. [process] is set once the boot thread
   * reaches it, [port] once the handshake arrived; until then it counts as booting, and against the
   * budget — which is what keeps a repeated [ensure] from queueing the same spare twice.
   */
  private class Spare(val signature: String, val archiveSlot: Int) {
    @Volatile var process: Process? = null
    @Volatile var port: Int? = null
    @Volatile var pid: Long? = null
    val alive: Boolean
      get() = process?.isAlive ?: true
  }

  public data class Snapshot(
    val warm: Int,
    val booting: Int,
    val signatures: Int,
    val adopted: Long,
    val coldLaunches: Long,
  )

  private val lock = ReentrantLock()
  private val spares = mutableListOf<Spare>() // guarded by [lock]
  private val lastReserved = mutableMapOf<String, Long>() // signature → nanoTime; guarded by [lock]
  private var adopted = 0L
  private var coldLaunches = 0L
  @Volatile private var closed = false
  private val booter = Executors.newSingleThreadExecutor { r ->
    Thread(r, "compose-ai-spare-boot").apply { isDaemon = true }
  }

  /**
   * Take up to [wanted] warm spares for [descriptor]'s signature, removing them from the pool, and
   * return their loopback ports for `composeai.daemon.sandboxWorker.spares`. Empty when the
   * signature has none warm yet — the caller then launches an ordinary cold daemon. Never blocks on
   * a boot: a spare still booting is not handed out.
   */
  public fun reserve(descriptor: DaemonLaunchDescriptor, wanted: Int): List<Int> {
    if (wanted <= 0 || closed) return emptyList()
    val signature = signatureOf(descriptor)
    return lock.withLock {
      lastReserved[signature] = System.nanoTime()
      val ready =
        spares.filter { it.signature == signature && it.port != null && it.alive }.take(wanted)
      spares.removeAll(ready)
      if (ready.isEmpty()) coldLaunches++ else adopted += ready.size
      ready.mapNotNull { it.port }
    }
  }

  /**
   * Keep [Config.perSignature] spares warm for [descriptor]'s signature, launching the missing ones
   * one at a time in the background. Within the total budget: a spare of the least recently
   * reserved *other* signature is evicted to make room, and when every spare belongs to signatures
   * reserved more recently than this one, nothing is launched. Idempotent — call it on every
   * launch.
   */
  public fun ensure(descriptor: DaemonLaunchDescriptor) {
    if (closed || config.maxSpares <= 0 || config.perSignature <= 0) return
    val signature = signatureOf(descriptor)
    val queued = lock.withLock {
      lastReserved.putIfAbsent(signature, System.nanoTime())
      pruneDead()
      val have = spares.count { it.signature == signature }
      var room = config.maxSpares - spares.size
      val missing = config.perSignature - have
      while (missing > room) {
        val victim =
          spares
            .filter { it.signature != signature && it.port != null }
            .minByOrNull { lastReserved[it.signature] ?: 0L } ?: break
        if ((lastReserved[victim.signature] ?: 0L) > (lastReserved[signature] ?: 0L)) break
        evict(victim, "evicted for signature $signature")
        room++
      }
      List(minOf(missing, room).coerceAtLeast(0)) {
        val used = spares.filter { it.signature == signature }.map { it.archiveSlot }.toSet()
        val slot = (0 until config.perSignature).firstOrNull { it !in used } ?: spares.size
        Spare(signature, slot).also { spares += it }
      }
    }
    // One boot at a time, on the pool's thread — the queue is the serialisation.
    queued.forEach { spare -> booter.execute { runCatching { launchSpare(spare, descriptor) } } }
  }

  /**
   * The identity a spare is generic within: the daemon classpath in order, the JVM flags (minus the
   * class-data-sharing archive path, which is per JVM), the launcher, and the system properties
   * that shape the sandbox boot. Everything else about a catalog reaches the worker at adoption.
   */
  public fun signatureOf(descriptor: DaemonLaunchDescriptor): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun feed(s: String) {
      digest.update(s.toByteArray(Charsets.UTF_8))
      digest.update(0)
    }
    feed(descriptor.javaLauncher ?: "")
    descriptor.classpath.forEach(::feed)
    descriptor.jvmArgs.filterNot { it.startsWith(SHARED_ARCHIVE_FLAG) }.forEach(::feed)
    bootSystemProperties(descriptor).toSortedMap().forEach { (k, v) -> feed("$k=$v") }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }

  public fun snapshot(): Snapshot = lock.withLock {
    pruneDead()
    Snapshot(
      warm = spares.count { it.port != null },
      booting = spares.count { it.port == null },
      signatures = spares.map { it.signature }.distinct().size,
      adopted = adopted,
      coldLaunches = coldLaunches,
    )
  }

  override fun close() {
    closed = true
    booter.shutdownNow()
    val all = lock.withLock { spares.toList().also { spares.clear() } }
    all.forEach { it.process?.destroyForcibly() }
  }

  // ---- internals -----------------------------------------------------------------------------

  private fun launchSpare(spare: Spare, descriptor: DaemonLaunchDescriptor) {
    // Dropped while queued (the pool closed): nothing to launch.
    if (closed || lock.withLock { spare !in spares }) return
    val signature = spare.signature
    val command = spareCommand(descriptor, spare.archiveSlot)
    val process =
      try {
        launcher(command, config.workingDirectory)
      } catch (t: Throwable) {
        log("could not launch a spare for $signature: ${t.javaClass.simpleName}: ${t.message}")
        lock.withLock { spares.remove(spare) }
        return
      }
    spare.process = process
    pump(process.errorStream, "spare-$signature")
    // The handshake is the one stdout line we parse; everything else is diagnostics.
    val handshake = awaitHandshake(spare, process)
    if (handshake == null) {
      log("spare for $signature did not come up within ${config.bootTimeoutMs}ms; killing it")
      lock.withLock { spares.remove(spare) }
      process.destroyForcibly()
      return
    }
    spare.pid = handshake.first
    spare.port = handshake.second
    log("spare for $signature warm (pid=${handshake.first}, port=${handshake.second})")
  }

  /**
   * Reads the spare's stdout until [SPARE_HANDSHAKE_PREFIX] appears, then keeps pumping it to
   * stderr. Returns `(pid, port)`, or `null` on timeout or EOF (the spare died booting).
   */
  private fun awaitHandshake(spare: Spare, process: Process): Pair<Long, Int>? {
    val result = java.util.concurrent.CompletableFuture<Pair<Long, Int>?>()
    Thread(
        {
          try {
            process.inputStream.bufferedReader().useLines { lines ->
              for (line in lines) {
                if (!result.isDone && line.startsWith(SPARE_HANDSHAKE_PREFIX)) {
                  result.complete(parseHandshake(line))
                } else {
                  System.err.println("[spare-${spare.signature}] $line")
                }
              }
            }
          } finally {
            result.complete(null)
          }
        },
        "compose-ai-spare-${spare.signature}-stdout",
      )
      .apply {
        isDaemon = true
        start()
      }
    return try {
      result.get(config.bootTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {
      null
    }
  }

  private fun pump(stream: java.io.InputStream, tag: String) {
    Thread(
        {
          stream.bufferedReader().useLines { lines ->
            lines.forEach { System.err.println("[$tag] $it") }
          }
        },
        "compose-ai-$tag-stderr",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  /** Caller holds [lock]. Drops spares whose JVM is gone (a boot failure, or a kill). */
  private fun pruneDead() {
    spares.removeAll { !it.alive }
  }

  /** Caller holds [lock]. */
  private fun evict(spare: Spare, why: String) {
    spares.remove(spare)
    spare.process?.destroyForcibly()
    log("spare for ${spare.signature} (pid=${spare.pid}) $why")
  }

  /**
   * The spare's argv: the daemon's own launcher, flags and classpath, the boot-time system
   * properties, and the worker entry point in spare mode. Pure; the test reads it.
   */
  internal fun spareCommand(descriptor: DaemonLaunchDescriptor, archiveSlot: Int): List<String> =
    buildList {
      add(descriptor.javaLauncher ?: File(System.getProperty("java.home"), "bin/java").absolutePath)
      // One class-data-sharing archive per spare slot, like the daemon gives each worker slot:
      // two JVMs dumping into one file at exit is a torn archive.
      descriptor.jvmArgs.forEach { arg ->
        if (arg.startsWith(SHARED_ARCHIVE_FLAG)) {
          val path = arg.removePrefix(SHARED_ARCHIVE_FLAG)
          val stem = path.removeSuffix(".jsa")
          val ext = if (path.endsWith(".jsa")) ".jsa" else ""
          add("$SHARED_ARCHIVE_FLAG$stem-spare$archiveSlot$ext")
        } else add(arg)
      }
      bootSystemProperties(descriptor).forEach { (k, v) -> add("-D$k=$v") }
      add("-D${DaemonProperties.Names.SANDBOX_WORKER_SPARE}=true")
      add("-D${DaemonProperties.Names.SANDBOX_COUNT}=1")
      add("-D${DaemonProperties.Names.BACKGROUND_SANDBOX_BOOT}=false")
      add(classpathArgFile(descriptor.classpath))
      add(SANDBOX_WORKER_MAIN_CLASS)
    }

  /**
   * The system properties a sandbox boot depends on, and therefore the only ones a spare is
   * launched with: Robolectric's own (`robolectric.*`: SDK pin, offline mode, dependency dir,
   * graphics mode), `android.*` / `roborazzi.*`, and the daemon knobs the sandbox runner or the JVM
   * reads before any catalog is known. Everything else — class dirs, the previews manifest, output
   * and data directories, the IR dir — is the catalog's, and arrives at adoption.
   */
  internal fun bootSystemProperties(descriptor: DaemonLaunchDescriptor): Map<String, String> =
    descriptor.systemProperties.filterKeys { key ->
      BOOT_PROPERTY_PREFIXES.any { key.startsWith(it) } || key in BOOT_PROPERTIES
    }

  public companion object {
    public const val DEFAULT_MAX_SPARES: Int = 4
    public const val DEFAULT_PER_SIGNATURE: Int = 2

    /** Mirrors `SandboxWorkerMain.SPARE_HANDSHAKE_PREFIX`; the two must agree as bytes. */
    public const val SPARE_HANDSHAKE_PREFIX: String = "composeai-spare-worker: listening"

    /** `ee.schimke.composeai.daemon.pool.SandboxWorkerMain` — the worker entry point. */
    public const val SANDBOX_WORKER_MAIN_CLASS: String =
      "ee.schimke.composeai.daemon.pool.SandboxWorkerMain"

    private const val SHARED_ARCHIVE_FLAG = "-XX:SharedArchiveFile="

    private val BOOT_PROPERTY_PREFIXES = listOf("robolectric.", "android.", "roborazzi.")

    private val BOOT_PROPERTIES =
      setOf(
        DaemonProperties.Names.USER_CLASS_PACKAGES,
        DaemonProperties.Names.USE_CONSUMER_APPLICATION,
        DaemonProperties.Names.MAX_HEAP_MB,
        DaemonProperties.Names.SANDBOX_BOOT_TIMEOUT_MS,
        DaemonProperties.Names.WARM_RENDER_ON_BOOT,
        DaemonProperties.Names.STARTUP_QUIET,
      )

    private val HANDSHAKE = Regex("""pid=(\d+) port=(\d+)""")

    internal fun parseHandshake(line: String): Pair<Long, Int>? {
      val m = HANDSHAKE.find(line) ?: return null
      return m.groupValues[1].toLong() to m.groupValues[2].toInt()
    }
  }
}
