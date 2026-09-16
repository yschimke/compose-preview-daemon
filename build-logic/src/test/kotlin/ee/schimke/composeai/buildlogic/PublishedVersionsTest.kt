package ee.schimke.composeai.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version a skipped module carries is the one thing in the reduced-publish scheme that cannot
 * be got wrong quietly. A published POM names its project dependencies at their `project.version`,
 * so resolving a skipped module to the tag would upload a POM requiring a coordinate that was never
 * published — resolvable for nobody, and unrepairable, because Central refuses a second upload of a
 * version. That is compose-ai-tools v2.2.1 and yschimke/wear-m3-catalog#350.
 */
class PublishedVersionsTest {
  private val manifest =
    """
    {
      "modules": {
        "daemon-core": "3.4.2",
        "renderer-android": "3.5.0"
      }
    }
    """
      .trimIndent()

  @Test
  fun `a module in the publish set takes the tag version`() {
    assertEquals(
      "3.6.0",
      PublishedVersions.resolve("daemon-core", "3.6.0", setOf("daemon-core"), manifest),
    )
  }

  @Test
  fun `a module outside the publish set keeps the version it last published at`() {
    assertEquals(
      "3.4.2",
      PublishedVersions.resolve("daemon-core", "3.6.0", setOf("renderer-android"), manifest),
    )
  }

  @Test
  fun `a null publish set means every module publishes at the tag`() {
    assertEquals("3.6.0", PublishedVersions.resolve("daemon-core", "3.6.0", null, manifest))
    assertEquals("3.6.0", PublishedVersions.resolve("anything-at-all", "3.6.0", null, manifest))
  }

  @Test
  fun `a skipped module with no recorded version is an error, never the tag`() {
    val failure =
      assertFailsWith<IllegalStateException> {
        PublishedVersions.resolve("data-brand-new", "3.6.0", setOf("daemon-core"), manifest)
      }
    assertTrue("data-brand-new" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun `artifact ids are matched whole, not as prefixes`() {
    // `daemon-core` is a prefix of nothing here, but `renderer-android` would match a hypothetical
    // `renderer-android-foo` under a sloppy regex, and the two carry different versions.
    val text = """{"modules": {"renderer-android-extra": "1.0.0", "renderer-android": "3.5.0"}}"""
    assertEquals("3.5.0", PublishedVersions.recordedVersion("renderer-android", text))
    assertEquals("1.0.0", PublishedVersions.recordedVersion("renderer-android-extra", text))
    assertNull(PublishedVersions.recordedVersion("renderer", text))
  }

  @Test
  fun `an absent publish set property means publish everything`() {
    assertNull(PublishedVersions.parsePublishSet(null))
    assertEquals(setOf("a", "b"), PublishedVersions.parsePublishSet(" a , b "))
  }

  /**
   * Regression: an empty property used to parse to null, which the rest of the build reads as
   * "publish everything". A release whose changes are confined to `.github/` plans nothing, and
   * that release would then have uploaded all 69 coordinates while `record-published.py` recorded
   * none of them -- the maximum quota cost on exactly the release that needed none of it, and a
   * manifest left disagreeing with Central.
   */
  @Test
  fun `an explicitly empty publish set means publish nothing, not everything`() {
    assertEquals(emptySet(), PublishedVersions.parsePublishSet(""))
    assertEquals(emptySet(), PublishedVersions.parsePublishSet("  , ,"))
    // And it must not be mistaken for the publish-everything case.
    assertEquals(
      "3.4.2",
      PublishedVersions.resolve("daemon-core", "3.6.0", emptySet(), manifest),
    )
  }

  // `the committed manifest covers every published module` lived here. It guarded against a
  // module falling out of a hand-maintained list and being republished every release forever.
  //
  // There is no list to fall out of now: the plan asks Maven Central for whatever modules it
  // discovers in `settings.gradle.kts`, and a coordinate Central has never seen resolves to
  // "publish". What remains worth guarding is the discovery itself, which is why an unreadable
  // build file is a hard error in `maven-publish-plan.sh` rather than a skip.
}
