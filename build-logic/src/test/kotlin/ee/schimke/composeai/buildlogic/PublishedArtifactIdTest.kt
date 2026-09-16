package ee.schimke.composeai.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the one convention `:bom` derives its constraints from.
 *
 * The BOM does not carry a list of coordinates. `settings.gradle.kts` hands it the project paths
 * whose build script applies `composeai.maven-publishing`, and it flattens each path into an
 * artifact id — `:daemon:core` becomes `daemon-core`. That derivation is only safe while every
 * published module's declared `artifactId` agrees with it, and nothing in Gradle enforces the
 * agreement: a module is free to publish under any name it likes.
 *
 * A module that broke the convention would not fail to build. It would publish normally and simply
 * be absent from the BOM under its real name, while the BOM constrained a coordinate that does not
 * exist — the failure landing on a consumer, at resolution, one release later. So it is checked
 * here, against the build files, on every `check`.
 */
class PublishedArtifactIdTest {
  @Test
  fun `every published module's artifactId is its project path flattened`() {
    val repoRoot = findRepoRoot()
    val settings = repoRoot.resolve("settings.gradle.kts").readText()

    val projectDirs = mutableMapOf<String, String>()
    Regex("""project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)""").findAll(settings).forEach {
      projectDirs[it.groupValues[1]] = it.groupValues[2]
    }
    val paths = Regex("""^include\("(:[^"]+)"\)""", RegexOption.MULTILINE)
      .findAll(settings)
      .map { it.groupValues[1] }
      .toList()

    assertTrue(paths.size > 50, "expected the full module list, found ${paths.size}")

    var checked = 0
    paths.forEach { path ->
      val dir = repoRoot.resolve(projectDirs[path] ?: path.removePrefix(":").replace(':', '/'))
      val buildFile = dir.resolve("build.gradle.kts")
      if (!buildFile.isFile) return@forEach
      val text = buildFile.readText()
      // The closing quote matters: `composeai.maven-publishing-platform` (the BOM) shares this
      // id's first 26 characters, and it publishes under a name that is deliberately not its
      // path — `:bom` -> `compose-preview-daemon-bom`. `settings.gradle.kts` selects the same way.
      if (!text.contains("composeai.maven-publishing\")")) return@forEach

      val declared =
        Regex("""artifactId\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
          ?: error("$path applies composeai.maven-publishing but declares no artifactId")
      assertEquals(path.removePrefix(":").replace(':', '-'), declared, "artifactId for $path")
      checked++
    }

    assertTrue(checked > 50, "expected to check the published modules, checked $checked")
  }

  private fun findRepoRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .first { it.resolve("settings.gradle.kts").isFile && it.resolve("build-logic").isDirectory }
}
