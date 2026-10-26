package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertThrows
import org.junit.Test

class DaemonClientPreviewRowsTest {

  @Test
  fun `a daemon that predates preview rows answers null`() {
    val requests =
      withPeer(
        answer = { id ->
          """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found: preview/rows"}}"""
        }
      ) { client ->
        assertThat(client.previewRows("com.example.CardKt.Card", timeout = 5.seconds)).isNull()
      }
    val request = requests.single()
    assertThat(request["method"]!!.jsonPrimitive.content).isEqualTo("preview/rows")
    assertThat(request["params"]!!.jsonObject["previewId"]!!.jsonPrimitive.content)
      .isEqualTo("com.example.CardKt.Card")
  }

  @Test
  fun `preview rows decode`() {
    withPeer(
      answer = { id ->
        """{"jsonrpc":"2.0","id":$id,"result":{"previewId":"a.CardKt.Card","rows":[{"index":0,"label":"Dark","id":"a.CardKt.Card_Dark"},{"index":1,"label":"PARAM_1","id":"a.CardKt.Card_PARAM_1"}]}}"""
      }
    ) { client ->
      val result = client.previewRows("a.CardKt.Card", timeout = 5.seconds)!!
      assertThat(result.previewId).isEqualTo("a.CardKt.Card")
      assertThat(result.rows.map { it.id })
        .containsExactly("a.CardKt.Card_Dark", "a.CardKt.Card_PARAM_1")
        .inOrder()
      assertThat(result.rows[1].index).isEqualTo(1)
      assertThat(result.rows[0].label).isEqualTo("Dark")
    }
  }

  @Test
  fun `an unknown preview id throws rather than reading as unsupported`() {
    withPeer(
      answer = { id ->
        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"invalid preview/rows params: unknown previewId"}}"""
      }
    ) { client ->
      val failure =
        assertThrows(IllegalStateException::class.java) {
          client.previewRows("nope", timeout = 5.seconds)
        }
      assertThat(failure.message).contains("-32602")
    }
  }

  /** Runs [block] against a client whose daemon answers every request with [answer]. */
  private fun withPeer(answer: (Long) -> String, block: (DaemonClient) -> Unit): List<JsonObject> {
    val toClient = PipedInputStream()
    val daemonOut = PipedOutputStream(toClient)
    val fromClient = PipedInputStream()
    val clientOut = PipedOutputStream(fromClient)
    val requests = mutableListOf<JsonObject>()
    val peer = Thread {
      runCatching {
        while (true) {
          val request = Json.parseToJsonElement(readFrame(fromClient) ?: break).jsonObject
          requests += request
          writeFrame(daemonOut, answer(request["id"]!!.jsonPrimitive.long))
        }
      }
    }
      .apply {
        isDaemon = true
        start()
      }
    try {
      DaemonClient(toClient, clientOut, { _, _ -> }).use(block)
    } finally {
      daemonOut.close()
      peer.join(2_000)
    }
    return requests
  }

  private fun readFrame(input: InputStream): String? {
    val header = StringBuilder()
    while (!header.endsWith("\r\n\r\n")) {
      val b = input.read()
      if (b < 0) return null
      header.append(b.toChar())
    }
    val length = header.lines().first { it.startsWith("Content-Length:") }.substringAfter(':')
    return String(input.readNBytes(length.trim().toInt()), Charsets.UTF_8)
  }

  private fun writeFrame(output: OutputStream, text: String) {
    val body = text.toByteArray(Charsets.UTF_8)
    output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    output.write(body)
    output.flush()
  }
}
