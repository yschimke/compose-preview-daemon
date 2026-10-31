package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class DaemonClientPendingRequestsTest {
  @Test
  fun `a failed write releases its response slot`() {
    val output =
      object : OutputStream() {
        override fun write(value: Int) {
          throw IOException("broken pipe")
        }
      }
    DaemonClient(ByteArrayInputStream(byteArrayOf()), output, { _, _ -> }).use { client ->
      val failure = runCatching {
        client.initialize(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = 10.milliseconds,
        )
      }
        .exceptionOrNull()
      assertThat(failure).isInstanceOf(IOException::class.java)
      assertThat(pendingCount(client)).isEqualTo(0)
    }
  }

  @Test
  fun `a late reply cannot recreate a timed out response slot`() {
    val input = PipedInputStream()
    val peer = PipedOutputStream(input)
    val barrier = CountDownLatch(1)
    DaemonClient(
        input,
        ByteArrayOutputStream(),
        { method, _ -> if (method == "barrier") barrier.countDown() },
      )
      .use { client ->
        try {
          val failure = runCatching {
            client.initialize(
              workspaceRoot = "/ws",
              moduleId = ":app",
              moduleProjectDir = "/ws/app",
              timeout = 10.milliseconds,
            )
          }
            .exceptionOrNull()
          assertThat(failure).hasMessageThat().contains("timed out")
          assertThat(pendingCount(client)).isEqualTo(0)
          writeFrame(peer, """{"jsonrpc":"2.0","id":1,"result":{}}""")
          writeFrame(peer, """{"jsonrpc":"2.0","method":"barrier","params":{}}""")
          assertThat(barrier.await(5, TimeUnit.SECONDS)).isTrue()
          assertThat(pendingCount(client)).isEqualTo(0)
        } finally {
          peer.close()
        }
      }
  }

  @Test
  fun `a daemon that exits fails the waiting request at once, not at its timeout`() {
    // A daemon started on a stale classpath died with ClassNotFoundException DaemonMain, and the
    // MCP server's initialize waited out its two-minute timeout before reporting anything.
    val input = PipedInputStream()
    val peer = PipedOutputStream(input)
    val written = CountDownLatch(1)
    val output =
      object : OutputStream() {
        override fun write(value: Int) {
          written.countDown()
        }
      }
    DaemonClient(input, output, { _, _ -> }).use { client ->
      val exit = Thread {
        written.await(5, TimeUnit.SECONDS)
        peer.close() // The process exited: its stdout reaches EOF.
      }
      exit.start()
      val started = System.nanoTime()
      val failure = runCatching {
        client.initialize(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = kotlin.time.Duration.parse("2m"),
        )
      }
        .exceptionOrNull()
      val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
      exit.join()
      assertThat(failure).isInstanceOf(IOException::class.java)
      assertThat(failure).hasMessageThat().contains("closed its connection before answering")
      assertThat(waitedMs).isLessThan(10_000)
      assertThat(pendingCount(client)).isEqualTo(0)

      // And a request after that fails at once too.
      val later = runCatching {
        client.initialize(
          workspaceRoot = "/ws",
          moduleId = ":app",
          moduleProjectDir = "/ws/app",
          timeout = kotlin.time.Duration.parse("2m"),
        )
      }
        .exceptionOrNull()
      assertThat(later).isInstanceOf(IOException::class.java)
    }
  }

  // Inspect retention without adding a production API solely to expose this implementation detail.
  private fun pendingCount(client: DaemonClient): Int {
    val field = DaemonClient::class.java.getDeclaredField("responseSlots")
    field.isAccessible = true
    return (field.get(client) as Map<*, *>).size
  }

  private fun writeFrame(output: OutputStream, text: String) {
    val body = text.toByteArray(Charsets.UTF_8)
    output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    output.write(body)
    output.flush()
  }
}
