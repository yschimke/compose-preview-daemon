package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.DaemonSocketFrontEnd
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Test

/**
 * [SocketDaemonClientFactory] against a real daemon JVM: the first session starts it, the next one
 * reuses it, and a stop ends it (compose-ai-tools#6104).
 */
class SocketDaemonClientFactoryTest {

  private val dir: File = Files.createTempDirectory("cpd").toFile()
  private val socket = dir.resolve("d.sock").toPath()

  @After
  fun cleanUp() {
    SocketDaemonClientFactory.stop(socket)
    dir.deleteRecursively()
  }

  @Test
  fun `the first session starts a daemon and the next reuses it`() {
    val factory = SocketDaemonClientFactory(startTimeout = 60.seconds)
    val descriptor = descriptor()
    val workspace = WorkspaceId.derive("ws", dir)

    fun sessionPid(): Long {
      val spawn = factory.spawn(workspace, descriptor)
      val client = spawn.client(onNotification = { _, _ -> }, onClose = {})
      try {
        return client
          .initialize(
            workspaceRoot = dir.path,
            moduleId = ":stub",
            moduleProjectDir = dir.path,
            timeout = 30.seconds,
          )
          .pid
      } finally {
        spawn.shutdown()
      }
    }

    val first = sessionPid()
    val second = sessionPid()
    assertThat(second).isEqualTo(first)
    assertThat(ProcessHandle.of(first).map { it.isAlive }.orElse(false)).isTrue()

    assertThat(SocketDaemonClientFactory.stop(socket)).isTrue()
    val exited =
      ProcessHandle.of(first).map { it.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS) }
    assertThat(exited.isPresent || ProcessHandle.of(first).isEmpty).isTrue()
    assertThat(SocketDaemonClientFactory.connect(socket)).isNull()
  }

  @Test
  fun `nothing listening means no connection and nothing to stop`() {
    assertThat(SocketDaemonClientFactory.connect(socket)).isNull()
    assertThat(SocketDaemonClientFactory.stop(socket)).isFalse()
  }

  private fun descriptor(): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor.Builder(
        schemaVersion = 2,
        modulePath = ":stub",
        variant = "desktop",
        enabled = true,
        mainClass = StubSocketDaemonMain::class.java.name,
        classpath = System.getProperty("java.class.path").split(File.pathSeparator),
        jvmArgs = emptyList(),
        systemProperties =
          mapOf(
            DaemonProperties.Names.SOCKET_PATH to socket.toString(),
            DaemonProperties.Names.KEEP_ALIVE_IDLE_MS to "60000",
          ),
        workingDirectory = dir.absolutePath,
        manifestPath = "",
      )
      .build()
}

/** A socket-mode daemon whose host renders nothing: enough to exercise the transport. */
object StubSocketDaemonMain {
  @JvmStatic
  fun main(args: Array<String>) {
    val frontEnd = checkNotNull(DaemonSocketFrontEnd.fromSystemProperties())
    frontEnd.start()
    JsonRpcServer(
        input = frontEnd.serverInput,
        output = frontEnd.serverOutput,
        host = StubHost,
        daemonVersion = "test",
        onExit = JsonRpcServer.EXIT_PROCESS,
      )
      .run()
  }

  private object StubHost : RenderHost {
    override fun start() = Unit

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      RenderResult(
        id = (request as RenderRequest.Render).id,
        classLoaderHashCode = 0,
        classLoaderName = "stub",
      )

    override fun shutdown(timeoutMs: Long) = Unit
  }
}
