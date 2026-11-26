package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.SystemPropertyArgs
import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * A daemon's `-D` arguments stay inline until one would exceed Linux's per-argument `execve` limit
 * (`MAX_ARG_STRLEN`, 128 KiB), which `composeai.daemon.userClassDirs` does on a target with ~1,500
 * runtime jars (compose-ai-tools#5945). Then they all move to an argfile the launcher expands.
 */
class SystemPropertyArgsTest {
  @Test
  fun `ordinary properties stay on the command line unchanged`() {
    val properties = mapOf("composeai.test.a" to "1", "composeai.test.b" to "two words")
    assertThat(SubprocessDaemonClientFactory.systemPropertyArgs(properties))
      .containsExactly("-Dcomposeai.test.a=1", "-Dcomposeai.test.b=two words")
      .inOrder()
  }

  @Test
  fun `a property past the execve limit reaches the JVM through an argfile`() {
    val dirs =
      (0 until 1_500).joinToString(File.pathSeparator) {
        "/home/builder/.cache/bazel/execroot/workspace/bazel-out/k8-fastbuild/bin/third_party/lib$it/lib$it.jar"
      }
    assertThat(dirs.length).isGreaterThan(128 * 1024)
    val properties =
      mapOf(
        DaemonProperties.Names.USER_CLASS_DIRS to dirs,
        "composeai.test.quoted" to "a \"quoted\" \\ value",
      )

    val args = SubprocessDaemonClientFactory.systemPropertyArgs(properties)

    assertThat(args).hasSize(1)
    assertThat(args.single()).startsWith("@")
    assertThat(args.single().length).isLessThan(4096)
    val argFile = File(args.single().substring(1))
    assertThat(argFile.name).startsWith("composeai-props-pid${ProcessHandle.current().pid()}-")
    try {
      // The real launcher reads the argfile back: both values arrive byte for byte.
      val java = File(System.getProperty("java.home"), "bin/java").absolutePath
      val probe =
        ProcessBuilder(java, args.single(), "-XshowSettings:properties", "-version")
          .redirectErrorStream(true)
          .start()
      val output = probe.inputStream.bufferedReader().readText()
      assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue()
      assertThat(probe.exitValue()).isEqualTo(0)
      assertThat(output).contains("lib1499/lib1499.jar")
      assertThat(output).contains("composeai.test.quoted = a \"quoted\" \\ value")
    } finally {
      argFile.delete()
    }
  }

  @Test
  fun `sweepStaleArgFiles removes dead-PID and aged legacy argfiles while preserving live ones`() {
    val dir = Files.createTempDirectory("sweep-argfiles-test").toFile()
    try {
      val now = 1_000_000_000L
      val maxAge = TimeUnit.MINUTES.toMillis(10)

      val deadLaunchDir = File(dir, "composeai-launch-pid111-1-abc").apply { mkdirs() }
      File(deadLaunchDir, "composeai-cp123.args").writeText("-cp foo")
      val liveLaunchDir = File(dir, "composeai-launch-pid222-1-def").apply { mkdirs() }
      File(liveLaunchDir, "composeai-cp456.args").writeText("-cp bar")

      val deadProps = File(dir, "composeai-props-pid111-abc.args").apply { writeText("x") }
      val liveProps = File(dir, "composeai-props-pid222-def.args").apply { writeText("y") }
      val oldLegacyCp =
        File(dir, "composeai-cp999.args").apply {
          writeText("old")
          setLastModified(now - maxAge - 1_000L)
        }
      val freshLegacyCp =
        File(dir, "composeai-cp888.args").apply {
          writeText("fresh")
          setLastModified(now - 5_000L)
        }
      val unrelated = File(dir, "keep-me.args").apply { writeText("keep") }

      val removed =
        SystemPropertyArgs.sweepStaleArgFiles(
          directory = dir,
          legacyMaxAgeMs = maxAge,
          nowMs = now,
          isPidAlive = { pid -> pid == 222L },
        )

      assertThat(removed).isEqualTo(3)
      assertThat(deadLaunchDir.exists()).isFalse()
      assertThat(deadProps.exists()).isFalse()
      assertThat(oldLegacyCp.exists()).isFalse()
      assertThat(liveLaunchDir.exists()).isTrue()
      assertThat(liveProps.exists()).isTrue()
      assertThat(freshLegacyCp.exists()).isTrue()
      assertThat(unrelated.exists()).isTrue()
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `extractArgFiles matches only composeai props and cp argfiles`() {
    val extracted =
      SystemPropertyArgs.extractArgFiles(
        listOf(
          "/usr/bin/java",
          "-Xmx1g",
          "@/tmp/composeai-props-pid42-1.args",
          "@/tmp/composeai-cp12345.args",
          "@/tmp/other.args",
          "com.example.Main",
        ),
      )
    assertThat(extracted.map { it.path })
      .containsExactly("/tmp/composeai-props-pid42-1.args", "/tmp/composeai-cp12345.args")
      .inOrder()
  }
}
