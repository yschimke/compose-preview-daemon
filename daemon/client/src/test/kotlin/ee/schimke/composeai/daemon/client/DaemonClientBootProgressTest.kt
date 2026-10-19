package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.BootProgressListener
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import ee.schimke.composeai.daemon.protocol.BootPhase
import ee.schimke.composeai.daemon.protocol.BootProgressParams
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * [DaemonClient.initializeWithBootProgress] against a real [JsonRpcServer] whose host boots slowly
 * (compose-ai-tools#6114): progress arrives while it boots, and the call returns once it can
 * render.
 */
class DaemonClientBootProgressTest {

  @get:Rule val timeout: Timeout = Timeout.seconds(60)

  @Test
  fun `reports the boot and returns once the host is up`() {
    val gate = CountDownLatch(1)
    val host = GatedHost(gate)
    withDaemon(host) { client ->
      val seen = CopyOnWriteArrayList<BootProgressParams>()
      // Release the host once the client has been told it is booting.
      val result =
        client.initializeWithBootProgress(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = 30.seconds,
        ) {
          seen += it
          if (it.phase == BootPhase.SANDBOX_BOOTING) gate.countDown()
        }
      assertThat(result.booting).isTrue()
      assertThat(host.started).isTrue()
      assertThat(seen.map { it.phase })
        .containsAtLeast(BootPhase.STARTING, BootPhase.SANDBOX_BOOTING, BootPhase.READY)
        .inOrder()
      assertThat(seen.last().phase).isEqualTo(BootPhase.READY)
    }
  }

  @Test
  fun `a failed boot throws DaemonBootFailedException`() {
    val gate = CountDownLatch(1)
    withDaemon(GatedHost(gate, failure = IllegalStateException("sandbox 0 did not boot"))) { client
      ->
      val failure = runCatching {
        client.initializeWithBootProgress(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = 30.seconds,
        ) {
          if (it.phase == BootPhase.STARTING) gate.countDown()
        }
      }
        .exceptionOrNull()
      assertThat(failure).isInstanceOf(DaemonBootFailedException::class.java)
      assertThat(failure).hasMessageThat().contains("sandbox 0 did not boot")
    }
  }

  @Test
  fun `a host that is already up is reported as not booting`() {
    val gate = CountDownLatch(0)
    withDaemon(GatedHost(gate)) { client ->
      Thread.sleep(300)
      val seen = CopyOnWriteArrayList<BootProgressParams>()
      val result =
        client.initializeWithBootProgress(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = 30.seconds,
        ) {
          seen += it
        }
      assertThat(result.booting).isFalse()
      assertThat(seen).isEmpty()
    }
  }

  private fun withDaemon(host: RenderHost, body: (DaemonClient) -> Unit) {
    val toServer = PipedOutputStream()
    val serverIn = PipedInputStream(toServer, 1 shl 16)
    val fromServer = PipedInputStream(1 shl 16)
    val serverOut = PipedOutputStream(fromServer)
    thread(isDaemon = true, name = "boot-progress-test-server") {
      runCatching {
        JsonRpcServer(
            input = serverIn,
            output = serverOut,
            host = host,
            daemonVersion = "test",
            onExit = {},
          )
          .run()
      }
    }
    DaemonClient(fromServer, toServer, { _, _ -> }).use(body)
  }

  private class GatedHost(
    private val gate: CountDownLatch,
    private val failure: Throwable? = null,
  ) : RenderHost {
    @Volatile var started = false
    @Volatile private var listener: BootProgressListener? = null

    override fun setBootProgressListener(listener: BootProgressListener?) {
      this.listener = listener
    }

    override fun start() {
      listener?.onBootProgress(BootPhase.SANDBOX_BOOTING, 0, 0, 1)
      gate.await()
      failure?.let { throw it }
      started = true
      listener?.onBootProgress(BootPhase.SANDBOX_READY, 0, 1, 1)
    }

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      throw UnsupportedOperationException("no renders in this test")

    override fun shutdown(timeoutMs: Long) {}
  }
}
