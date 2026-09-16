package ee.schimke.composeai.renderer

import ee.schimke.composeai.fonts.google.GoogleFontKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GoogleFontCache], [GoogleFontKey], the CSS-API helpers, and the
 * `FontRequest.query` parser that underpins [ShadowFontsContractCompat].
 *
 * No network access: the download path is stubbed with a canned byte array so the test is
 * deterministic. No Robolectric runner either — these are pure JVM helpers, and the shadow is
 * exercised end-to-end via `:samples:android:composePreviewRenderAll`.
 */
class GoogleFontInterceptorTest {
  @Test
  fun `parseFontRequestQuery reads the Compose GoogleFont wire format`() {
    val query = "name=Roboto%20Mono&weight=500&width=100.0&italic=0.0&besteffort=true"
    val key = parseFontRequestQuery(query)
    assertNotNull(key)
    assertEquals("Roboto Mono", key!!.name)
    assertEquals(500, key.weight)
    assertFalse(key.italic)
  }

  @Test
  fun `parseFontRequestQuery treats italic floats above half as italic`() {
    val key = parseFontRequestQuery("name=Inter&weight=700&italic=1.0&besteffort=true")
    assertNotNull(key)
    assertTrue(key!!.italic)
    assertEquals(700, key.weight)
  }

  @Test
  fun `parseFontRequestQuery defaults missing weight to 400 and italic to false`() {
    val key = parseFontRequestQuery("name=Inter&besteffort=true")
    assertNotNull(key)
    assertEquals(400, key!!.weight)
    assertFalse(key.italic)
  }

  @Test
  fun `parseFontRequestQuery returns null when name is missing or blank`() {
    assertNull(parseFontRequestQuery(null))
    assertNull(parseFontRequestQuery("weight=400&italic=0.0"))
    assertNull(parseFontRequestQuery("name=&weight=400"))
  }

  @Test
  fun `parseVariationAxes reads both join shapes and reads nothing from junk`() {
    assertEquals(
      listOf("wght" to 750f, "GRAD" to 0f, "opsz" to 9f, "slnt" to -10f),
      parseVariationAxes("'wght' 750, 'GRAD' 0,'opsz' 9, 'slnt' -10"),
    )
    assertEquals(emptyList<Pair<String, Float>>(), parseVariationAxes(null))
    assertEquals(emptyList<Pair<String, Float>>(), parseVariationAxes(""))
    assertEquals(emptyList<Pair<String, Float>>(), parseVariationAxes("wght=750"))
  }

  @Test
  fun `a request with no axes keeps resolving through the CSS API`() {
    val key = GoogleFontKey("Lato", 400, italic = false)
    assertFalse(requiresVariableFace(null, key))
    assertFalse(requiresVariableFace("", key))
  }

  @Test
  fun `axes the static instance already bakes do not need the variable file`() {
    assertFalse(requiresVariableFace("'wght' 500", GoogleFontKey("Lato", 500, italic = false)))
    assertFalse(
      requiresVariableFace("'wght' 500, 'ital' 1", GoogleFontKey("Lato", 500, italic = true))
    )
  }

  @Test
  fun `a wght that differs from the requested weight needs the variable file`() {
    // The Glimmer shape: `Font(...)` stays at the default W400 so the query asks for the 400 face,
    // and the role's real weight rides in the axes — where a static instance drops it.
    assertTrue(
      requiresVariableFace("'wght' 750", GoogleFontKey("Google Sans Flex", 400, italic = false))
    )
    assertTrue(requiresVariableFace("'ital' 1", GoogleFontKey("Lato", 400, italic = false)))
  }

  @Test
  fun `any axis beyond wght and ital needs the variable file`() {
    val key = GoogleFontKey("Google Sans Flex", 400, italic = false)
    assertTrue(requiresVariableFace("'ROND' 100", key))
    assertTrue(requiresVariableFace("'opsz' 9", key))
    assertTrue(requiresVariableFace("'GRAD' 0", key))
    assertTrue(requiresVariableFace("'wdth' 100", key))
  }

  @Test
  fun `a recorded face wins over the weight-named file the cache directory holds`() {
    val dir = java.nio.file.Files.createTempDirectory("fonts").toFile()
    val stale = java.io.File(dir, "google-sans-flex-400.ttf").apply { writeText("stale") }
    val variable =
      java.io.File(dir, "google-sans-flex-variable.ttf").apply { writeText("variable") }
    val previous = System.getProperty("composeai.fonts.cacheDir")
    System.setProperty("composeai.fonts.cacheDir", dir.absolutePath)
    try {
      GoogleFontFiles.resetForTest()
      // Without a record the directory answers, which is the pre-existing behaviour.
      assertEquals(stale, GoogleFontFiles.cached("Google Sans Flex", 400, italic = false))
      // The axes-bearing render resolved the variable file, so that is what the export must embed
      // — not the static instance a shared machine cache happens to hold under the weight name.
      GoogleFontFiles.record(GoogleFontKey("Google Sans Flex", 400, italic = false), variable)
      assertEquals(variable, GoogleFontFiles.cached("Google Sans Flex", 400, italic = false))
    } finally {
      GoogleFontFiles.resetForTest()
      if (previous == null) System.clearProperty("composeai.fonts.cacheDir")
      else System.setProperty("composeai.fonts.cacheDir", previous)
    }
  }

  @Test
  fun `dropped axes are recorded per resolution and read back by face`() {
    try {
      GoogleFontFiles.resetForTest()
      // Nothing recorded: null, not false. Whether axes were dropped is a property of a
      // RESOLUTION, so a face this process never resolved has no answer, and inventing one would
      // claim the axes applied when nothing checked.
      assertNull(GoogleFontFiles.droppedVariationSettings("Google Sans Flex", 400, italic = false))

      GoogleFontFiles.recordAxesDropped(
        GoogleFontKey("Google Sans Flex", 400, italic = false),
        "'wght' 750",
      )
      assertEquals(
        "'wght' 750",
        GoogleFontFiles.droppedVariationSettings("Google Sans Flex", 400, italic = false),
      )
      // Keyed by face, so a sibling weight and the italic of the same family stay unanswered.
      assertNull(GoogleFontFiles.droppedVariationSettings("Google Sans Flex", 500, italic = false))
      assertNull(GoogleFontFiles.droppedVariationSettings("Google Sans Flex", 400, italic = true))
      assertNull(GoogleFontFiles.droppedVariationSettings("Lato", 400, italic = false))
    } finally {
      GoogleFontFiles.resetForTest()
    }
  }

  @Test
  fun `the axes registry is not deduplicated the way the stderr warning is`() {
    try {
      GoogleFontFiles.resetForTest()
      val key = GoogleFontKey("Google Sans Flex", 400, italic = false)
      // FontResolutionDiagnostics warns once per process. The registry must NOT: the recorder asks
      // per resolution, and a second render of the same face is entitled to the same answer.
      GoogleFontFiles.recordAxesDropped(key, "'wght' 650")
      GoogleFontFiles.recordAxesDropped(key, "'wght' 750")
      assertEquals(
        "'wght' 750",
        GoogleFontFiles.droppedVariationSettings("Google Sans Flex", 400, italic = false),
      )
    } finally {
      GoogleFontFiles.resetForTest()
    }
  }

  @Test
  fun `a recorded face that no longer exists falls back to the cache directory`() {
    val dir = java.nio.file.Files.createTempDirectory("fonts").toFile()
    val onDisk = java.io.File(dir, "lato-400.ttf").apply { writeText("lato") }
    val previous = System.getProperty("composeai.fonts.cacheDir")
    System.setProperty("composeai.fonts.cacheDir", dir.absolutePath)
    try {
      GoogleFontFiles.resetForTest()
      GoogleFontFiles.record(
        GoogleFontKey("Lato", 400, italic = false),
        java.io.File(dir, "gone.ttf"),
      )
      assertEquals(onDisk, GoogleFontFiles.cached("Lato", 400, italic = false))
    } finally {
      GoogleFontFiles.resetForTest()
      if (previous == null) System.clearProperty("composeai.fonts.cacheDir")
      else System.setProperty("composeai.fonts.cacheDir", previous)
    }
  }

  @Test
  fun `the Glimmer title role needs the variable file`() {
    // Verbatim `GoogleSansFlexTypographyDefaults.TitleLargeVariationSettings`, in the
    // sorted-by-axis order Compose puts on the wire.
    val settings = "'GRAD' 0,'ROND' 100.0,'opsz' 9.0,'slnt' 0.0,'wdth' 100.0,'wght' 750"
    assertTrue(
      requiresVariableFace(settings, GoogleFontKey("Google Sans Flex", 400, italic = false))
    )
  }
}
