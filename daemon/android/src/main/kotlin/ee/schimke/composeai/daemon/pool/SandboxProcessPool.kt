package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Out-of-process sandbox pool (issue #3072) — the parent half.
 *
 * Robolectric's native-graphics runtime binds to a **single classloader per process**: it loads
 * `libandroid_runtime.so` once and registers its JNI natives against whichever sandbox's
 * instrumented framework classes reach it first. A second sandbox in the same JVM boots with a
 * `Typeface` whose system font map never populated, and the first native call that touches it takes
 * the whole process down with a `SIGSEGV`. That is a hard, load-once-per-JVM constraint, not a
 * version bug — so extra sandboxes get extra **processes**.
 *
 * Each worker is a plain JVM (`java -cp <this JVM's classpath> …SandboxWorkerMain`) hosting exactly
 * one Robolectric sandbox, talking newline-delimited JSON ([WorkerRequest] / [WorkerResponse]) over
 * a loopback socket that the *parent* listens on and the worker dials back. Listening in the parent
 * means no port has to be agreed in advance and a worker that dies before connecting fails the
 * accept with a clear diagnostic rather than hanging on a connect retry loop.
 *
 * Threading: one outstanding request per worker, guarded by that worker's [Worker.lock]. That is
 * the same contract an in-JVM slot had — a sandbox renders one preview at a time — so the pool adds
 * no new concurrency semantics, only a process boundary.
 */
class SandboxProcessPool(
  /** Number of worker processes; equals `sandboxCount - 1` (slot 0 stays in the daemon JVM). */
  val workerCount: Int,
  /** Wall-clock budget for a worker's `java` launch + Robolectric boot + ready handshake. */
  private val bootTimeoutMs: Long,
  /**
   * Test seam: extra system properties handed to every worker. Production passes nothing — workers
   * inherit the daemon's own `composeai.*` / `robolectric.*` properties (see [workerSysprops]).
   */
  private val extraSysprops: Map<String, String> = emptyMap(),
  /**
   * Loopback ports of pre-booted **spare** workers reserved for this daemon (SANDBOX-POOL.md §
   * "Spare workers"), from `composeai.daemon.sandboxWorker.spares`. [bootWorker] adopts one of
   * these — connect, [WorkerRequest.Configure], done — before it falls back to launching and
   * booting a worker of its own. Each port is tried at most once: a spare that refuses the
   * connection or fails to configure is skipped, and the slot boots cold.
   */
  spareEndpoints: List<Int> = emptyList(),
) : AutoCloseable {

  private class Worker(
    val index: Int,
    /** The JVM we launched, or `null` for an adopted spare, which someone else spawned. */
    val process: Process?,
    val socket: Socket,
    val reader: BufferedReader,
    val writer: BufferedWriter,
    val pid: Long,
    /** Whether this slot was filled by adopting a spare rather than by a cold boot. */
    val adopted: Boolean,
  ) {
    val lock = ReentrantLock()
    @Volatile var dead: Boolean = false

    /**
     * Either way there is a process to wait on or kill; a spare is reached through its pid. By pid
     * for both — this module compiles against the Android stubs, which know `ProcessHandle` but not
     * `Process.toHandle()`.
     */
    val handle: ProcessHandle?
      get() = ProcessHandle.of(pid).orElse(null)

    fun destroyForcibly() {
      process?.destroyForcibly() ?: handle?.destroyForcibly()
    }

    /** Blocks up to [timeoutMs] for the process to exit; true if it did (or was never found). */
    fun waitFor(timeoutMs: Long): Boolean {
      process?.let {
        return it.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      }
      val h = handle ?: return true
      return try {
        h.onExit().get(timeoutMs, TimeUnit.MILLISECONDS)
        true
      } catch (_: java.util.concurrent.TimeoutException) {
        false
      } catch (_: Throwable) {
        true
      }
    }
  }

  private val workers = arrayOfNulls<Worker>(workerCount)

  /** Spares not yet tried, in the order the launcher reserved them. Guarded by [serverLock]. */
  private val untriedSpares = ArrayDeque(spareEndpoints)

  /** Spare endpoints handed in at construction; a daemon with none boots every worker cold. */
  val spareEndpointCount: Int = spareEndpoints.size

  /** Whether a spare is still available to adopt (none has been tried for it yet). */
  fun hasUntriedSpare(): Boolean = serverLock.withLock { untriedSpares.isNotEmpty() }

  /**
   * Whether worker [index] was filled by adopting a spare. False for a cold boot or an empty slot.
   */
  fun isAdopted(index: Int): Boolean = workers.getOrNull(index)?.adopted == true

  /**
   * Bound lazily on the first [bootWorker] so a host that never starts its pool (`sandboxCount =
   * 1`, or a host constructed but never started) opens no socket at all. Loopback-bound: workers
   * are always local children.
   */
  private var serverSocket: ServerSocket? = null

  private val serverLock = ReentrantLock()

  @Volatile private var closed = false

  private fun ensureServerSocket(): ServerSocket = serverLock.withLock {
    serverSocket
      ?: ServerSocket(0, workerCount + 4, InetAddress.getLoopbackAddress()).also {
        serverSocket = it
      }
  }

  /**
   * Fills worker slot [index] and blocks until it can take renders. Returns `true` when the slot
   * was filled by **adopting a spare** (a pre-booted, warm worker handed over by the launcher —
   * milliseconds), `false` when it had to spawn and boot a worker of its own.
   *
   * The adopt path is tried first while untried spares remain; any failure there is logged and
   * falls through to the cold path, so a stale spare list can only cost a connection attempt. The
   * cold path throws on launch failure, accept timeout, or a worker-side boot failure — the caller
   * decides whether that caps the pool (background boot) or aborts the host (eager boot), exactly
   * as the in-JVM path did.
   */
  fun bootWorker(index: Int): Boolean {
    if (adoptSpare(index)) return true
    bootColdWorker(index)
    return false
  }

  /**
   * The adopt half of [bootWorker] on its own: fill slot [index] from the untried spares, or return
   * `false` without booting anything. `RobolectricHost.start` uses this to take every spare it was
   * handed *before* its own in-process sandbox boots, so a daemon with spares answers `initialize`
   * in the time it takes to configure them.
   */
  fun adoptSpare(index: Int): Boolean {
    require(index in 0 until workerCount) {
      "worker index $index out of range 0..${workerCount - 1}"
    }
    check(!closed) { "SandboxProcessPool is closed" }
    while (true) {
      val port = serverLock.withLock { untriedSpares.removeFirstOrNull() } ?: break
      val adopted =
        try {
          adoptSpare(index, port)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: spare sandbox worker on port $port could not be adopted for slot " +
              "$index (${t.javaClass.simpleName}: ${t.message}); trying the next spare, else a cold boot"
          )
          continue
        }
      workers[index] = adopted
      System.err.println(
        "compose-ai-daemon: sandbox worker $index adopted a warm spare (pid=${adopted.pid}, port=$port)"
      )
      return true
    }
    return false
  }

  /**
   * Connect to a listening spare and hand it this daemon's catalog. The spare has already booted
   * and warm-rendered, so the round-trip is a socket connect plus a classloader swap on the far
   * side; [SPARE_CONNECT_TIMEOUT_MS] bounds the connect and the configure reply separately.
   */
  private fun adoptSpare(index: Int, port: Int): Worker {
    val socket = Socket()
    try {
      socket.connect(
        java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        SPARE_CONNECT_TIMEOUT_MS,
      )
      socket.tcpNoDelay = true
      socket.soTimeout = SPARE_CONFIGURE_TIMEOUT_MS
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
      val request =
        WorkerRequest.Configure(
          // Everything a cold worker would have inherited on its command line, minus the pool's
          // own coordinates: a spare has no parent port to dial, and its slot is this one.
          systemProperties = configureSysprops(index),
          parentPid = ProcessHandle.current().pid(),
        )
      writer.write(workerJson.encodeToString(WorkerRequest.serializer(), request))
      writer.write("\n")
      writer.flush()
      val reply = readResponse(reader)
      val pid =
        (reply as? WorkerResponse.Configured)?.pid
          ?: error("spare worker on port $port answered configure with $reply")
      socket.soTimeout = 0
      return Worker(index, process = null, socket, reader, writer, pid, adopted = true)
    } catch (t: Throwable) {
      runCatching { socket.close() }
      throw t
    }
  }

  /** The original path: spawn a worker JVM, wait for it to dial back and report ready. */
  private fun bootColdWorker(index: Int) {
    val server = ensureServerSocket()
    val process = launchWorkerProcess(index, server.localPort)
    val socket =
      try {
        server.soTimeout = bootTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        server.accept()
      } catch (t: Throwable) {
        process.destroyForcibly()
        throw IllegalStateException(
          "sandbox worker $index never connected back within ${bootTimeoutMs}ms " +
            "(exited=${!process.isAlive}); see the [sandbox-worker-$index] stderr above.",
          t,
        )
      }
    socket.tcpNoDelay = true
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    // The worker boots its sandbox *after* connecting, so the ready line can take as long as a
    // Robolectric bootstrap. Reuse the boot budget for it rather than a socket-level default.
    socket.soTimeout = bootTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hello =
      try {
        readResponse(reader)
      } catch (t: Throwable) {
        process.destroyForcibly()
        throw IllegalStateException("sandbox worker $index died before reporting ready", t)
      }
    when (hello) {
      is WorkerResponse.Ready -> {
        socket.soTimeout = 0
        workers[index] = Worker(index, process, socket, reader, writer, hello.pid, adopted = false)
        System.err.println(
          "compose-ai-daemon: sandbox worker $index ready (pid=${hello.pid}, port=${server.localPort})"
        )
      }
      is WorkerResponse.BootFailed -> {
        process.destroyForcibly()
        error("sandbox worker $index failed to boot its Robolectric sandbox: ${hello.diagnostic}")
      }
      else -> {
        process.destroyForcibly()
        error("sandbox worker $index sent $hello before reporting ready")
      }
    }
  }

  /** True once [bootWorker] has completed for [index] and the worker is still alive. */
  fun isReady(index: Int): Boolean = workers.getOrNull(index)?.let { !it.dead } == true

  /** Worker process ids, in slot order; `null` for slots that never booted. Diagnostics + tests. */
  fun workerPids(): List<Long?> = workers.map { if (it?.dead == false) it.pid else null }

  /**
   * Dispatches [request] to worker [index] and blocks for its result. Re-throws a worker-side
   * render failure as a [RemoteSandboxRenderException] carrying the worker's flattened cause chain,
   * so `JsonRpcServer`'s existing Throwable path turns it into the same typed `renderFailed` an
   * in-process failure produces.
   */
  fun submit(index: Int, request: RenderRequest.Render, timeoutMs: Long): RenderResult {
    val worker =
      workers.getOrNull(index)?.takeIf { !it.dead }
        ?: error("sandbox worker $index is not ready (pool of $workerCount)")
    return worker.lock.withLock {
      val response =
        exchange(
          worker,
          WorkerRequest.Render(id = request.id, payload = request.payload, timeoutMs = timeoutMs),
          // Give the socket read a margin over the render budget so a worker that answers just
          // inside its own deadline still beats ours.
          readTimeoutMs = timeoutMs + SOCKET_READ_MARGIN_MS,
        )
      when (response) {
        is WorkerResponse.Result -> response.result.toRenderResult()
        is WorkerResponse.Failed -> throw RemoteSandboxRenderException(response.diagnostic)
        else -> error("sandbox worker $index answered a render with $response")
      }
    }
  }

  /**
   * Broadcasts `swapUserClassLoaders` to every live worker. Best-effort per worker: a worker that
   * fails to answer is marked dead and logged rather than failing the whole hot-reload — the host
   * keeps serving on its remaining slots.
   */
  fun swapUserClassLoaders() {
    for (worker in workers) {
      if (worker == null || worker.dead) continue
      try {
        worker.lock.withLock {
          exchange(worker, WorkerRequest.Swap, readTimeoutMs = SWAP_TIMEOUT_MS)
        }
      } catch (t: Throwable) {
        markDead(worker, "classloader swap failed", t)
      }
    }
  }

  /** Politely stops every worker, then force-kills anything still alive after [timeoutMs]. */
  fun shutdown(timeoutMs: Long) {
    closed = true
    for (worker in workers) {
      if (worker == null) continue
      runCatching {
        worker.lock.withLock {
          if (!worker.dead) exchange(worker, WorkerRequest.Shutdown, readTimeoutMs = timeoutMs)
        }
      }
      runCatching { worker.socket.close() }
      if (!worker.waitFor(timeoutMs)) {
        System.err.println(
          "compose-ai-daemon: sandbox worker ${worker.index} (pid=${worker.pid}) did not exit " +
            "within ${timeoutMs}ms; killing"
        )
        worker.destroyForcibly()
      }
      worker.dead = true
    }
    runCatching { serverLock.withLock { serverSocket?.close() } }
  }

  override fun close() = shutdown(SHUTDOWN_TIMEOUT_MS)

  private fun markDead(worker: Worker, what: String, cause: Throwable) {
    worker.dead = true
    System.err.println(
      "compose-ai-daemon: sandbox worker ${worker.index} (pid=${worker.pid}) $what " +
        "(${cause.javaClass.simpleName}: ${cause.message}); dropping it from the pool"
    )
    runCatching { worker.socket.close() }
    runCatching { worker.destroyForcibly() }
  }

  private fun exchange(
    worker: Worker,
    request: WorkerRequest,
    readTimeoutMs: Long,
  ): WorkerResponse {
    try {
      worker.socket.soTimeout = readTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
      worker.writer.write(workerJson.encodeToString(WorkerRequest.serializer(), request))
      worker.writer.write("\n")
      worker.writer.flush()
      return readResponse(worker.reader)
    } catch (t: Throwable) {
      markDead(worker, "died mid-request", t)
      throw IllegalStateException(
        "sandbox worker ${worker.index} (pid=${worker.pid}) failed while handling $request",
        t,
      )
    }
  }

  private fun readResponse(reader: BufferedReader): WorkerResponse {
    val line = reader.readLine() ?: error("sandbox worker closed its socket (EOF)")
    return workerJson.decodeFromString(WorkerResponse.serializer(), line)
  }

  private fun launchWorkerProcess(index: Int, port: Int): Process {
    val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val command = buildList {
      add(java)
      addAll(workerJvmArgs(inheritedJvmArgs(), index))
      addAll(workerSysprops(index, port).map { (k, v) -> "-D$k=$v" })
      add("-cp")
      add(System.getProperty("java.class.path") ?: "")
      add(SandboxWorkerMain::class.java.name)
    }
    val process =
      ProcessBuilder(command)
        .redirectErrorStream(false)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    // The worker speaks its protocol over the socket, so both of its stdio streams are pure
    // diagnostics — pump them onto the daemon's stderr with a slot tag. Unpumped pipes fill and
    // deadlock a chatty Robolectric bootstrap, so this is load-bearing, not just nice logging.
    pumpToStderr(process.errorStream, "sandbox-worker-$index")
    pumpToStderr(process.inputStream, "sandbox-worker-$index")
    return process
  }

  private fun pumpToStderr(stream: java.io.InputStream, tag: String) {
    Thread(
        {
          stream.bufferedReader().useLines { lines ->
            for (line in lines) System.err.println("[$tag] $line")
          }
        },
        "compose-ai-$tag-log",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  /**
   * JVM flags the worker inherits from the daemon JVM: heap sizing, GC, `--add-opens`, and the
   * module flags Robolectric needs. Deliberately dropped: anything that must not be duplicated in a
   * second process — a debugger/JDWP port (would fail to bind), JaCoCo/agent attachments (would
   * write to the same exec file), and the pool's own properties, which [workerSysprops] re-derives.
   */
  private fun inheritedJvmArgs(): List<String> =
    ManagementFactory.getRuntimeMXBean().inputArguments.filter { arg ->
      when {
        arg.startsWith("-agentlib:") -> false
        arg.startsWith("-agentpath:") -> false
        arg.startsWith("-javaagent:") -> false
        arg.startsWith("-Xrunjdwp") -> false
        arg.startsWith("-D") -> false // re-derived below, minus the pool-control properties
        else -> true
      }
    }

  /**
   * System properties the worker needs. The `composeai.*` / `robolectric.*` / `android.*` families
   * carry everything that shapes a render — user-class dirs, the SDK pin, Robolectric's offline /
   * dependency-dir settings, feature flags — so forwarding them wholesale keeps a worker's render
   * configured identically to the daemon's own sandbox.
   *
   * Overridden per worker: `sandboxCount` is forced to 1 (a worker JVM hosts exactly one sandbox —
   * the whole point), background boot is off (the worker's own `start()` must block until its
   * sandbox is up, because that is what the ready handshake means), and the boot-time warm render
   * is left to the parent, which already warms each slot as it comes up.
   */
  private fun workerSysprops(index: Int, port: Int): Map<String, String> {
    val forwarded = forwardedSysprops()
    forwarded[SANDBOX_COUNT_PROP] = "1"
    forwarded[WORKER_PORT_PROP] = port.toString()
    forwarded[WORKER_SLOT_PROP] = index.toString()
    forwarded.putAll(extraSysprops)
    return forwarded
  }

  /**
   * What an adopted spare is handed over the socket: the same forwarded families as
   * [workerSysprops], without a port to dial back (it is already connected) and without the spare
   * marker it was launched with (it is a pooled worker from here on).
   */
  private fun configureSysprops(index: Int): Map<String, String> {
    val forwarded = forwardedSysprops()
    forwarded[SANDBOX_COUNT_PROP] = "1"
    forwarded[WORKER_SLOT_PROP] = index.toString()
    forwarded[DaemonProperties.Names.SANDBOX_WORKER_SPARE] = "false"
    forwarded.putAll(extraSysprops)
    return forwarded
  }

  private fun forwardedSysprops(): LinkedHashMap<String, String> = Companion.forwardedSysprops()

  companion object {
    const val WORKER_PORT_PROP: String = DaemonProperties.Names.SANDBOX_WORKER_PORT

    /**
     * The inherited JVM args, with the class-data-sharing archive re-pointed at a **per-slot**
     * file: `-XX:SharedArchiveFile=<name>.jsa` becomes `<name>-worker<index>.jsa`.
     *
     * Serve hands a catalog daemon `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=…` so the
     * JVM writes an archive of its loaded classes when it exits and maps it on the next launch of
     * the same classpath. Both halves of that reach the workers through the inherited `-XX` flags,
     * and both are wanted: the worker's archive is the one that carries the warm render's classes
     * (Compose, the font stack, the PNG encoder), which the daemon JVM's slot 0 never loads. What
     * must not be shared is the *file*. The dump runs from `before_exit`, so a worker that halts
     * because its parent died dumps at the same moment the parent does, and two JVMs writing one
     * archive path is a torn file. One file per JVM removes the race: a slot has at most one live
     * worker, and a replacement is spawned only after the failed one is gone.
     *
     * Pure; `index` is the pool slot. Args without a `SharedArchiveFile` pass through unchanged.
     */
    internal fun workerJvmArgs(inherited: List<String>, index: Int): List<String> =
      inherited.map { arg ->
        if (!arg.startsWith(SHARED_ARCHIVE_FLAG)) return@map arg
        val path = arg.removePrefix(SHARED_ARCHIVE_FLAG)
        val stem = path.removeSuffix(".jsa")
        val ext = if (path.endsWith(".jsa")) ".jsa" else ""
        "$SHARED_ARCHIVE_FLAG$stem-worker$index$ext"
      }

    private const val SHARED_ARCHIVE_FLAG = "-XX:SharedArchiveFile="
    const val WORKER_SLOT_PROP: String = DaemonProperties.Names.SANDBOX_WORKER_SLOT

    private const val SANDBOX_COUNT_PROP = DaemonProperties.Names.SANDBOX_COUNT

    private val FORWARDED_PREFIXES = listOf("composeai.", "robolectric.", "android.", "roborazzi.")

    /**
     * Pool-control properties a worker must never inherit verbatim: the pool size (a worker hosts
     * one sandbox), background boot (the ready handshake requires an eager boot), and the parent's
     * own worker coordinates when a worker somehow spawns from a worker.
     */
    private val WORKER_OVERRIDDEN_PROPS =
      setOf(
        SANDBOX_COUNT_PROP,
        DaemonProperties.Names.BACKGROUND_SANDBOX_BOOT,
        DaemonProperties.Names.WARM_RENDER_ON_BOOT,
        WORKER_PORT_PROP,
        WORKER_SLOT_PROP,
        // A worker never spawns spares of its own, and the marker is per launch.
        DaemonProperties.Names.SANDBOX_WORKER_SPARE,
        DaemonProperties.Names.SANDBOX_WORKER_SPARES,
      )

    private fun forwardedSysprops(): LinkedHashMap<String, String> {
      val forwarded = linkedMapOf<String, String>()
      for ((rawKey, rawValue) in System.getProperties()) {
        val key = rawKey as? String ?: continue
        val value = rawValue as? String ?: continue
        if (FORWARDED_PREFIXES.any { key.startsWith(it) }) forwarded[key] = value
      }
      forwarded.keys.removeAll(WORKER_OVERRIDDEN_PROPS)
      return forwarded
    }

    /**
     * Test seam: the argv a **spare** worker gets when launched from this JVM — the same inherited
     * flags, forwarded properties and classpath a pooled worker gets, in spare mode. Production
     * spares are launched by `daemon-client`'s `SandboxSparePool` from a launch descriptor; this
     * lets the adoption test stand one up against the test JVM's own classpath.
     */
    internal fun spareWorkerCommandForTest(): List<String> = buildList {
      add(File(File(System.getProperty("java.home"), "bin"), "java").absolutePath)
      addAll(
        ManagementFactory.getRuntimeMXBean().inputArguments.filter { arg ->
          !arg.startsWith("-agentlib:") &&
            !arg.startsWith("-agentpath:") &&
            !arg.startsWith("-javaagent:") &&
            !arg.startsWith("-Xrunjdwp") &&
            !arg.startsWith("-D")
        }
      )
      val props = forwardedSysprops()
      props[SANDBOX_COUNT_PROP] = "1"
      props[DaemonProperties.Names.SANDBOX_WORKER_SPARE] = "true"
      props.forEach { (k, v) -> add("-D$k=$v") }
      add("-cp")
      add(System.getProperty("java.class.path") ?: "")
      add(SandboxWorkerMain::class.java.name)
    }

    /** A listening spare answers a loopback connect at once; anything longer is a dead spare. */
    private const val SPARE_CONNECT_TIMEOUT_MS = 5_000

    /**
     * The configure reply waits on a classloader rebuild in the spare, never on a sandbox boot —
     * generous all the same, so a spare mid-warm-render is not written off for a slow frame.
     */
    private const val SPARE_CONFIGURE_TIMEOUT_MS = 60_000

    private const val SOCKET_READ_MARGIN_MS = 15_000L
    private const val SWAP_TIMEOUT_MS = 30_000L
    private const val SHUTDOWN_TIMEOUT_MS = 30_000L
  }
}
