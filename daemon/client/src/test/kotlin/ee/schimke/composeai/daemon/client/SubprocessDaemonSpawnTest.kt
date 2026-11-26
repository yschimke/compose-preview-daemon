package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.SystemPropertyArgs
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.SourceWatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class SubprocessDaemonSpawnTest {
  @Test
  fun `bounded shutdown force kills after one total deadline`() {
    val process = NonTerminatingProcess()
    val spawn = SubprocessDaemonSpawn(process)
    spawn.client(onNotification = { _, _ -> }, onClose = {})

    val startedNanos = System.nanoTime()
    spawn.shutdown(50.milliseconds)
    val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

    assertThat(process.forciblyDestroyed).isTrue()
    assertThat(elapsedMillis).isLessThan(1_000L)
  }

  @Test
  fun `buildLaunchCommand stages argfiles in a per-launch dir removed by cleanup`() {
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = 2,
          modulePath = ":app",
          variant = "android",
          enabled = true,
          mainClass = "com.example.Main",
          classpath = listOf("/app/classes"),
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = "/tmp",
          manifestPath = "",
        )
        .build()

    val launch = buildLaunchCommand(descriptor)
    val argFiles = SystemPropertyArgs.extractArgFiles(launch.argv)
    assertThat(argFiles).hasSize(1)
    val launchDir = argFiles.single().parentFile
    assertThat(launchDir.name).startsWith("composeai-launch-pid${ProcessHandle.current().pid()}-")
    assertThat(argFiles.single().exists()).isTrue()

    launch.cleanup()
    assertThat(argFiles.single().exists()).isFalse()
    assertThat(launchDir.exists()).isFalse()
  }

  @Test
  fun `launchCommand forwards sourceWatch sourceDirs when not explicitly set`() {
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = 2,
          modulePath = ":app",
          variant = "android",
          enabled = true,
          mainClass = "com.example.Main",
          classpath = listOf("/app/classes"),
          jvmArgs = emptyList(),
          systemProperties = emptyMap(),
          workingDirectory = "/tmp",
          manifestPath = "",
        )
        .also {
          it.sourceWatch =
            SourceWatch.Builder()
              .also { sw ->
                sw.sourceDirs = listOf("/repo/app/src/main/kotlin", "/repo/app/src/debug/kotlin")
              }
              .build()
        }
        .build()

    val launch = buildLaunchCommand(descriptor)
    try {
      val expected =
        "-D${DaemonProperties.Names.SOURCE_DIRS}=" +
          "/repo/app/src/main/kotlin${File.pathSeparator}/repo/app/src/debug/kotlin"
      assertThat(launch.argv).contains(expected)
    } finally {
      launch.cleanup()
    }
  }

  @Test
  fun `launchCommand keeps explicit sourceDirs property over sourceWatch`() {
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = 2,
          modulePath = ":app",
          variant = "android",
          enabled = true,
          mainClass = "com.example.Main",
          classpath = listOf("/app/classes"),
          jvmArgs = emptyList(),
          systemProperties = mapOf(DaemonProperties.Names.SOURCE_DIRS to "/custom/src"),
          workingDirectory = "/tmp",
          manifestPath = "",
        )
        .also {
          it.sourceWatch =
            SourceWatch.Builder().also { sw -> sw.sourceDirs = listOf("/ignored/src") }.build()
        }
        .build()

    val launch = buildLaunchCommand(descriptor)
    try {
      assertThat(launch.argv).contains("-D${DaemonProperties.Names.SOURCE_DIRS}=/custom/src")
      assertThat(launch.argv).doesNotContain("-D${DaemonProperties.Names.SOURCE_DIRS}=/ignored/src")
    } finally {
      launch.cleanup()
    }
  }

  @Test
  fun `SubprocessDaemonSpawn runs argfile cleanup once on first stdout byte before exit`() {
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut)
    val exitFuture = CompletableFuture<Process>()
    val process = ControllableProcess(stdout = pipeIn, exitFuture = exitFuture)
    val cleanups = AtomicInteger(0)
    val cleanedLatch = CountDownLatch(1)

    val spawn =
      SubprocessDaemonSpawn(
        process = process,
        onProcessReadyOrExit = {
          cleanups.incrementAndGet()
          cleanedLatch.countDown()
        },
      )
    val client = spawn.client(onNotification = { _, _ -> }, onClose = {})
    try {
      assertThat(cleanups.get()).isEqualTo(0)
      // Simulate the daemon emitting its first JSON-RPC bytes (proof the launcher finished reading
      // @argfiles and entered main).
      val payload = """{"jsonrpc":"2.0","method":"noop"}"""
      pipeOut.write("Content-Length: ${payload.length}\r\n\r\n$payload".toByteArray())
      pipeOut.flush()
      assertThat(cleanedLatch.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(cleanups.get()).isEqualTo(1)

      // Later process exit must not invoke cleanup a second time.
      exitFuture.complete(process)
      assertThat(cleanups.get()).isEqualTo(1)
    } finally {
      runCatching { pipeOut.close() }
      runCatching { client.close() }
    }
  }

  @Test
  fun `SubprocessDaemonSpawn runs argfile cleanup on exit even with no stdout`() {
    val exitFuture = CompletableFuture<Process>()
    val process =
      ControllableProcess(stdout = ByteArrayInputStream(ByteArray(0)), exitFuture = exitFuture)
    val cleanups = AtomicInteger(0)

    SubprocessDaemonSpawn(process = process, onProcessReadyOrExit = { cleanups.incrementAndGet() })
    assertThat(cleanups.get()).isEqualTo(0)
    exitFuture.complete(process)
    assertThat(cleanups.get()).isEqualTo(1)
  }

  private class ControllableProcess(
    private val stdout: InputStream,
    private val exitFuture: CompletableFuture<Process>,
  ) : Process() {
    private val stdin = ByteArrayOutputStream()
    private val stderr = ByteArrayInputStream(ByteArray(0))

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() {
      exitFuture.complete(this)
    }

    override fun onExit(): CompletableFuture<Process> = exitFuture
  }

  private class NonTerminatingProcess : Process() {
    private val stdin = ByteArrayOutputStream()
    private val stdout = ByteArrayInputStream(ByteArray(0))
    private val stderr = ByteArrayInputStream(ByteArray(0))
    private var alive = true

    var forciblyDestroyed = false
      private set

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = throw InterruptedException("process does not exit")

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
      Thread.sleep(unit.toMillis(timeout))
      return false
    }

    override fun exitValue(): Int = throw IllegalThreadStateException("process is still running")

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
      forciblyDestroyed = true
      alive = false
      return this
    }

    override fun isAlive(): Boolean = alive
  }
}
