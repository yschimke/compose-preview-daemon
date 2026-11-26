package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.SystemPropertyArgs
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.io.classpathArgFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * Production [DaemonClientFactory]: forks a JVM per [DaemonLaunchDescriptor] and pipes its stdio
 * into a [DaemonClient]. Mirrors `RealDesktopHarnessLauncher` from `:daemon:harness`.
 */
public class SubprocessDaemonClientFactory(
  /**
   * Pre-booted sandbox workers to hand each Android daemon (SANDBOX-POOL.md § "Spare workers").
   * `null` — the default, and every launch outside a long-lived server — spawns daemons exactly as
   * before. With a pool, an Android launch whose sandbox pool has more than one slot takes the warm
   * spares of its overlay signature via `composeai.daemon.sandboxWorker.spares`, and the pool is
   * asked to top that signature back up for the next launch.
   */
  private val sparePool: SandboxSparePool? = null,
) : DaemonClientFactory {
  /** See the secondary constructor. A property rather than a primary parameter to keep the ABI. */
  private var environment: ChildEnvironment = ChildEnvironment.Default

  /**
   * @param environment the environment each daemon JVM starts with. [ChildEnvironment.Default] —
   *   the allowlist — unless a caller needs something else; a jail command on the descriptor runs
   *   under the same environment.
   */
  public constructor(
    sparePool: SandboxSparePool? = null,
    environment: ChildEnvironment,
  ) : this(sparePool) {
    this.environment = environment
  }

  override fun spawn(workspaceId: WorkspaceId, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    require(descriptor.enabled) {
      "daemon disabled for ${descriptor.modulePath} — set composePreview { daemon { enabled = true } }"
    }
    val launch = withReservedSpares(descriptor)
    return spawnProcess(workspaceId, launch.descriptor).also {
      // Demand-driven: the signature this launch used is the one worth keeping warm.
      sparePool?.takeIf { launch.spareEligible }?.ensure(descriptor)
    }
  }

  /** The descriptor as launched, plus whether it was the kind of launch spares apply to. */
  internal class Launch(val descriptor: DaemonLaunchDescriptor, val spareEligible: Boolean)

  /**
   * Reserve spares for an Android launch with a multi-slot sandbox pool and put their ports on the
   * descriptor. Pure apart from the reservation; the test reads the result.
   */
  internal fun withReservedSpares(descriptor: DaemonLaunchDescriptor): Launch {
    val pool = sparePool ?: return Launch(descriptor, spareEligible = false)
    if (descriptor.variant != ANDROID_VARIANT) return Launch(descriptor, spareEligible = false)
    val workers = sandboxCountOf(descriptor) - 1
    if (workers <= 0) return Launch(descriptor, spareEligible = false)
    val ports = pool.reserve(descriptor, workers)
    if (ports.isEmpty()) return Launch(descriptor, spareEligible = true)
    return Launch(
      descriptor
        .newBuilder()
        .also {
          it.systemProperties =
            descriptor.systemProperties +
              (DaemonProperties.Names.SANDBOX_WORKER_SPARES to ports.joinToString(","))
        }
        .build(),
      spareEligible = true,
    )
  }

  /**
   * The daemon's sandbox pool size: the descriptor's own property, else this JVM's (a server sets
   * it once in `JAVA_TOOL_OPTIONS`, which every daemon JVM inherits too), else `DaemonMain`'s
   * warm-spare default of five.
   */
  private fun sandboxCountOf(descriptor: DaemonLaunchDescriptor): Int =
    (descriptor.systemProperties[DaemonProperties.Names.SANDBOX_COUNT]
        ?: System.getProperty(DaemonProperties.Names.SANDBOX_COUNT))
      ?.toIntOrNull()
      ?.coerceAtLeast(1) ?: DEFAULT_ANDROID_SANDBOX_COUNT

  private fun spawnProcess(
    workspaceId: WorkspaceId,
    descriptor: DaemonLaunchDescriptor,
  ): DaemonSpawn {
    val launch = buildLaunchCommand(descriptor)
    val process =
      try {
        ProcessBuilder(launch.argv)
          .directory(File(descriptor.workingDirectory))
          .redirectErrorStream(false)
          .redirectInput(ProcessBuilder.Redirect.PIPE)
          .redirectOutput(ProcessBuilder.Redirect.PIPE)
          .redirectError(ProcessBuilder.Redirect.PIPE)
          .applyEnvironment(environment)
          .start()
      } catch (t: Throwable) {
        launch.cleanup()
        throw t
      }
    forwardStderr(process, "$workspaceId/${descriptor.modulePath}")
    descriptor.hardTtlSeconds?.let { ttl ->
      armHardTtl(process, ttl, "$workspaceId/${descriptor.modulePath}")
    }
    return SubprocessDaemonSpawn(process, onProcessReadyOrExit = launch.cleanup)
  }

  internal companion object {
    const val ANDROID_VARIANT = "android"

    const val MAX_INLINE_PROPERTY = SystemPropertyArgs.MAX_INLINE_PROPERTY
    const val MAX_INLINE_PROPERTIES = SystemPropertyArgs.MAX_INLINE_PROPERTIES
    const val MAX_WINDOWS_INLINE_PROPERTIES = SystemPropertyArgs.MAX_WINDOWS_INLINE_PROPERTIES

    /** The `-D` arguments for [properties], spilled to an `@argfile` past the OS limits. */
    fun systemPropertyArgs(properties: Map<String, String>, directory: File? = null): List<String> =
      SystemPropertyArgs.of(properties, directory)

    fun quoteForArgFile(token: String): String = SystemPropertyArgs.quote(token)

    /** `DaemonMain`'s pool size when `composeai.daemon.warmSpare` (default on) is unset-else. */
    const val DEFAULT_ANDROID_SANDBOX_COUNT = 5
  }

  /**
   * The hard wall-clock TTL: a daemon thread that force-kills the JVM at the deadline regardless of
   * what it is doing. Cooperative shutdown is not enough for a sandboxed playground session — a
   * snippet can spin a tight loop that never services a JSON-RPC `shutdown` — so the parent shoots
   * it. A process that exits on its own first makes this a no-op.
   */
  private fun armHardTtl(process: Process, ttlSeconds: Long, tag: String) {
    Thread(
        {
          if (!process.waitFor(ttlSeconds, TimeUnit.SECONDS)) {
            System.err.println("[daemon $tag] hard TTL of ${ttlSeconds}s reached — killing sandbox")
            process.destroyForcibly()
          }
        },
        "daemon-hard-ttl-$tag",
      )
      .apply { isDaemon = true }
      .start()
  }

  private fun forwardStderr(process: Process, tag: String) {
    Thread(
        {
          process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { System.err.println("[daemon $tag] $it") }
          }
        },
        "daemon-stderr-$tag",
      )
      .apply { isDaemon = true }
      .start()
  }
}

internal class SubprocessDaemonSpawn(
  private val process: Process,
  private val onProcessReadyOrExit: (() -> Unit)? = null,
) : DaemonSpawn {
  private lateinit var _client: DaemonClient
  private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)

  init {
    if (onProcessReadyOrExit != null) {
      process.onExit().whenComplete { _, _ -> runCleanupOnce() }
    }
  }

  private fun runCleanupOnce() {
    val cleanup = onProcessReadyOrExit ?: return
    if (cleanedUp.compareAndSet(false, true)) {
      runCatching { cleanup() }
    }
  }

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    val input =
      if (onProcessReadyOrExit != null) {
        FirstReadTriggerInputStream(process.inputStream, ::runCleanupOnce)
      } else {
        process.inputStream
      }
    _client =
      DaemonClient(
        input = input,
        output = process.outputStream,
        onNotification = onNotification,
        onClose = onClose,
      )
    return _client
  }

  override fun shutdown() {
    runCatching { _client.shutdownAndExit() }
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
    runCatching { _client.close() }
    runCleanupOnce()
  }

  override fun shutdown(timeout: Duration) {
    require(!timeout.isNegative()) { "shutdown timeout must not be negative" }
    val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds

    runCatching { _client.shutdownAndExit(timeout) }
    waitForUntil(deadlineNanos)

    if (process.isAlive) {
      process.destroy()
      waitForUntil(deadlineNanos)
      if (process.isAlive) process.destroyForcibly()
    }
    runCatching { _client.close() }
    runCleanupOnce()
  }

  private fun waitForUntil(deadlineNanos: Long) {
    if (!process.isAlive) return
    val remainingMillis = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L
    if (remainingMillis > 0L) process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)
  }
}

/**
 * Triggers [onFirstRead] once the wrapped child stdout yields its first byte — proof that the child
 * `java` launcher has finished parsing its `@argfile`s and entered `main`.
 */
private class FirstReadTriggerInputStream(
  private val delegate: java.io.InputStream,
  private val onFirstRead: () -> Unit,
) : java.io.InputStream() {
  private var triggered = false

  override fun read(): Int {
    val b = delegate.read()
    if (!triggered && b >= 0) {
      triggered = true
      onFirstRead()
    }
    return b
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    val n = delegate.read(b, off, len)
    if (!triggered && n > 0) {
      triggered = true
      onFirstRead()
    }
    return n
  }

  override fun available(): Int = delegate.available()

  override fun close() = delegate.close()
}

/** A prepared JVM argv together with a cleanup action that removes its temporary `@argfile`s. */
internal class LaunchCommand(val argv: List<String>, val cleanup: () -> Unit)

/**
 * The `java` argv that launches [descriptor]'s daemon: the optional jail, the JVM, its arguments,
 * the system properties and classpath (spilled to argfiles past the OS limits), and the main class.
 * Shared by [SubprocessDaemonClientFactory] and [SocketDaemonClientFactory].
 */
internal fun launchCommand(descriptor: DaemonLaunchDescriptor): List<String> =
  buildLaunchCommand(descriptor).argv

internal fun buildLaunchCommand(descriptor: DaemonLaunchDescriptor): LaunchCommand {
  val javaBin =
    descriptor.javaLauncher ?: File(System.getProperty("java.home"), "bin/java").absolutePath
  // Inside a jail the parent's temp dir may not exist (bwrap mounts its own /tmp), so the argfiles
  // go in the one directory both sides can see: the daemon's working directory. For ordinary
  // launches, stage both argfiles inside a PID-tagged per-launch temp directory so they can be
  // deleted together once the child boots (or swept on the next startup after a SIGKILL).
  val jailed = descriptor.jailCommand.isNotEmpty()
  val launchDir = if (jailed) null else SystemPropertyArgs.newLaunchArgDir()
  val argFileDirectory = launchDir ?: File(descriptor.workingDirectory)
  if (jailed) SystemPropertyArgs.sweepStaleArgFilesOnce(argFileDirectory)
  val argv =
    buildList<String> {
      // The optional OS jail (playground per-session sandbox). Empty for every ordinary daemon,
      // so the launched argv is byte-identical to the pre-sandbox one.
      addAll(descriptor.jailCommand)
      add(javaBin)
      addAll(descriptor.jvmArgs)
      addAll(SystemPropertyArgs.of(descriptor.systemProperties, argFileDirectory))
      add(classpathArgFile(descriptor.classpath, argFileDirectory))
      add(descriptor.mainClass)
    }
  val argFiles = SystemPropertyArgs.extractArgFiles(argv)
  val cleanup: () -> Unit =
    if (launchDir != null) {
      { runCatching { launchDir.deleteRecursively() } }
    } else {
      { SystemPropertyArgs.deleteArgFiles(argFiles) }
    }
  return LaunchCommand(argv = argv, cleanup = cleanup)
}
