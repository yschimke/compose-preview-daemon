package ee.schimke.composeai.renderer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression gate for `IllegalArgumentException: LayoutNode N not found in RectList` — the
 * intermittent desktop render crash compose-ui-builder hit on `UiBuilderEditorChromePreview`.
 *
 * Compose Desktop debounces `RectManager.dispatchCallbacks()` onto the EDT whichever thread drives
 * the scene, and that dispatch defragments the scene's `RectList`. A scene laid out off the EDT can
 * have its list compacted under it mid-placement, dropping a node it just inserted; see
 * [DesktopUiThread]. [RectListChurnPreview] makes the conditions certain rather than rare: a large
 * list, nodes removed every frame, and a placement pass that outlasts the 16 ms debounce.
 *
 * Measured before the fix, on Compose 1.12.0 / 1.12.0-rc01 (`forwardComposeSystemThemeTest` runs
 * this class there too): driving that tree off the EDT throws within one or two frames. On 1.11.1
 * the same race corrupts the `RectList` (entries go missing between frames) without that version
 * tripping its precondition, so this class passes there either way and is only a real gate on the
 * forward runtime. Both tests are bounded by [CHURN_FRAMES] frames, and on the EDT nothing races,
 * so neither can flake in the passing direction.
 */
class DesktopUiThreadRectListRaceTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Before
  fun requireSkikoNatives() {
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  /**
   * The production entry point — the one the Gradle plugin's renderer and its worker pool call —
   * renders a churning tree across a dozen settle frames without the race.
   */
  @Test
  fun aChurningPreviewRendersThroughMainWithoutLosingRectListEntries() {
    val out = File(tempFolder.newFolder("churn"), "churn.png")
    val args = Array(49) { "" }
    args[0] = "ee.schimke.composeai.renderer.RectListRaceFixturesKt"
    args[1] = "RectListChurnPreview"
    args[2] = "256"
    args[3] = "256"
    args[4] = "1.0"
    args[5] = "false"
    args[6] = "0"
    args[7] = out.absolutePath
    // `@SettledPreview` auto: advance frames until quiescent, so every churn frame is laid out.
    args[47] = "0"
    args[48] = "2000"

    main(args)

    val sidecar = File(out.parentFile, "${out.name}.error.json")
    assertFalse(
      "render threw: ${sidecar.takeIf { it.exists() }?.readText()}",
      sidecar.exists(),
    )
    assertTrue("render wrote no PNG", out.isFile)
  }

  /**
   * The daemon's shape: a long-lived scene whose every frame is a separate [DesktopUiThread.run]
   * from a non-EDT thread, as `DesktopHost`'s render loop, an interactive session's executor and a
   * live recording's tick loop do. Between hops the EDT is free to run the debounced dispatch,
   * which is fine — it is the scene's own thread — and must never overlap a frame.
   */
  @Test
  fun aSceneDrivenFrameByFrameThroughDesktopUiThreadSurvivesChurn() {
    assertFalse(SwingUtilities.isEventDispatchThread())
    var frame by mutableIntStateOf(0)
    val scene = DesktopUiThread.run { ImageComposeScene(256, 256, Density(1f)) }
    try {
      DesktopUiThread.run { scene.setContent { RectListChurn(frame) } }
      repeat(CHURN_FRAMES) { i ->
        frame = i + 1
        DesktopUiThread.run { scene.render((i + 1) * 16_000_000L).close() }
      }
    } finally {
      DesktopUiThread.run { scene.close() }
    }
  }

  @Test
  fun runExecutesOnTheEdtAndInlineWhenAlreadyThere() {
    val outer = DesktopUiThread.run {
      val nested = DesktopUiThread.run { Thread.currentThread() }
      assertSame("a nested run must stay on the EDT inline", Thread.currentThread(), nested)
      Thread.currentThread()
    }
    assertTrue(outer.name, outer.name.startsWith("AWT-EventQueue"))
  }

  @Test
  fun runLendsTheEdtTheCallersHandlerAndName() {
    val caller = Thread.currentThread()
    val original = caller.uncaughtExceptionHandler
    val handler = Thread.UncaughtExceptionHandler { _, _ -> }
    try {
      caller.uncaughtExceptionHandler = handler
      val (seenHandler, seenName) =
        DesktopUiThread.run {
          Thread.currentThread().uncaughtExceptionHandler to Thread.currentThread().name
        }
      assertSame(handler, seenHandler)
      assertTrue(seenName, seenName.endsWith("[for ${caller.name}]"))
      val after = DesktopUiThread.run { Thread.currentThread() }
      assertFalse("the EDT must get its own handler back", edtHandler(after) === handler)
    } finally {
      caller.uncaughtExceptionHandler = original
    }
  }

  private fun edtHandler(edt: Thread): Thread.UncaughtExceptionHandler? {
    val seen = AtomicReference<Thread.UncaughtExceptionHandler?>()
    SwingUtilities.invokeAndWait { seen.set(edt.uncaughtExceptionHandler) }
    return seen.get()
  }

  @Test
  fun runRethrowsTheBlocksThrowableUnchanged() {
    val boom = IllegalStateException("boom")
    val thrown = runCatching { DesktopUiThread.run<Unit> { throw boom } }.exceptionOrNull()
    assertSame(boom, thrown)
  }

  @Test
  fun runCarriesTheContextClassLoaderInAndBackOut() {
    val caller = Thread.currentThread()
    val original = caller.contextClassLoader
    val callerLoader = URLClassLoader(emptyArray(), original)
    val installedByBlock = URLClassLoader(emptyArray(), original)
    val seen = AtomicReference<ClassLoader?>()
    val edtLoaderBefore = edtContextClassLoader()
    try {
      caller.contextClassLoader = callerLoader
      DesktopUiThread.run {
        seen.set(Thread.currentThread().contextClassLoader)
        // What `RenderEngine.setUp` does for a held scene: leave a loader installed for later.
        Thread.currentThread().contextClassLoader = installedByBlock
      }
      assertSame("the block must see the caller's loader", callerLoader, seen.get())
      assertSame(
        "a loader the block installs lands on the caller",
        installedByBlock,
        caller.contextClassLoader,
      )
      assertSame(
        "the EDT must be back on its own loader once the block returns",
        edtLoaderBefore,
        edtContextClassLoader(),
      )
    } finally {
      caller.contextClassLoader = original
    }
  }

  private fun edtContextClassLoader(): ClassLoader? {
    val loader = AtomicReference<ClassLoader?>()
    SwingUtilities.invokeAndWait { loader.set(Thread.currentThread().contextClassLoader) }
    return loader.get()
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
