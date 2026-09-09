package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DaemonSpawn
import ee.schimke.composeai.daemon.client.SandboxSparePool
import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * SANDBOX-POOL.md § "Spare workers", end to end through the production client path: real
 * `:daemon:android` `DaemonMain` processes spawned by [SubprocessDaemonClientFactory] with a
 * [SandboxSparePool], driven over JSON-RPC by [DaemonClient].
 *
 * The shape a serve box repeats all day — a catalog daemon opens, renders, is reaped, and the next
 * one opens — compressed into one test:
 *
 * 1. a **cold** daemon (no pool) as the reference: `initialize` waits for a sandbox boot;
 * 2. the pool pre-boots one spare for the launch's signature;
 * 3. a daemon launched through the pool **adopts** it — `initialize` answers without booting, a
 *    render lands on the adopted worker — and its launch tops the pool back up;
 * 4. after it is shut down, the **next** daemon adopts the replenished spare: the pool is reused,
 *    not consumed;
 * 5. closing the pool leaves no spare JVM behind.
 *
 * **Skipped under fake mode and under `-Pharness.target=desktop`.** Heavy: two spare boots and
 * three daemons, several minutes on a loaded box — which is also why it runs where the other real
 * Android scenarios run, not in every CI job.
 */
class SpareAdoptionAndroidRealModeTest {

  @Test
  fun daemonsAdoptSparesAndTheNextDaemonReusesThePool() {
    Assume.assumeTrue(
      "Skipping SpareAdoptionAndroidRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )
    Assume.assumeTrue(
      "Skipping SpareAdoptionAndroidRealModeTest — android variant; set -Pharness.target=android.",
      HarnessTestSupport.harnessTarget() == "android",
    )
    val previewId = "red-square"
    val paths =
      realAndroidModeScenario(
        name = "spare-adoption",
        previews =
          listOf(
            RealModePreview(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "RedSquare",
            )
          ),
      )
    val descriptor = descriptor(paths)

    // 1. The cold reference: what every catalog open costs today.
    val cold = SubprocessDaemonClientFactory().openAndRender(descriptor, "cold", previewId)
    System.err.println(
      "[measure] cold daemon: initialize ${cold.initializeMs}ms, render ${cold.renderMs}ms"
    )

    val pool =
      SandboxSparePool(
        SandboxSparePool.Config(
          maxSpares = 2,
          perSignature = 1,
          workingDirectory = paths.rendersDir.parentFile,
        )
      )
    try {
      // 2. One spare, booted and warm ahead of demand.
      pool.ensure(descriptor)
      awaitWarm(pool, 1)
      val factory = SubprocessDaemonClientFactory(pool)

      // 3. Adopt it.
      val adopted = factory.openAndRender(descriptor, "adopted", previewId)
      System.err.println(
        "[measure] adopting daemon: initialize ${adopted.initializeMs}ms, render ${adopted.renderMs}ms"
      )
      assertEquals("the launch should have taken the warm spare", 1, pool.snapshot().adopted)
      assertTrue(
        "initialize on an adopted spare (${adopted.initializeMs}ms) should beat a cold boot " +
          "(${cold.initializeMs}ms)",
        adopted.initializeMs < cold.initializeMs,
      )

      // 4. The launch topped the signature back up; the next daemon adopts the replacement.
      awaitWarm(pool, 1)
      val reused = factory.openAndRender(descriptor, "reused", previewId)
      System.err.println(
        "[measure] next daemon: initialize ${reused.initializeMs}ms, render ${reused.renderMs}ms"
      )
      assertEquals(
        "the second launch should have adopted the replenished spare",
        2,
        pool.snapshot().adopted,
      )
      assertEquals(
        "no launch through the pool should have gone cold",
        0,
        pool.snapshot().coldLaunches,
      )
    } finally {
      pool.close()
    }
    // 5. Nothing left running: the adopted spares died with their daemons, the rest with the pool.
    val deadline = System.currentTimeMillis() + 30_000
    while (liveSpareWorkers() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(250)
    assertEquals("no spare worker JVM should outlive the pool", 0, liveSpareWorkers())
  }

  private class Opened(val initializeMs: Long, val renderMs: Long)

  /** Spawn, `initialize`, render [previewId] once, wait for `renderFinished`, shut down. */
  private fun SubprocessDaemonClientFactory.openAndRender(
    descriptor: DaemonLaunchDescriptor,
    tag: String,
    previewId: String,
  ): Opened {
    val notifications = LinkedBlockingQueue<Pair<String, JsonObject?>>()
    val spawn: DaemonSpawn = spawn(WorkspaceId("spare-adoption-$tag"), descriptor)
    val client =
      spawn.client(
        onNotification = { method, params -> notifications.put(method to params) },
        onClose = {},
      )
    try {
      val startedAt = System.nanoTime()
      val init =
        client.initialize(
          workspaceRoot = descriptor.workingDirectory,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          timeout = 600.seconds,
        )
      val initializeMs = (System.nanoTime() - startedAt) / 1_000_000
      assertEquals(2, init.protocolVersion)

      val renderStartedAt = System.nanoTime()
      val queued = client.renderNow(previews = listOf(previewId), tier = RenderTier.FAST)
      assertEquals(listOf(previewId), queued.queued)
      val finished = awaitRenderFinished(notifications, previewId)
      val renderMs = (System.nanoTime() - renderStartedAt) / 1_000_000
      val pngPath = finished?.get("pngPath")?.jsonPrimitive?.contentOrNull
      assertNotNull("renderFinished for $previewId should name a PNG", pngPath)
      assertTrue("PNG should exist at $pngPath", File(pngPath!!).isFile)
      return Opened(initializeMs, renderMs)
    } finally {
      spawn.shutdown(30.seconds)
    }
  }

  private fun awaitRenderFinished(
    notifications: LinkedBlockingQueue<Pair<String, JsonObject?>>,
    previewId: String,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + 300_000
    while (System.currentTimeMillis() < deadline) {
      val (method, params) =
        notifications.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: break
      val id = params?.get("id")?.jsonPrimitive?.contentOrNull
      if (method == "renderFailed" && id == previewId) error("render failed: $params")
      if (method == "renderFinished" && id == previewId) return params
    }
    error("no renderFinished for $previewId within the budget")
  }

  private fun awaitWarm(pool: SandboxSparePool, count: Int) {
    val deadline = System.currentTimeMillis() + 900_000
    while (pool.snapshot().warm < count && System.currentTimeMillis() < deadline) Thread.sleep(500)
    assertTrue(
      "expected $count warm spare(s), have ${pool.snapshot()}",
      pool.snapshot().warm >= count,
    )
  }

  private fun liveSpareWorkers(): Int =
    ProcessHandle.allProcesses()
      .filter { it.info().commandLine().orElse("").contains("sandboxWorker.spare=true") }
      .count()
      .toInt()

  /**
   * The real Android daemon as a launch descriptor — the same JVM flags and Robolectric properties
   * [RealAndroidHarnessLauncher] puts on its command line, in the shape the production client
   * spawns from. Two sandboxes, background boot, so there is exactly one worker slot to adopt into.
   */
  private fun descriptor(paths: RealModeScenarioPaths): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor(
      schemaVersion = 2,
      modulePath = ":spare-adoption",
      variant = "android",
      enabled = true,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = paths.classpath.map { it.absolutePath },
      jvmArgs =
        listOf(
          "--add-opens=java.base/java.io=ALL-UNNAMED",
          "--add-opens=java.base/java.lang=ALL-UNNAMED",
          "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
          "--add-opens=java.base/java.nio=ALL-UNNAMED",
          "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        ),
      systemProperties =
        mapOf(
          "composeai.render.outputDir" to paths.rendersDir.absolutePath,
          "composeai.harness.previewsManifest" to paths.manifestFile.absolutePath,
          "robolectric.graphicsMode" to "NATIVE",
          "robolectric.looperMode" to "PAUSED",
          "robolectric.conscryptMode" to "OFF",
          "robolectric.pixelCopyRenderMode" to "hardware",
          "robolectric.config.sdk" to "35",
          "roborazzi.test.record" to "true",
          DaemonProperties.Names.IDLE_TIMEOUT_MS to "600000",
          DaemonProperties.Names.SANDBOX_COUNT to "2",
          DaemonProperties.Names.BACKGROUND_SANDBOX_BOOT to "true",
        ),
      workingDirectory = paths.rendersDir.parentFile.absolutePath,
      manifestPath = paths.manifestFile.absolutePath,
    )
}
