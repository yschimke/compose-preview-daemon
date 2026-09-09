package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.config.DaemonProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adoption messages round-trip through the worker codec, and the handshake prefix a spare
 * prints is the one `daemon-client`'s `SandboxSparePool` scans for — the two live in different
 * modules and must agree as bytes.
 */
class SandboxWorkerProtocolConfigureTest {

  @Test
  fun `configure carries the properties and the adopting pid`() {
    val request: WorkerRequest =
      WorkerRequest.Configure(
        systemProperties =
          mapOf(
            DaemonProperties.Names.USER_CLASS_DIRS to "/catalog/classes",
            "composeai.render.outputDir" to "/catalog/renders",
          ),
        parentPid = 4242,
      )
    val line = workerJson.encodeToString(WorkerRequest.serializer(), request)
    assertTrue(line, line.contains("\"type\":\"configure\""))
    assertEquals(request, workerJson.decodeFromString(WorkerRequest.serializer(), line))
  }

  @Test
  fun `configured carries the worker pid`() {
    val response: WorkerResponse = WorkerResponse.Configured(pid = 99)
    val line = workerJson.encodeToString(WorkerResponse.serializer(), response)
    assertTrue(line, line.contains("\"type\":\"configured\""))
    assertEquals(response, workerJson.decodeFromString(WorkerResponse.serializer(), line))
  }

  @Test
  fun `the spare handshake prefix is what the spare pool scans for`() {
    assertEquals("composeai-spare-worker: listening", SandboxWorkerMain.SPARE_HANDSHAKE_PREFIX)
  }
}
