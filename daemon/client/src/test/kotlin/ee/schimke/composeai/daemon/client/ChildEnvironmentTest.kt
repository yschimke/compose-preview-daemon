package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChildEnvironmentTest {

  private val parent =
    mapOf(
      "PATH" to "/usr/bin:/bin",
      "HOME" to "/home/me",
      "LANG" to "en_US.UTF-8",
      "LC_ALL" to "en_US.UTF-8",
      "TZ" to "UTC",
      "TMPDIR" to "/tmp",
      "JAVA_HOME" to "/opt/jdk",
      "JAVA_TOOL_OPTIONS" to "-Dcomposeai.daemon.sandboxCount=3",
      "ANDROID_HOME" to "/opt/android",
      "GRADLE_USER_HOME" to "/home/me/.gradle",
      "XDG_CACHE_HOME" to "/home/me/.cache",
      "COMPOSEAI_AGENT_ID" to "agent-7",
      "SERVE_TOKEN" to "t0k3n",
      "COMPOSE_PREVIEW_SERVE_TOKEN" to "t0k3n",
      "AWS_SECRET_ACCESS_KEY" to "k",
      "GITHUB_TOKEN" to "g",
    )

  @Test
  fun `default keeps what a daemon reads and drops everything else`() {
    val child = ChildEnvironment.Default.resolve(parent)

    assertThat(child)
      .containsExactlyEntriesIn(
        parent -
          setOf(
            "SERVE_TOKEN",
            "COMPOSE_PREVIEW_SERVE_TOKEN",
            "AWS_SECRET_ACCESS_KEY",
            "GITHUB_TOKEN",
          )
      )
  }

  @Test
  fun `inherit passes the parent through`() {
    assertThat(ChildEnvironment.Inherit.resolve(parent)).isEqualTo(parent)
  }

  @Test
  fun `extras are added and win over an inherited value`() {
    val policy =
      ChildEnvironment.Allowlist(extra = mapOf("HOME" to "/work", "COMPOSE_EXTRA" to "yes"))

    val child = policy.resolve(parent)

    assertThat(child["HOME"]).isEqualTo("/work")
    assertThat(child["COMPOSE_EXTRA"]).isEqualTo("yes")
    assertThat(child).doesNotContainKey("SERVE_TOKEN")
  }

  @Test
  fun `names and prefixes are the caller's to replace`() {
    val policy = ChildEnvironment.Allowlist(names = setOf("PATH"), prefixes = emptySet())

    assertThat(policy.resolve(parent)).containsExactly("PATH", "/usr/bin:/bin")
    assertThat(policy.plusNames("TZ").resolve(parent).keys).containsExactly("PATH", "TZ")
    assertThat(policy.plusExtra(mapOf("A" to "b")).resolve(parent).keys)
      .containsExactly("PATH", "A")
  }

  @Test
  fun `names match case-insensitively, as Windows spells them`() {
    val child =
      ChildEnvironment.Default.resolve(
        mapOf(
          "Path" to "C:\\Windows",
          "SystemRoot" to "C:\\Windows",
          "lc_ctype" to "C",
          "Token" to "x",
        )
      )

    assertThat(child.keys).containsExactly("Path", "SystemRoot", "lc_ctype")
  }

  @Test
  fun `the test JVM carries the planted variable`() {
    // Guards the two spawn tests below: without it they would pass vacuously.
    assertThat(System.getenv("SERVE_TOKEN")).isNotNull()
  }

  @Test
  fun `a daemon spawned by the factory does not see the parent's other variables`() {
    val env = spawnAndDump(SubprocessDaemonClientFactory())

    assertThat(env).doesNotContainKey("SERVE_TOKEN")
    System.getenv("PATH")?.let { assertThat(env["PATH"]).isEqualTo(it) }
  }

  @Test
  fun `a factory told to inherit passes everything through`() {
    assumeTrue(System.getenv("SERVE_TOKEN") != null)

    val env = spawnAndDump(SubprocessDaemonClientFactory(environment = ChildEnvironment.Inherit))

    assertThat(env["SERVE_TOKEN"]).isEqualTo(System.getenv("SERVE_TOKEN"))
  }

  @Test
  fun `a factory allowlist puts its extras on the child`() {
    val env =
      spawnAndDump(
        SubprocessDaemonClientFactory(
          environment = ChildEnvironment.Allowlist(extra = mapOf("ENV_DUMP_EXTRA" to "present"))
        )
      )

    assertThat(env["ENV_DUMP_EXTRA"]).isEqualTo("present")
    assertThat(env).doesNotContainKey("SERVE_TOKEN")
  }

  @Test
  fun `a spare pool's production launcher applies its environment`() {
    val dir = Files.createTempDirectory("spare-env").toFile()
    val out = File(dir, "env.txt")
    val process =
      SandboxSparePool.processLauncher(ChildEnvironment.Default)(
        listOf(
          javaBin(),
          "-DenvDump.out=${out.absolutePath}",
          "-cp",
          System.getProperty("java.class.path"),
          EnvDumpMain::class.java.name,
        ),
        dir,
      )
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()

    val env = readDump(out)
    assertThat(env).doesNotContainKey("SERVE_TOKEN")
    assertThat(env).isNotEmpty()
    dir.deleteRecursively()
  }

  private fun spawnAndDump(factory: SubprocessDaemonClientFactory): Map<String, String> {
    val dir = Files.createTempDirectory("daemon-env").toFile()
    val out = File(dir, "env.txt")
    val descriptor =
      DaemonLaunchDescriptor.Builder(
          schemaVersion = 2,
          modulePath = ":env",
          variant = "desktop",
          enabled = true,
          mainClass = EnvDumpMain::class.java.name,
          classpath = System.getProperty("java.class.path").split(File.pathSeparator),
          jvmArgs = emptyList(),
          systemProperties = mapOf("envDump.out" to out.absolutePath),
          workingDirectory = dir.absolutePath,
          manifestPath = "",
        )
        .build()
    val spawn = factory.spawn(WorkspaceId("env-test"), descriptor)
    try {
      spawn.client(onNotification = { _, _ -> }, onClose = {})
      val deadline = System.currentTimeMillis() + 30_000
      while (!out.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
      return readDump(out)
    } finally {
      spawn.shutdown(5.seconds)
      dir.deleteRecursively()
    }
  }

  private fun readDump(out: File): Map<String, String> {
    assertThat(out.exists()).isTrue()
    return out
      .readLines()
      .filter { '=' in it }
      .associate { it.substringBefore('=') to it.substringAfter('=') }
  }

  private fun javaBin(): String = File(System.getProperty("java.home"), "bin/java").absolutePath
}
