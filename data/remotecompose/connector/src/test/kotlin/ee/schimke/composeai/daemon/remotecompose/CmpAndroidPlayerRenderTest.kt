package ee.schimke.composeai.daemon.remotecompose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `cmp-android` draws through the CMP player (`rc-player-compose`), and draws the document at the
 * size it was captured at.
 *
 * The fixture is the remote-m3 catalog's 192dp horizontal page indicator, captured at density 2.0.
 * The AndroidX embedded player draws its rail 84px wide on this 384px display; the CMP player must
 * agree, which also pins the host-density fix that 2.0.4 carries (an older CMP player drew it 42px
 * wide on its first frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w192dp-h192dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CmpAndroidPlayerRenderTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `cmp-android is the CMP player and draws the rail at the captured size`() {
    val backend = checkNotNull(RemoteComposePlayers.forId("cmp-android"))
    assertEquals("cmp-android", backend.id)
    assertNotEquals(
      "cmp-android must not be the AndroidX embedded player",
      RemoteComposePlayers.forId("androidx-embedded")?.javaClass,
      backend.javaClass,
    )

    val document = RemoteComposeDocumentSource(fixture())
    rule.setContent {
      Box(Modifier.fillMaxSize()) { backend.Play(document, emptyMap(), Modifier.fillMaxSize()) }
    }
    rule.waitForIdle()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    assertEquals(384, bitmap.width)

    // The capture includes the window's own background, so ink is what differs from a corner.
    val background = bitmap.getPixel(0, 0)
    var minX = bitmap.width
    var maxX = -1
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        if (bitmap.getPixel(x, y) != background) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
        }
      }
    }
    val width = maxX - minX + 1
    assertTrue("the CMP player drew nothing", maxX >= minX)
    assertEquals("rail width in px", 84f, width.toFloat(), 3f)
  }

  private fun fixture(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/rc-fixtures/pageindicator-horizontal-192dp.rc")) {
        "missing fixture"
      }
      .use { it.readBytes() }
}
