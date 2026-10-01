package ee.schimke.composeai.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The root build's release wiring (`root-tasks.gradle.kts`) re-states the publish-set parsing rule,
 * because it cannot see build-logic's classes to call [PublishedVersions.parsePublishSet]. Two
 * copies of one rule is two chances to change one and not the other, and the first version of this
 * change did exactly that: the shared function learned that an empty set means "publish nothing"
 * while `printPublishTasks` kept collapsing empty to null, so an empty plan published all 69
 * coordinates anyway.
 *
 * This asserts the copy in `root-tasks.gradle.kts` has not drifted back.
 */
class PublishSetParsingTest {
  @Test
  fun `the root build does not collapse an empty publish set to null`() {
    val repoRoot =
      generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile && it.resolve("build-logic").isDirectory }
    val rootBuild = repoRoot.resolve("root-tasks.gradle.kts").readText()

    val block =
      rootBuild
        .substringAfter("""val publishSet =""", "")
        .substringBefore("val rows", "")
    assertTrue(block.isNotEmpty(), "could not find the publishSet block in root-tasks.gradle.kts")
    assertTrue("gradleProperty(\"composeai.publishSet\")" in block, block)
    // `takeIf { it.isNotEmpty() }` is precisely the bug: it turns an explicit empty set back into
    // "publish everything".
    assertFalse(
      "takeIf" in block,
      "root-tasks.gradle.kts collapses an empty publish set; empty must stay empty:\n$block",
    )
  }
}
