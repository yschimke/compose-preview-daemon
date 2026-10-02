package ee.schimke.composeai.renderer

import java.awt.EventQueue
import java.util.concurrent.CountDownLatch

/**
 * Runs Compose Desktop scene work on the AWT event dispatch thread — the one thread Compose itself
 * also uses for an [androidx.compose.ui.ImageComposeScene].
 *
 * **Why.** Compose Multiplatform's `RectManager` (the per-owner index of every layout node's
 * bounds) debounces its `dispatchCallbacks()` through `postDelayed`, and on desktop that posts to
 * skiko's `MainUIDispatcher` — the EDT — whatever thread owns the scene (the `TODO CMP-7153` in
 * `Actuals.skiko.kt`, tracked upstream as CMP-10678). `dispatchCallbacks()` runs
 * `RectList.defragment()`, which rewrites and swaps the list's backing arrays. A scene driven from
 * any other thread therefore has the EDT defragmenting its `RectList` ~16 ms after a layout change
 * starts, while the driving thread may still be inserting and moving nodes in it. An insert that
 * lands mid-defragment is dropped, and the next lookup of that node fails with
 * `IllegalArgumentException: LayoutNode N not found in RectList`. It is timing-dependent — a layout
 * pass has to outlast the 16 ms debounce and remove nodes — so it surfaces as a rare,
 * unreproducible crash on a loaded CI runner. On Compose 1.12 a churning tree hits it within a
 * couple of frames (`DesktopUiThreadRectListRaceTest`).
 *
 * Driving the scene on the EDT makes that delayed dispatch an ordinary later event on the same
 * thread, which is how a `ComposeWindow` already works, and matches Compose's own desktop test
 * harness: `runComposeUiTest`'s `runOnUiThread` is `SwingUtilities.invokeAndWait`, so the
 * `runSkikoComposeUiTest` renderers were already half on the EDT.
 *
 * **Contract.** [run] executes [block] on the EDT and returns its result, rethrowing whatever it
 * threw unchanged. Called on the EDT it simply runs [block], so nesting is free and never deadlocks
 * on itself. The caller's context class loader is carried in and back out: the block sees the
 * caller's loader, and any loader it leaves installed (as `RenderEngine.setUp` does for a held
 * scene, restoring it in `tearDown`) is installed on the caller afterwards, so per-thread class
 * loader bookkeeping behaves as if the block had run on the caller. For the same reason the
 * caller's uncaught-exception handler is installed on the EDT for the block — Compose reports a
 * recomposition failure to the composing thread's handler, and a live recording latches its tick
 * failures there — and the EDT's name gains a ` [for <caller>]` suffix, so a stack dump, a log line
 * or a test keyed on the composing thread still says whose scene it was. The wait is
 * uninterruptible — returning early while the block still ran on the EDT would put two threads back
 * on one scene — and an interrupt that arrives meanwhile is re-asserted on return.
 *
 * Everything on the EDT is serialised: two threads each driving their own scene now take turns, one
 * [run] at a time. A [block] must not wait on other EDT work (`runBlocking(Dispatchers.Swing)`,
 * `invokeAndWait` from elsewhere), since the EDT is busy running it.
 */
object DesktopUiThread {

  /** Runs [block] on the AWT event dispatch thread, or inline when already there. */
  @JvmStatic
  fun <T> run(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()

    val caller = Thread.currentThread()
    val callerLoader = caller.contextClassLoader
    val callerHandler = caller.uncaughtExceptionHandler
    val callerName = caller.name
    val done = CountDownLatch(1)
    var outcome: Result<T>? = null
    var loaderAfter: ClassLoader? = callerLoader
    EventQueue.invokeLater {
      val edt = Thread.currentThread()
      val edtLoader = edt.contextClassLoader
      val edtHandler = edt.uncaughtExceptionHandler
      val edtName = edt.name
      edt.contextClassLoader = callerLoader
      edt.uncaughtExceptionHandler = callerHandler
      edt.name = "$edtName [for $callerName]"
      try {
        outcome = runCatching(block)
      } finally {
        loaderAfter = edt.contextClassLoader
        edt.contextClassLoader = edtLoader
        edt.uncaughtExceptionHandler = edtHandler
        edt.name = edtName
        done.countDown()
      }
    }

    var interrupted = false
    while (true) {
      try {
        done.await()
        break
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) caller.interrupt()
    // The latch's countDown/await pair publishes `outcome` and `loaderAfter` to this thread.
    caller.contextClassLoader = loaderAfter
    return outcome!!.getOrThrow()
  }
}
