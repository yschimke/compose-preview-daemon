package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.config.DaemonProperties
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * [SandboxSparePool] without a Robolectric JVM in sight: the launcher seam hands back fake
 * processes whose stdout the test drives, so the handshake, the signature, the budget and the
 * hand-off are all asserted on the pool's own bookkeeping.
 */
class SandboxSparePoolTest {

  private val launched = mutableListOf<Pair<List<String>, FakeSpare>>()

  private fun pool(config: SandboxSparePool.Config): SandboxSparePool =
    SandboxSparePool(
      config = config.copy(bootTimeoutMs = 5_000),
      log = {},
      launcher = { command, _ -> FakeSpare().also { launched += command to it } },
    )

  private fun descriptor(
    classpath: List<String> = listOf("/opt/overlay.jar", "/opt/lib-daemon-android/daemon.jar"),
    jvmArgs: List<String> =
      listOf("-XX:+UseSerialGC", "-XX:SharedArchiveFile=/cds/android-abc.jsa"),
    systemProperties: Map<String, String> =
      mapOf(
        "robolectric.offline" to "true",
        DaemonProperties.Names.USER_CLASS_DIRS to "/catalog/classes",
        "composeai.render.outputDir" to "/catalog/renders",
      ),
    variant: String = "android",
  ) =
    DaemonLaunchDescriptor(
      schemaVersion = 2,
      modulePath = ":catalog",
      variant = variant,
      enabled = true,
      mainClass = "ee.schimke.composeai.daemon.DaemonMain",
      classpath = classpath,
      jvmArgs = jvmArgs,
      systemProperties = systemProperties,
      workingDirectory = "/tmp",
      manifestPath = "/catalog/previews.json",
    )

  private fun awaitWarm(pool: SandboxSparePool, count: Int) {
    val deadline = System.currentTimeMillis() + 5_000
    while (pool.snapshot().warm < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertThat(pool.snapshot().warm).isAtLeast(count)
  }

  @Test
  fun `the signature ignores the catalog's own properties and the archive path`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 0))
    val a = descriptor()
    val sameSandboxOtherCatalog =
      descriptor(
        jvmArgs = listOf("-XX:+UseSerialGC", "-XX:SharedArchiveFile=/cds/android-xyz.jsa"),
        systemProperties =
          mapOf(
            "robolectric.offline" to "true",
            DaemonProperties.Names.USER_CLASS_DIRS to "/other/classes",
            "composeai.render.outputDir" to "/other/renders",
          ),
      )
    val otherOverlay = descriptor(classpath = listOf("/opt/other-overlay.jar", "/opt/daemon.jar"))
    val otherSdk = descriptor(systemProperties = mapOf("robolectric.offline" to "false"))

    assertThat(pool.signatureOf(sameSandboxOtherCatalog)).isEqualTo(pool.signatureOf(a))
    assertThat(pool.signatureOf(otherOverlay)).isNotEqualTo(pool.signatureOf(a))
    assertThat(pool.signatureOf(otherSdk)).isNotEqualTo(pool.signatureOf(a))
  }

  @Test
  fun `a spare is launched generic, in spare mode, with its own archive`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 1, perSignature = 1))
    val command = pool.spareCommand(descriptor(), archiveSlot = 1)

    assertThat(command).contains("-D${DaemonProperties.Names.SANDBOX_WORKER_SPARE}=true")
    assertThat(command).contains("-D${DaemonProperties.Names.SANDBOX_COUNT}=1")
    assertThat(command).contains("-Drobolectric.offline=true")
    assertThat(command).contains("-XX:SharedArchiveFile=/cds/android-abc-spare1.jsa")
    assertThat(command.last()).isEqualTo(SandboxSparePool.SANDBOX_WORKER_MAIN_CLASS)
    assertThat(command).contains("-XX:MaxHeapFreeRatio=30")
    assertThat(command).contains("-XX:MinHeapFreeRatio=10")
    val decided = descriptor(jvmArgs = listOf("-XX:MaxHeapFreeRatio=50", "-XX:MinHeapFreeRatio=20"))
    val respected = pool.spareCommand(decided, archiveSlot = 0)
    assertThat(respected).contains("-XX:MaxHeapFreeRatio=50")
    assertThat(respected).doesNotContain("-XX:MaxHeapFreeRatio=30")
    assertThat(respected).doesNotContain("-XX:MinHeapFreeRatio=10")
    assertThat(command.any { it.contains("userClassDirs") }).isFalse()
    assertThat(command.any { it.contains("outputDir") }).isFalse()
  }

  @Test
  fun `the native heap trim rides along only on a JDK that has it`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 1, perSignature = 1))
    val on21 = pool.spareCommand(descriptor(), archiveSlot = 0, javaFeatureVersion = 21)
    assertThat(on21).contains("-XX:TrimNativeHeapInterval=1000")
    assertThat(on21).contains("-XX:+UnlockExperimentalVMOptions")
    val on17 = pool.spareCommand(descriptor(), archiveSlot = 0, javaFeatureVersion = 17)
    assertThat(on17.any { it.contains("TrimNativeHeap") }).isFalse()
    // A launcher the pool did not pick, or a descriptor that already decides, is left alone.
    val other = descriptor().copy(javaLauncher = "/opt/jdk/bin/java")
    assertThat(
        pool.spareCommand(other, 0, javaFeatureVersion = 21).any { it.contains("TrimNativeHeap") }
      )
      .isFalse()
    val off = pool(SandboxSparePool.Config(maxSpares = 1, perSignature = 1, trimNativeHeapMs = 0))
    assertThat(
        off.spareCommand(descriptor(), 0, javaFeatureVersion = 21).any {
          it.contains("TrimNativeHeap")
        }
      )
      .isFalse()
  }

  @Test
  fun `reserve hands out only warm spares of the same signature, once`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 4, perSignature = 2))
    val d = descriptor()
    assertThat(pool.reserve(d, 2)).isEmpty() // nothing warm yet: a cold launch
    pool.ensure(d)
    awaitLaunched(1)
    assertThat(pool.reserve(d, 2)).isEmpty() // launched but not yet warm
    // Boots are serial: the second spare is launched once the first has handshaken.
    launched[0].second.handshake(pid = 101, port = 40001)
    awaitLaunched(2)
    launched[1].second.handshake(pid = 102, port = 40002)
    awaitWarm(pool, 2)

    assertThat(pool.reserve(descriptor(classpath = listOf("/elsewhere.jar")), 2)).isEmpty()
    assertThat(pool.reserve(d, 1)).containsExactly(40001)
    assertThat(pool.reserve(d, 5)).containsExactly(40002)
    assertThat(pool.reserve(d, 1)).isEmpty()
    val snapshot = pool.snapshot()
    assertThat(snapshot.adopted).isEqualTo(2)
    assertThat(snapshot.coldLaunches).isEqualTo(4)
    pool.close()
  }

  @Test
  fun `ensure tops a signature up to its target within the total budget`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 3, perSignature = 2))
    val a = descriptor()
    val b = descriptor(classpath = listOf("/opt/b.jar"))
    pool.ensure(a)
    assertThat(pool.snapshot().booting).isEqualTo(2) // one launching, one queued behind it
    pool.ensure(a) // idempotent: both are already accounted for
    assertThat(pool.snapshot().booting).isEqualTo(2)
    pool.ensure(b) // one slot of budget left
    assertThat(pool.snapshot().booting).isEqualTo(3)
    awaitLaunched(1)
    pool.close()
    assertThat(launched.all { it.second.destroyed }).isTrue()
  }

  @Test
  fun `a spare of a less recently used signature is evicted to make room`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 2, perSignature = 2))
    val a = descriptor()
    val b = descriptor(classpath = listOf("/opt/b.jar"))
    pool.reserve(a, 1)
    pool.ensure(a)
    awaitLaunched(1)
    launched[0].second.handshake(1, 40001)
    awaitLaunched(2)
    launched[1].second.handshake(2, 40002)
    awaitWarm(pool, 2)

    pool.reserve(b, 1) // b is now the more recently used signature
    pool.ensure(b)
    awaitLaunched(3)
    assertThat(launched[0].second.destroyed && launched[1].second.destroyed).isTrue()
    assertThat(pool.snapshot().signatures).isEqualTo(1)
    assertThat(pool.snapshot().booting).isEqualTo(2)
    pool.close()
  }

  @Test
  fun `a spare that dies while booting is dropped`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 1, perSignature = 1))
    pool.ensure(descriptor())
    awaitLaunched(1)
    launched[0].second.die()
    val deadline = System.currentTimeMillis() + 5_000
    while (pool.snapshot().booting > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertThat(pool.snapshot().booting).isEqualTo(0)
    pool.close()
  }

  @Test
  fun `the factory puts reserved ports on an android launch and asks for a top-up`() {
    val pool = pool(SandboxSparePool.Config(maxSpares = 2, perSignature = 1))
    val d = descriptor(systemProperties = mapOf(DaemonProperties.Names.SANDBOX_COUNT to "3"))
    pool.ensure(d)
    awaitLaunched(1)
    launched[0].second.handshake(7, 40007)
    awaitWarm(pool, 1)
    val factory = SubprocessDaemonClientFactory(pool)

    val launch = factory.withReservedSpares(d)
    assertThat(launch.spareEligible).isTrue()
    assertThat(launch.descriptor.systemProperties[DaemonProperties.Names.SANDBOX_WORKER_SPARES])
      .isEqualTo("40007")

    val desktop = factory.withReservedSpares(d.copy(variant = "desktop"))
    assertThat(desktop.spareEligible).isFalse()
    assertThat(desktop.descriptor).isEqualTo(d.copy(variant = "desktop"))

    val single =
      factory.withReservedSpares(
        d.copy(systemProperties = mapOf(DaemonProperties.Names.SANDBOX_COUNT to "1"))
      )
    assertThat(single.spareEligible).isFalse()
    pool.close()
  }

  private fun awaitLaunched(count: Int) {
    val deadline = System.currentTimeMillis() + 5_000
    while (launched.size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertThat(launched.size).isAtLeast(count)
  }

  /** A process whose stdout the test writes. */
  private class FakeSpare : Process() {
    private val stdoutSink = PipedOutputStream()
    private val stdout = PipedInputStream(stdoutSink, 4096)
    private val stderr = ByteArrayInputStream(ByteArray(0))
    private val stdin = ByteArrayOutputStream()
    @Volatile private var alive = true
    @Volatile var destroyed = false

    fun handshake(pid: Long, port: Int) {
      stdoutSink.write("noise from robolectric\n".toByteArray())
      stdoutSink.write(
        "${SandboxSparePool.SPARE_HANDSHAKE_PREFIX} pid=$pid port=$port\n".toByteArray()
      )
      stdoutSink.flush()
    }

    fun die() {
      alive = false
      stdoutSink.close()
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
      while (alive) Thread.sleep(10)
      return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

    override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

    override fun destroy() = destroyForcibly().let {}

    override fun destroyForcibly(): Process {
      destroyed = true
      alive = false
      runCatching { stdoutSink.close() }
      return this
    }

    override fun isAlive(): Boolean = alive

    override fun pid(): Long = 4242
  }
}
