package ee.schimke.composeai.daemon

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.use
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Material 3 wrap in [WallpaperOverrideExtension] is skipped, not fatal, when
 * `androidx.compose.material3` is absent (a Wear-only app, #400).
 */
class WallpaperMaterial3GateTest {

  private val override = WallpaperOverride(seedColor = "#00D775", isDark = false)

  private fun primaryUnder(extension: WallpaperOverrideExtension): Int {
    var primary = 0
    var ranContent = false
    ImageComposeScene(width = 10, height = 10).use { scene ->
      scene.setContent {
        extension.AroundComposable {
          ranContent = true
          primary = MaterialTheme.colorScheme.primary.toArgb()
        }
      }
      scene.render()
    }
    assertTrue("content must always compose", ranContent)
    return primary
  }

  @Test
  fun predicate_reflects_whether_material3_resolves() {
    assertTrue(isMaterial3Loadable(WallpaperOverrideExtension::class.java.classLoader))
    // A loader with only the bootstrap classpath stands in for a Wear-only app's.
    assertFalse(isMaterial3Loadable(object : ClassLoader(null) {}))
  }

  @Test
  fun wraps_in_the_seeded_scheme_when_material3_is_present() {
    val seeded = WallpaperColorScheme.from(Color(0xFF00D775), isDark = false).primary.toArgb()
    assertEquals(seeded, primaryUnder(WallpaperOverrideExtension(override) { true }))
    assertEquals(seeded, primaryUnder(WallpaperOverrideExtension(override)))
  }

  @Test
  fun skips_only_the_material3_wrap_when_material3_is_absent() {
    val seeded = WallpaperColorScheme.from(Color(0xFF00D775), isDark = false).primary.toArgb()
    val primary = primaryUnder(WallpaperOverrideExtension(override) { false })
    assertNotEquals(seeded, primary)
  }
}
