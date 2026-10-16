package ee.schimke.composeai.daemon.client

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
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
  }
}
