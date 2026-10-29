package ee.schimke.composeai.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * compose-ai-tools#6239: the daemon routes previewIds through the `previews.json` the Gradle plugin
 * rewrites on every discovery, so a `@Preview` added while the daemon runs must route without a
 * restart.
 */
class PreviewManifestRouterFollowedManifestTest {

  private lateinit var file: File
  private var modified = 1_000_000L

  @Before
  fun setUp() {
    file = Files.createTempFile("previews", ".json").toFile()
  }

  @After
  fun tearDown() {
    file.delete()
  }

  private fun entry(id: String) =
    PreviewManifestEntry(id = id, className = "com.example.PreviewsKt", functionName = id)

  /** Writes [ids] as the manifest, a second apart so every rewrite is a visible change. */
  private fun write(vararg ids: String): PreviewManifest {
    val manifest = PreviewManifest(previews = ids.map(::entry))
    file.writeText(Json.encodeToString(PreviewManifest.serializer(), manifest))
    modified += 1_000L
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified))
    return manifest
  }

  private fun PreviewManifestRouter.routedClassFunction(previewId: String): String? =
    rowAddressed(previewId)?.entry?.functionName

  @Test
  fun `a preview added to the manifest file routes without a restart`() {
    val router = PreviewManifestRouter(manifest = write("Old"), manifestFile = file)
    assertNull(router.routedClassFunction("New"))

    write("Old", "New")

    assertEquals("New", router.routedClassFunction("New"))
    val routed = router.routeTarget(RenderTarget.Preview(previewId = "New")) as RenderTarget.Spec
    assertEquals("New", routed.spec.functionName)
  }

  @Test
  fun `a preview removed from the manifest file stops routing`() {
    val router = PreviewManifestRouter(manifest = write("Kept", "Removed"), manifestFile = file)
    assertEquals("Removed", router.routedClassFunction("Removed"))

    write("Kept")

    assertNull(router.routedClassFunction("Removed"))
  }

  @Test
  fun `a manifest file caught mid-rewrite keeps routing the previous manifest`() {
    val router = PreviewManifestRouter(manifest = write("Old"), manifestFile = file)
    assertEquals("Old", router.routedClassFunction("Old"))

    file.writeText("""{"previews":[{"id":"New",""")
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified + 1_000L))

    assertEquals("Old", router.routedClassFunction("Old"))
  }

  @Test
  fun `a router given no manifest file keeps the manifest it was handed`() {
    val router = PreviewManifestRouter(manifest = write("Old"))

    write("Old", "New")

    assertNull(router.routedClassFunction("New"))
  }
}
