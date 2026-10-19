package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

/**
 * Connects to a daemon that serves clients over a Unix domain socket, starting one first if none is
 * listening (compose-ai-tools#6104).
 *
 * The descriptor names the socket in [DaemonProperties.Names.SOCKET_PATH]; the daemon's
 * `DaemonSocketFrontEnd` keeps its host and warm sandboxes between clients and exits after
 * [DaemonProperties.Names.KEEP_ALIVE_IDLE_MS] with none connected. A daemon this factory starts
 * outlives the caller: its output goes to `daemon.log` beside the socket, and closing a session
 * ([DaemonSpawn.shutdown]) only disconnects. Two callers racing to start one serialise on a lock
 * file beside the socket, so exactly one process is launched.
 */
public class SocketDaemonClientFactory(
  private val startTimeout: Duration = 120.seconds,
  private val environment: ChildEnvironment = ChildEnvironment.Default,
) : DaemonClientFactory {

  override fun spawn(workspaceId: WorkspaceId, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    require(descriptor.enabled) { "daemon disabled for ${descriptor.modulePath}" }
    // A socket daemon outlives its caller, so nothing would be left to enforce a hard TTL or own
    // a jail: those launches stay with [SubprocessDaemonClientFactory].
    require(descriptor.hardTtlSeconds == null && descriptor.jailCommand.isEmpty()) {
      "${descriptor.modulePath}: a jailed or hard-TTL daemon cannot be shared over a socket"
    }
    val socket = socketPathOf(descriptor)
    connect(socket)?.let {
      return SocketDaemonSpawn(it)
    }
    val dir = socket.toAbsolutePath().parent.toFile().apply { mkdirs() }
    RandomAccessFile(File(dir, LOCK_FILE), "rw").use { lockFile ->
      lockFile.channel.lock().use {
        // Another caller may have started it while this one waited for the lock.
        connect(socket)?.let {
          return SocketDaemonSpawn(it)
        }
        val process = launch(descriptor, dir)
        val deadline = System.nanoTime() + startTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
          connect(socket)?.let {
            return SocketDaemonSpawn(it)
          }
          if (!process.isAlive) {
            error(
              "daemon for ${descriptor.modulePath} exited (${process.exitValue()}) before it " +
                "listened on $socket; see ${File(dir, LOG_FILE)}"
            )
          }
          Thread.sleep(POLL_MS)
        }
        process.destroy()
        error("daemon for ${descriptor.modulePath} did not listen on $socket within $startTimeout")
      }
    }
  }

  private fun launch(descriptor: DaemonLaunchDescriptor, dir: File): Process {
    // `setsid` (Linux) moves the daemon out of the caller's process group, so a Ctrl-C meant for
    // the CLI does not reach it.
    val setsid = listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() }
    val log = File(dir, LOG_FILE)
    return ProcessBuilder(listOfNotNull(setsid) + launchCommand(descriptor))
      .directory(File(descriptor.workingDirectory))
      .redirectInput(ProcessBuilder.Redirect.from(NULL_FILE))
      .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
      .redirectError(ProcessBuilder.Redirect.appendTo(log))
      .applyEnvironment(environment)
      .start()
  }

  public companion object {
    private const val LOCK_FILE = "daemon.lock"
    private const val LOG_FILE = "daemon.log"
    private const val POLL_MS = 100L
    private val NULL_FILE =
      File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")

    /** The socket [descriptor] names; a descriptor without one is a stdio daemon. */
    public fun socketPathOf(descriptor: DaemonLaunchDescriptor): Path =
      Path.of(
        requireNotNull(descriptor.systemProperties[DaemonProperties.Names.SOCKET_PATH]) {
          "${descriptor.modulePath}: no ${DaemonProperties.Names.SOCKET_PATH} in the descriptor"
        }
      )

    /** A connection to the daemon listening on [socket], or `null` when none is. */
    public fun connect(socket: Path): SocketChannel? =
      try {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
          try {
            connect(UnixDomainSocketAddress.of(socket))
          } catch (e: IOException) {
            close()
            throw e
          }
        }
      } catch (_: IOException) {
        null
      } catch (_: UnsupportedOperationException) {
        null
      }

    /**
     * Asks the daemon on [socket] to stop now rather than when idle. Returns whether one was
     * listening.
     */
    public fun stop(socket: Path): Boolean {
      val channel = connect(socket) ?: return false
      channel.use {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"$STOP_METHOD"}""".encodeToByteArray()
        it
          .outputStream()
          .write("Content-Length: ${payload.size}\r\n\r\n".encodeToByteArray() + payload)
        // Wait for the reply, or for the daemon to hang up, before returning.
        runCatching { it.inputStream().read(ByteArray(256)) }
      }
      return true
    }

    /** The daemon socket front end's stop request (`DaemonSocketFrontEnd.STOP_METHOD`). */
    private const val STOP_METHOD = "daemon/stop"
  }
}

/** A session over one socket connection; [shutdown] disconnects and leaves the daemon running. */
internal class SocketDaemonSpawn(private val channel: SocketChannel) : DaemonSpawn {
  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = channel.inputStream(),
        output = channel.outputStream(),
        onNotification = onNotification,
        onClose = onClose,
      )
    return _client
  }

  override fun shutdown() {
    // The daemon answers `shutdown` and `exit` for this connection only.
    runCatching { _client.shutdownAndExit() }
    runCatching { _client.close() }
    runCatching { channel.close() }
  }
}

/**
 * Stream views that read and write the channel directly. `Channels.newInputStream` and
 * `newOutputStream` share the channel's blocking lock, so a read waiting on the daemon would block
 * every request to it.
 */
internal fun SocketChannel.inputStream(): InputStream =
  object : InputStream() {
    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      if (len == 0) 0 else this@inputStream.read(ByteBuffer.wrap(b, off, len))

    override fun close() = this@inputStream.close()
  }

internal fun SocketChannel.outputStream(): OutputStream =
  object : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
      val buffer = ByteBuffer.wrap(b, off, len)
      while (buffer.hasRemaining()) this@outputStream.write(buffer)
    }

    override fun close() = this@outputStream.close()
  }
