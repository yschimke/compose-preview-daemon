package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class DaemonClientCompileWarmUpTest {

  @Test
  fun `a daemon that predates compileWarmUp answers null`() {
    val requests =
      withPeer(
        answer = { id ->
          """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found: compileWarmUp"}}"""
        }
      ) { client ->
        assertThat(client.compileWarmUp(timeout = 5.seconds)).isNull()
      }
    assertThat(requests.single()["method"]!!.jsonPrimitive.content).isEqualTo("compileWarmUp")
  }

  @Test
  fun `the warm-up sends the sources it is given`() {
    val requests =
      withPeer(answer = { id -> """{"jsonrpc":"2.0","id":$id,"result":{"ready":true}}""" }) { client
        ->
        client.compileWarmUp(listOf("/m/src/Home.kt"), timeout = 5.seconds)
      }
    val sources = requests.single()["params"]!!.jsonObject["sources"]!!.jsonArray
    assertThat(sources.map { it.jsonPrimitive.content }).containsExactly("/m/src/Home.kt")
  }

  @Test
  fun `a warm-up answer decodes`() {
    withPeer(
      answer = { id ->
        """{"jsonrpc":"2.0","id":$id,"result":{"ready":false,"durationMs":3,"message":"no warm compiler is bundled for Kotlin 2.4.20"}}"""
      }
    ) { client ->
      val result = client.compileWarmUp(timeout = 5.seconds)!!
      assertThat(result.ready).isFalse()
      assertThat(result.durationMs).isEqualTo(3)
      assertThat(result.message).contains("2.4.20")
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
