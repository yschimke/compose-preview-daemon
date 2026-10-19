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
