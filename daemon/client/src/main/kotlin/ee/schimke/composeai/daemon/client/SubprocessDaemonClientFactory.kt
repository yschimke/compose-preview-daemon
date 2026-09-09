package ee.schimke.composeai.daemon.client

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
  private val sparePool: SandboxSparePool? = null
) : DaemonClientFactory {
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
      descriptor.copy(
        systemProperties =
          descriptor.systemProperties +
            (DaemonProperties.Names.SANDBOX_WORKER_SPARES to ports.joinToString(","))
      ),
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
    val javaBin =
      descriptor.javaLauncher ?: File(System.getProperty("java.home"), "bin/java").absolutePath
    val command =
      buildList<String> {
        // The optional OS jail (playground per-session sandbox). Empty for every ordinary daemon,
        // so the launched argv is byte-identical to the pre-sandbox one.
        addAll(descriptor.jailCommand)
        add(javaBin)
        addAll(descriptor.jvmArgs)
        descriptor.systemProperties.forEach { (k, v) -> add("-D$k=$v") }
        // Inside a jail the parent's temp dir may not exist (bwrap mounts its own /tmp), so the
        // argfile goes in the one directory both sides can see: the daemon's working directory.
        add(
          classpathArgFile(
            descriptor.classpath,
            File(descriptor.workingDirectory).takeIf { descriptor.jailCommand.isNotEmpty() },
          )
        )
        add(descriptor.mainClass)
      }
    val process =
      ProcessBuilder(command)
        .directory(File(descriptor.workingDirectory))
        .redirectErrorStream(false)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    forwardStderr(process, "$workspaceId/${descriptor.modulePath}")
    descriptor.hardTtlSeconds?.let { ttl ->
      armHardTtl(process, ttl, "$workspaceId/${descriptor.modulePath}")
    }
    return SubprocessDaemonSpawn(process)
  }

  private companion object {
    const val ANDROID_VARIANT = "android"

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

internal class SubprocessDaemonSpawn(private val process: Process) : DaemonSpawn {
  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = process.inputStream,
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
  }

  private fun waitForUntil(deadlineNanos: Long) {
    if (!process.isAlive) return
    val remainingMillis = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L
    if (remainingMillis > 0L) process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)
  }
}
