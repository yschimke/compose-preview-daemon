package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.pool.SandboxProcessPool
import ee.schimke.composeai.daemon.pool.SandboxWorkerMain
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md § "Spare workers" — a pre-booted spare worker is adopted by a starting host
 * before its own in-process sandbox boots, and serves renders in the meantime.
 *
 * The spare is launched the way `SandboxSparePool` launches one (spare mode, this JVM's classpath
 * and flags, no catalog), and its stdout handshake gives the port the host is handed through
 * `composeai.daemon.sandboxWorker.spares`. The host under test has `sandboxCount = 2`, so the one
 * spare fills its only worker slot; slot 0 boots behind it on the background thread.
 */
class RobolectricHostSpareAdoptionTest {

  @Test
  fun adoptedSpareServesRendersWhileTheInProcessSandboxBoots() {
    val outputDir = Files.createTempDirectory("spare-adoption").toFile()
    val spare =
      ProcessBuilder(SandboxProcessPool.spareWorkerCommandForTest())
        .redirectErrorStream(false)
        .start()
    pump(spare.errorStream)
    val handshake =
      spare.inputStream.bufferedReader().let { reader ->
        var line: String?
        var found: String? = null
        val deadline = System.currentTimeMillis() + 600_000
        while (
          reader.readLine().also { line = it } != null && System.currentTimeMillis() < deadline
        ) {
          if (line!!.startsWith(SandboxWorkerMain.SPARE_HANDSHAKE_PREFIX)) {
            found = line
            break
          }
          System.err.println("[spare] $line")
        }
        // Keep draining so the spare never blocks on a full pipe.
        Thread(
            { runCatching { reader.forEachLine { System.err.println("[spare] $it") } } },
            "spare-stdout",
          )
          .apply { isDaemon = true }
          .start()
        found
      }
    assertNotNull("spare worker never announced itself; see [spare] stderr above", handshake)
    val port = Regex("port=(\\d+)").find(handshake!!)!!.groupValues[1].toInt()
    val sparePid = Regex("pid=(\\d+)").find(handshake)!!.groupValues[1].toLong()

    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(RobolectricHost.BACKGROUND_BOOT_PROP, "true")
    System.setProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES, port.toString())
    val host = RobolectricHost(sandboxCount = 2)
    try {
      val startedAt = System.nanoTime()
      host.start()
      val startMs = (System.nanoTime() - startedAt) / 1_000_000
      // The load-bearing assertions: start() returned on the adopted worker alone, long before a
      // sandbox could have booted in this JVM, and the worker is the spare we launched.
      assertEquals(
        "exactly the adopted slot should be ready right after start()",
        1,
        host.readySlotCountForTest(),
      )
      assertTrue("start() should return in adoption time, took ${startMs}ms", startMs < 30_000)
      assertEquals(listOf(sparePid), host.workerPidsForTest())

      // A render succeeds now, on the worker — slot 0 is still booting.
      val early =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-1")),
          timeoutMs = 120_000,
        )
      assertNotNull(early)

      // The in-process sandbox comes up behind it and the pool completes.
      val deadline = System.currentTimeMillis() + 600_000
      while (host.readySlotCountForTest() < 2 && System.currentTimeMillis() < deadline) Thread
        .sleep(200)
      assertEquals("slot 0 should boot in the background", 2, host.readySlotCountForTest())
      val later =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-2")),
          timeoutMs = 120_000,
        )
      assertNotNull(later)
    } finally {
      System.clearProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES)
      System.clearProperty(RobolectricHost.BACKGROUND_BOOT_PROP)
      host.shutdown()
      // The adopted spare belongs to the host now: shutdown ends it.
      assertTrue("adopted spare should exit with the host", spare.waitFor(30, TimeUnit.SECONDS))
      outputDir.deleteRecursively()
    }
  }

  private fun pump(stream: java.io.InputStream) {
    Thread(
        { stream.bufferedReader().forEachLine { System.err.println("[spare] $it") } },
        "spare-stderr",
      )
      .apply { isDaemon = true }
      .start()
  }
}
