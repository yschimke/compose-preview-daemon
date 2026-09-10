package ee.schimke.composeai.buildlogic

import java.io.File
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class DependencyOwnershipTest {
  @Test
  fun `allows contracts but rejects tools and server ownership`() {
    assertFalse(DependencyOwnership.isForbidden("ee.schimke.composeai:daemon-protocol"))
    assertFalse(DependencyOwnership.isForbidden("org.jetbrains.kotlin:kotlin-stdlib"))

    listOf(
        "compose-preview-config",
        "compose-preview-plugin",
        "compose-preview-render-host",
        "bundle-format",
        "compose-preview-serve",
      )
      .forEach { artifact ->
        assertTrue(DependencyOwnership.isForbidden("ee.schimke.composeai:$artifact"), artifact)
      }
  }

  @Test
  fun `covers JVM KMP and every Android production variant`() {
    listOf(
        "runtimeClasspath",
        "jvmRuntimeClasspath",
        "debugRuntimeClasspath",
        "releaseRuntimeClasspath",
        "demoBenchmarkRuntimeClasspath",
      )
      .forEach { assertTrue(DependencyOwnership.isProductionRuntimeClasspath(it), it) }

    listOf(
        "testRuntimeClasspath",
        "jvmTestRuntimeClasspath",
        "debugUnitTestRuntimeClasspath",
        "connectedAndroidTestRuntimeClasspath",
        "lintClassPath",
      )
      .forEach { assertFalse(DependencyOwnership.isProductionRuntimeClasspath(it), it) }
  }

  @Test
  fun `resolved transitive tools edge fails while contract and internal project pass`(
    @TempDir projectDir: File
  ) {
    val repository = projectDir.resolve("repository")
    publish(repository, "ee.schimke.composeai", "daemon-protocol")
    publish(repository, "ee.schimke.composeai", "compose-preview-render-host")
    publish(
      repository,
      "example",
      "bridge",
      dependency = "ee.schimke.composeai" to "compose-preview-render-host",
    )
    writeFixture(projectDir, repository, includeForbiddenBridge = true)

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("checkDependencyOwnership", "--stacktrace")
        .buildAndFail()

    assertTrue(result.output.contains("compose-preview-render-host [runtimeClasspath]"))
  }

  @Test
  fun `resolved contract and internal project dependencies pass`(@TempDir projectDir: File) {
    val repository = projectDir.resolve("repository")
    publish(repository, "ee.schimke.composeai", "daemon-protocol")
    writeFixture(projectDir, repository, includeForbiddenBridge = false)

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withPluginClasspath()
      .withArguments("checkDependencyOwnership", "--stacktrace")
      .build()
  }

  private fun writeFixture(projectDir: File, repository: File, includeForbiddenBridge: Boolean) {
    projectDir.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\ninclude 'internal'\n")
    projectDir.resolve("internal").mkdirs()
    projectDir.resolve("internal/build.gradle").writeText("plugins { id 'java-library' }\n")
    projectDir.resolve("build.gradle").writeText(
      """
      plugins {
        id 'java'
        id 'composeai.base-conventions'
      }
      repositories { maven { url = uri('${repository.invariantSeparatorsPath}') } }
      dependencies {
        implementation project(':internal')
        implementation 'ee.schimke.composeai:daemon-protocol:1.0'
        ${if (includeForbiddenBridge) "implementation 'example:bridge:1.0'" else ""}
      }
      """.trimIndent()
    )
  }

  private fun publish(
    repository: File,
    group: String,
    artifact: String,
    dependency: Pair<String, String>? = null,
  ) {
    val dir = repository.resolve("${group.replace('.', '/')}/$artifact/1.0").apply { mkdirs() }
    val dependencyXml =
      dependency?.let { (dependencyGroup, dependencyArtifact) ->
        """
        <dependencies><dependency>
          <groupId>$dependencyGroup</groupId><artifactId>$dependencyArtifact</artifactId>
          <version>1.0</version>
        </dependency></dependencies>
        """.trimIndent()
      } ?: ""
    dir.resolve("$artifact-1.0.pom").writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion><groupId>$group</groupId>
        <artifactId>$artifact</artifactId><version>1.0</version>$dependencyXml
      </project>
      """.trimIndent()
    )
    JarOutputStream(dir.resolve("$artifact-1.0.jar").outputStream()).use {}
  }
}
