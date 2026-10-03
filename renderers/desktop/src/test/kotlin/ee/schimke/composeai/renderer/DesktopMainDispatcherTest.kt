package ee.schimke.composeai.renderer

import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop renderer's runtime carries a real `Dispatchers.Main`, bound to the AWT event dispatch
 * thread the scene is driven on ([DesktopUiThread]).
 *
 * Without one, `Dispatchers.Main` resolves to `kotlinx-coroutines-test`'s `TestMainDispatcher`
 * (Compose UI Test brings it onto the classpath) with no platform dispatcher to delegate to, and
 * the first access throws `IllegalStateException: Module with the Main dispatcher is missing`. A
 * Compose Multiplatform app supplies Main from `kotlinx-coroutines-swing` in its *application*
 * module, so the library modules a catalog renders never carry it: every route of tunjid/heron
 * rendered as a single flat surface colour, because its navigation library takes
 * `Dispatchers.Main.immediate` while composing the scaffold.
 */
class DesktopMainDispatcherTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Before
  fun requireSkikoNatives() {
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @Test
  fun mainDispatchesOntoTheEventDispatchThread() {
    assertFalse(SwingUtilities.isEventDispatchThread())
    val onEdt = runBlocking {
      withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() }
    }
    assertTrue("Dispatchers.Main must run its work on the AWT event dispatch thread", onEdt)
  }

  @Test
  fun mainImmediateRunsInlineOnTheRenderThread() {
    val ranInline = CompletableFuture<Boolean>()
    DesktopUiThread.run {
      ranInline.complete(!Dispatchers.Main.immediate.isDispatchNeeded(Dispatchers.Main.immediate))
    }
    assertTrue(
      "Main.immediate must not re-dispatch from the thread the scene composes on",
      ranInline.get(5, TimeUnit.SECONDS),
    )
  }

  @Test
  fun aPreviewTakingMainImmediateWhileComposingRendersItsContent() {
    val out = File(tempFolder.newFolder("main"), "main.png")
    val args = Array(49) { "" }
    args[0] = "ee.schimke.composeai.renderer.MainDispatcherFixturesKt"
    args[1] = "MainImmediateScopePreview"
    args[2] = "64"
    args[3] = "64"
    args[4] = "1.0"
    args[5] = "false"
    args[6] = "0"
    args[7] = out.absolutePath

    main(args)

    val sidecar = File(out.parentFile, "${out.name}.error.json")
    assertFalse(
      "render threw: ${sidecar.takeIf { it.exists() }?.readText()}",
      sidecar.exists(),
    )
    assertTrue("render wrote no PNG", out.isFile)
    val image = ImageIO.read(out)
    assertEquals(
      "the preview's own fill must reach the frame",
      MAIN_SCOPE_FILL.toArgb(),
      image.getRGB(image.width / 2, image.height / 2),
    )
  }

  private companion object {
    var skikoLoadFailure: String? = null

    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
