package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.pool.SandboxProcessPool
import ee.schimke.composeai.daemon.pool.SandboxWorkerMain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
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

  /**
   * A booted, warm, listening spare launched the way the spare pool launches one. [handshakes]
   * receives every handshake line after the first — a released worker announces its next port.
   */
  private class LaunchedSpare(
    val process: Process,
    val pid: Long,
    val port: Int,
    val handshakes: LinkedBlockingQueue<String>,
  )

  private fun launchSpare(): LaunchedSpare {
    // Experiment seams: `COMPOSEAI_TEST_SPARE_JAVA` runs the spare on another JDK,
    // `COMPOSEAI_TEST_SPARE_JVM_ARGS` adds flags (space-separated) — for measuring a JVM lever on
    // the spare without touching what the pool launches.
    val command =
      SandboxProcessPool.spareWorkerCommandForTest().toMutableList().apply {
        System.getenv("COMPOSEAI_TEST_SPARE_JAVA")?.takeIf { it.isNotBlank() }?.let { set(0, it) }
        System.getenv("COMPOSEAI_TEST_SPARE_JVM_ARGS")
          ?.split(' ')
          ?.filter { it.isNotBlank() }
          ?.let { addAll(1, it) }
      }
    val spare = ProcessBuilder(command).redirectErrorStream(false).start()
    pump(spare.errorStream)
    val handshakes = LinkedBlockingQueue<String>()
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
            {
              runCatching {
                reader.forEachLine {
                  if (it.startsWith(SandboxWorkerMain.SPARE_HANDSHAKE_PREFIX)) handshakes.put(it)
                  else System.err.println("[spare] $it")
                }
              }
            },
            "spare-stdout",
          )
          .apply { isDaemon = true }
          .start()
        found
      }
    assertNotNull("spare worker never announced itself; see [spare] stderr above", handshake)
    val port = Regex("port=(\\d+)").find(handshake!!)!!.groupValues[1].toInt()
    val pid = Regex("pid=(\\d+)").find(handshake)!!.groupValues[1].toLong()
    // Diagnostics for the memory profile (Linux only; STARTUP.md § "The pool, before and after").
    System.err.println(
      "[measure] spare warm RSS: ${rssKb(pid)} kB; host JVM before start: " +
        "${rssKb(ProcessHandle.current().pid())} kB"
    )
    if (System.getenv("COMPOSEAI_TEST_SPARE_JVM_ARGS")?.contains("NativeMemoryTracking") == true) {
      Thread.sleep(5_000)
      System.err.println("[measure] spare RSS after 5 s idle: ${rssKb(pid)} kB")
      val jcmd = File(File(command[0]).parentFile, "jcmd")
      runCatching {
        val out =
          ProcessBuilder(jcmd.path, pid.toString(), "VM.native_memory", "summary")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
        out
          .lines()
          .filter { it.contains("committed=") }
          .forEach { System.err.println("[nmt] ${it.trim()}") }
      }
        .onFailure { System.err.println("[nmt] failed: $it") }
    }
    return LaunchedSpare(spare, pid, port, handshakes)
  }

  @Test
  fun aReleasedSpareListensAgainAndTheNextHostAdoptsIt() {
    // What a daemon's clean shutdown does to an adopted worker, and what the next daemon gets:
    // the worker survives its first host, announces a fresh port, and serves a second host —
    // with a different catalog — without a boot in between.
    val outputDir = Files.createTempDirectory("spare-release").toFile()
    val spare = launchSpare()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(RobolectricHost.BACKGROUND_BOOT_PROP, "true")
    System.setProperty(DaemonProperties.Names.LAZY_IN_PROCESS_SANDBOX, "true")
    try {
      // First host: no catalog at all, one stub render on the worker, then a clean shutdown.
      System.setProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES, spare.port.toString())
      val first = RobolectricHost(sandboxCount = 2)
      first.start()
      assertEquals(listOf(spare.pid), first.workerPidsForTest())
      assertNotNull(
        first.submit(RenderRequest.Render(target = RenderTarget.Stub("first")), timeoutMs = 120_000)
      )
      first.shutdown()
      val released = spare.handshakes.poll(30, TimeUnit.SECONDS)
      assertNotNull("the released worker should announce a new port", released)
      assertTrue("the worker should outlive the host that released it", spare.process.isAlive)
      val nextPort = Regex("port=(\\d+)").find(released!!)!!.groupValues[1].toInt()
      assertTrue("a fresh listener, not the old one", nextPort != spare.port)

      // Second host: the fixture catalog this time, adopted through the new port.
      val userClassesDir = stageFixtureClassesDir()
      System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, userClassesDir.absolutePath)
      System.setProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES, nextPort.toString())
      val second = RobolectricHost(sandboxCount = 2)
      try {
        val startedAt = System.nanoTime()
        second.start()
        val startMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(listOf(spare.pid), second.workerPidsForTest())
        assertTrue("re-adoption should take adoption time, took ${startMs}ms", startMs < 30_000)
        val renderStartedAt = System.nanoTime()
        val result =
          second.submit(
            RenderRequest.Render(target = fixtureTarget("RedSquare", "after-release")),
            timeoutMs = 120_000,
          )
        assertNotNull(
          "the re-adopted worker should render the new catalog",
          result.artifact.pathOrNull(),
        )
        System.err.println(
          "[measure] re-adopted worker: start ${startMs}ms, first catalog render " +
            "${(System.nanoTime() - renderStartedAt) / 1_000_000}ms"
        )
      } finally {
        System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
        second.shutdown()
        userClassesDir.deleteRecursively()
      }
      // Released again — a pool would take it back; here nobody does, so end it.
      assertNotNull(spare.handshakes.poll(30, TimeUnit.SECONDS))
      spare.process.destroyForcibly()
    } finally {
      System.clearProperty(DaemonProperties.Names.LAZY_IN_PROCESS_SANDBOX)
      System.clearProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES)
      System.clearProperty(RobolectricHost.BACKGROUND_BOOT_PROP)
      spare.process.destroyForcibly()
      outputDir.deleteRecursively()
    }
  }

  @Test
  fun adoptedSpareServesRendersWhileTheInProcessSandboxBoots() {
    val outputDir = Files.createTempDirectory("spare-adoption").toFile()
    val userClassesDir = stageFixtureClassesDir()
    val spare = launchSpare()
    // Handed to the spare at adoption (`configure` forwards it), never at its launch: the spare
    // booted knowing no catalog, and the fixture classes are this "catalog".
    System.setProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP, userClassesDir.absolutePath)
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(RobolectricHost.BACKGROUND_BOOT_PROP, "true")
    System.setProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES, spare.port.toString())
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
      assertEquals(listOf(spare.pid), host.workerPidsForTest())

      // A render succeeds now, on the worker — slot 0 is still booting.
      val early =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-1")),
          timeoutMs = 120_000,
        )
      assertNotNull(early)
      // What the adopting catalog actually pays: its own first real renders, on a sandbox that
      // was warmed against no catalog. Foundation-only first, then Material 3 twice, so the
      // one-off class loading a richer warm-up could absorb is visible against a repeat.
      fun timedRender(function: String, tag: String): Long {
        val startedAt = System.nanoTime()
        val result =
          host.submit(
            RenderRequest.Render(target = fixtureTarget(function, tag)),
            timeoutMs = 120_000,
          )
        assertNotNull("$function should render a PNG", result.artifact.pathOrNull())
        return (System.nanoTime() - startedAt) / 1_000_000
      }
      val red = timedRender("RedSquare", "first-red")
      val m3First = timedRender("MaterialButtonInteractionState", "first-m3")
      val m3Second = timedRender("MaterialButtonInteractionState", "second-m3")
      val iconsFirst = timedRender("IconButtonRowInputBar", "first-icons")
      System.err.println(
        "[measure] adopted worker first renders: RedSquare ${red}ms, M3 first ${m3First}ms, " +
          "M3 second ${m3Second}ms, icons+M3 first ${iconsFirst}ms"
      )

      // The in-process sandbox comes up behind it and the pool completes.
      val deadline = System.currentTimeMillis() + 600_000
      while (host.readySlotCountForTest() < 2 && System.currentTimeMillis() < deadline) {
        Thread.sleep(200)
      }
      assertEquals("slot 0 should boot in the background", 2, host.readySlotCountForTest())
      System.err.println(
        "[measure] host JVM after slot 0 booted: ${rssKb(ProcessHandle.current().pid())} kB; " +
          "spare after a render: ${rssKb(spare.pid)} kB"
      )
      val later =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-2")),
          timeoutMs = 120_000,
        )
      assertNotNull(later)
    } finally {
      System.clearProperty(UserClassLoaderHolder.USER_CLASS_DIRS_PROP)
      System.clearProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES)
      System.clearProperty(RobolectricHost.BACKGROUND_BOOT_PROP)
      host.shutdown()
      userClassesDir.deleteRecursively()
      // Shutdown hands the adopted spare back rather than ending it: it announces a fresh port.
      assertNotNull(
        "adopted spare should be released, not killed",
        spare.handshakes.poll(30, TimeUnit.SECONDS),
      )
      spare.process.destroyForcibly()
      outputDir.deleteRecursively()
    }
  }

  @Test
  fun lazyInProcessSandboxBootsOnlyWhenSomethingNeedsIt() {
    // `composeai.daemon.lazyInProcessSandbox`: the adopted worker serves, and slot 0 stays down —
    // no boot, no sandbox's worth of memory — until a path that only slot 0 can serve asks for it.
    val outputDir = Files.createTempDirectory("spare-lazy-slot0").toFile()
    val spare = launchSpare()
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    System.setProperty(RobolectricHost.BACKGROUND_BOOT_PROP, "true")
    System.setProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES, spare.port.toString())
    System.setProperty(DaemonProperties.Names.LAZY_IN_PROCESS_SANDBOX, "true")
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()
      assertEquals(1, host.readySlotCountForTest())
      val rendered =
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-1")),
          timeoutMs = 120_000,
        )
      assertNotNull(rendered)
      // Long enough that a background boot of slot 0 would have shown up.
      Thread.sleep(8_000)
      assertEquals("slot 0 must stay deferred", 1, host.readySlotCountForTest())
      System.err.println(
        "[measure] host JVM with slot 0 deferred: ${rssKb(ProcessHandle.current().pid())} kB"
      )

      val startedAt = System.nanoTime()
      host.ensureInProcessSandboxForTest()
      System.err.println(
        "[measure] on-demand slot 0 boot: ${(System.nanoTime() - startedAt) / 1_000_000}ms; " +
          "host JVM after: ${rssKb(ProcessHandle.current().pid())} kB"
      )
      assertEquals("slot 0 boots on demand", 2, host.readySlotCountForTest())
      assertNotNull(
        host.submit(
          RenderRequest.Render(target = RenderTarget.Stub("render-2")),
          timeoutMs = 120_000,
        )
      )
    } finally {
      System.clearProperty(DaemonProperties.Names.LAZY_IN_PROCESS_SANDBOX)
      System.clearProperty(DaemonProperties.Names.SANDBOX_WORKER_SPARES)
      System.clearProperty(RobolectricHost.BACKGROUND_BOOT_PROP)
      host.shutdown()
      assertNotNull(spare.handshakes.poll(30, TimeUnit.SECONDS))
      spare.process.destroyForcibly()
      outputDir.deleteRecursively()
    }
  }

  private fun fixtureTarget(function: String, tag: String): RenderTarget.Spec =
    RenderTarget.Spec(
      RenderSpec(
        previewId = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.$function.$tag",
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = function,
        widthPx = 256,
        heightPx = 128,
        density = 2.0f,
        showBackground = true,
        outputBaseName = tag,
      )
    )

  /** The testFixtures classes, copied out so they can be a user-class dir of their own. */
  private fun stageFixtureClassesDir(): File {
    val tempDir = Files.createTempDirectory("spare-userClasses").toFile()
    val resourceName = "ee/schimke/composeai/daemon/RedFixturePreviewsKt.class"
    val url =
      (Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()).getResource(
        resourceName
      ) ?: error("Can't locate testFixtures class on the test classpath: $resourceName")
    val urlString = url.toString()
    if (urlString.startsWith("file:")) {
      val classFile = File(url.toURI())
      var root: File = classFile.parentFile ?: error("classFile has no parent: $classFile")
      repeat("ee/schimke/composeai/daemon".count { it == '/' } + 1) {
        root = root.parentFile ?: error("ran off the top walking up from $classFile")
      }
      root.copyRecursively(tempDir, overwrite = true)
      return tempDir
    }
    if (urlString.startsWith("jar:file:")) {
      val jarPath = urlString.removePrefix("jar:file:").substringBefore("!").let { File(it) }
      java.util.zip.ZipFile(jarPath).use { jar ->
        for (entry in jar.entries()) {
          if (!entry.name.startsWith("ee/schimke/composeai/daemon/") || entry.isDirectory) continue
          val out = File(tempDir, entry.name)
          out.parentFile?.mkdirs()
          jar.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
      }
      return tempDir
    }
    error("Unsupported testFixtures URL shape: $urlString")
  }

  private fun rssKb(pid: Long): Long? = runCatching {
    File("/proc/$pid/status")
      .readLines()
      .firstOrNull { it.startsWith("VmRSS:") }
      ?.filter { it.isDigit() }
      ?.toLong()
  }
    .getOrNull()

  private fun pump(stream: java.io.InputStream) {
    Thread(
        { stream.bufferedReader().forEachLine { System.err.println("[spare] $it") } },
        "spare-stderr",
      )
      .apply { isDaemon = true }
      .start()
  }
}
