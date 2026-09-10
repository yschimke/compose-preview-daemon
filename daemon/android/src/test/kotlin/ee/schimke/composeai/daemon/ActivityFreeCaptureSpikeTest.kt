package ee.schimke.composeai.daemon

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fetchImage
import ee.schimke.composeai.daemon.pool.SandboxProcessPool
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyExtractor
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.notification.RunNotifier
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in B4 experiment. The full engine checks artifact parity; the matched Activity/PhoneWindow
 * paths isolate hosting cost while sharing composition, synchronization, capture and extraction.
 * Each mode gets a fresh SDK 35 native-runtime JVM. No published renderer behavior changes.
 */
class ActivityFreeCaptureSpikeTest {
  @Test
  fun compareWindowCaptureWithProductionEngine() {
    assumeTrue(System.getenv("COMPOSEAI_ACTIVITY_FREE_SPIKE") == "true")
    val inputMode = System.getenv("COMPOSEAI_ACTIVITY_FREE_INPUT") == "true"
    val root =
      File(if (inputMode) "build/activity-free-input-spike" else "build/activity-free-spike")
        .absoluteFile
        .apply { mkdirs() }
    val trials = (System.getenv("COMPOSEAI_ACTIVITY_FREE_TRIALS") ?: "1").toInt()
    require(trials in 1..10)
    for (trial in 0 until trials) {
      val trialDir = root.resolve("trial-$trial").apply { mkdirs() }
      val modes =
        if (inputMode) listOf("activity", "window") else listOf("engine", "activity", "window")
      // Rotate process order so the candidate isn't always last on an already-warm host.
      for (mode in modes.drop(trial % modes.size) + modes.take(trial % modes.size)) {
        val output =
          trialDir.resolve(mode).apply {
            deleteRecursively()
            mkdirs()
          }
        val command = SandboxProcessPool.spareWorkerCommandForTest().dropLast(1).toMutableList()
        command += ActivityFreeCaptureSpikeMain::class.java.name
        command += listOf(mode, output.absolutePath)
        val log = trialDir.resolve("$mode.log")
        val child = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        try {
          assertTrue("$mode timed out: $log", child.waitFor(180, TimeUnit.SECONDS))
          assertEquals(log.readText(), 0, child.exitValue())
        } finally {
          if (child.isAlive) {
            child.destroyForcibly()
            child.waitFor(10, TimeUnit.SECONDS)
          }
        }
        log
          .readLines()
          .filter { it.startsWith("[measure]") }
          .forEach { println("trial=$trial $it") }
      }
      for (cycle in 0..2) for (name in ActivityFreeCaptureSpikeMain.fixtures) {
        val outputName = "$cycle-$name"
        val referenceMode = if (inputMode) "activity" else "engine"
        val expected = trialDir.resolve("$referenceMode/renders/$outputName.png")
        for (mode in listOf("activity", "window")) {
          val actual = trialDir.resolve("$mode/renders/$outputName.png")
          assertArrayEquals(
            "PNG parity: trial=$trial $mode $outputName",
            expected.readBytes(),
            actual.readBytes(),
          )
          assertEquals(
            "hierarchy parity: $mode $outputName",
            trialDir.resolve("$referenceMode/data/$outputName/uia-hierarchy.json").readText(),
            trialDir.resolve("$mode/data/$outputName/uia-hierarchy.json").readText(),
          )
        }
        assertEquals(
          "full hierarchy parity: $outputName",
          trialDir.resolve("activity/renders/$outputName-semantics.json").readText(),
          trialDir.resolve("window/renders/$outputName-semantics.json").readText(),
        )
      }
    }
  }
}

object ActivityFreeCaptureSpikeMain {
  val inputMode = System.getenv("COMPOSEAI_ACTIVITY_FREE_INPUT") == "true"
  val fixtures =
    if (inputMode) listOf("ClickToggleSquare", "ClickableToggleSquare", "EditableTextFieldSquare")
    else
      listOf(
        "RedSquare",
        "MaterialButtonInteractionState",
        "SerifTextPreview",
        "DialogWindowSurface",
      ) +
        if (System.getenv("COMPOSEAI_ACTIVITY_FREE_BROAD") == "true") {
          listOf(
            "OpaqueImageSquare",
            "GradientBackgroundCard",
            "RadialGradientBackgroundCard",
            "EmojiAndAnnotatedText",
            "GraphicsLayerAndWideVector",
            "IconButtonRowInputBar",
            "LazyColumnListPreview",
            "EditableTextFieldSquare",
            "GenericOutlineShapeSquare",
          )
        } else emptyList()

  @JvmStatic
  fun main(args: Array<String>) {
    try {
      require(args.size == 2 && args[0] in listOf("engine", "activity", "window"))
      System.setProperty("activityFreeSpike.mode", args[0])
      System.setProperty(RenderEngine.OUTPUT_DIR_PROP, File(args[1], "renders").absolutePath)
      System.setProperty("roborazzi.test.record", "true")
      val result = JUnitCore.runClasses(Fixture::class.java)
      result.failures.forEach { it.exception.printStackTrace() }
      check(result.wasSuccessful())
    } catch (t: Throwable) {
      t.printStackTrace()
      exitProcess(1)
    }
    exitProcess(0)
  }

  class Runner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    override fun run(notifier: RunNotifier) {
      if (System.getenv("COMPOSEAI_ACTIVITY_FREE_SPIKE") == "true") super.run(notifier)
      else notifier.fireTestIgnored(description)
    }
  }

  @RunWith(Runner::class)
  @Config(sdk = [35])
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  class Fixture {
    @Test
    fun capture() {
      val mode = System.getProperty("activityFreeSpike.mode")
      val runtime = ManagementFactory.getRuntimeMXBean()
      val os =
        ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
      val classes = ManagementFactory.getClassLoadingMXBean()
      println(
        "[measure] mode=$mode readyMs=${runtime.uptime} cpuMs=${os.processCpuTime / 1_000_000} loadedClasses=${classes.totalLoadedClassCount}"
      )
      for (cycle in 0..2) for ((index, name) in fixtures.withIndex()) {
        val outputName = "$cycle-$name"
        val start = System.nanoTime()
        if (mode == "engine") {
          RenderEngine()
            .render(
              RenderSpec(
                className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                functionName = name,
                widthPx = 320,
                heightPx = 320,
                density = 1f,
                outputBaseName = outputName,
              ),
              (cycle * fixtures.size + index).toLong(),
            )
        } else {
          captureWindow(name, outputName, mode == "activity")
        }
        println(
          "[measure] mode=$mode cycle=$cycle fixture=$name renderMs=${(System.nanoTime() - start) / 1_000_000} uptimeMs=${runtime.uptime} cpuMs=${os.processCpuTime / 1_000_000} loadedClasses=${classes.totalLoadedClassCount}"
        )
      }
    }
  }

  private class Owners : SavedStateRegistryOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this)
    override val viewModelStore = ViewModelStore()
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
      get() = saved.savedStateRegistry

    init {
      saved.performAttach()
      saved.performRestore(null)
    }
  }

  @OptIn(ExperimentalRoborazziApi::class)
  @Suppress("DEPRECATION")
  private fun captureWindow(name: String, outputName: String, useActivity: Boolean) {
    var phaseStart = System.nanoTime()
    fun phase(label: String) {
      val now = System.nanoTime()
      println(
        "[phase] mode=${if (useActivity) "activity" else "window"} output=$outputName fixture=$name phase=$label ms=${(now - phaseStart) / 1_000_000}"
      )
      phaseStart = now
    }
    RuntimeEnvironment.setQualifiers("+w320dp-h320dp-port-160dpi")
    RuntimeEnvironment.setFontScale(1f)
    val app = RuntimeEnvironment.getApplication()
    if (useActivity) {
      org.robolectric.Shadows.shadowOf(app.packageManager)
        .addActivityIfNotPresent(
          android.content.ComponentName(app.packageName, ComponentActivity::class.java.name)
        )
    }
    val activityRule = if (useActivity) createAndroidComposeRule<ComponentActivity>() else null
    val rule = activityRule ?: createEmptyComposeRule()
    fun advanceInputClocks(totalMs: Long) {
      val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
      var remaining = totalMs
      while (remaining > 0) {
        val step = minOf(remaining, 16L)
        rule.mainClock.advanceTimeBy(step)
        looper.idleFor(java.time.Duration.ofMillis(step))
        remaining -= step
      }
    }
    phase("rule-created")
    val statement =
      object : Statement() {
        override fun evaluate() {
          phase("rule-enter")
          rule.mainClock.autoAdvance = false
          var cleanup: () -> Unit = {}
          lateinit var view: ComposeView
          lateinit var window: Window
          lateinit var frame: FrameLayout
          lateinit var owners: Owners
          lateinit var manager: WindowManager
          try {
            if (activityRule != null) {
              rule.runOnUiThread {
                window = activityRule.activity.window
                window.decorView.setBackgroundColor(android.graphics.Color.WHITE)
                view = ComposeView(activityRule.activity)
                cleanup = { view.disposeComposition() }
                activityRule.activity.setContentView(view)
                view.setContent {
                  CompositionLocalProvider(LocalInspectionMode provides !inputMode) {
                    content(name)
                  }
                }
              }
            } else
              rule.runOnUiThread {
                val context =
                  ContextThemeWrapper(
                    RuntimeEnvironment.getApplication(),
                    android.R.style.Theme_Material_Light_NoActionBar,
                  )
                manager = context.getSystemService(WindowManager::class.java)
                // A bare FrameLayout falls back to View.draw. A real decor supplies the Window that
                // Roborazzi's View bitmap fetcher needs for PixelCopy, without an Activity context.
                // Hidden framework construction is deliberate here: this is an SDK-pinned spike.
                window =
                  Class.forName("com.android.internal.policy.PhoneWindow")
                    .getConstructor(Context::class.java)
                    .newInstance(context) as Window
                window.setWindowManager(manager, null, "activity-free-spike")
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
                owners = Owners()
                cleanup = {
                  owners.lifecycle.currentState = Lifecycle.State.DESTROYED
                  owners.viewModelStore.clear()
                }
                frame = FrameLayout(context)
                frame.setViewTreeLifecycleOwner(owners)
                frame.setViewTreeSavedStateRegistryOwner(owners)
                frame.setViewTreeViewModelStoreOwner(owners)
                view = ComposeView(context)
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                cleanup = {
                  view.disposeComposition()
                  if (window.decorView.isAttachedToWindow)
                    manager.removeViewImmediate(window.decorView)
                  owners.lifecycle.currentState = Lifecycle.State.DESTROYED
                  owners.viewModelStore.clear()
                }
                owners.lifecycle.currentState = Lifecycle.State.RESUMED
                view.setContent {
                  CompositionLocalProvider(LocalInspectionMode provides !inputMode) {
                    content(name)
                  }
                }
                window.setContentView(frame)
                manager.addView(
                  window.decorView,
                  WindowManager.LayoutParams(
                      320,
                      320,
                      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                      PixelFormat.TRANSLUCENT,
                    )
                    .apply { gravity = Gravity.TOP or Gravity.LEFT },
                )
              }
            phase("attach")
            // Attachment/composition is queued on Robolectric's paused main looper. Without this,
            // waitForIdle waits two seconds in waitForComposeRoots before draining that same queue.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!useActivity) {
              // WindowManager attachment does not deliver the focus event that ActivityScenario
              // supplies. Compose's cursor/input behavior reads window focus independently of
              // the lifecycle and node focus; dispatch through ViewRootImpl to update both.
              rule.runOnUiThread {
                val root =
                  org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                    window.decorView,
                    "getViewRootImpl",
                  )
                org.robolectric.shadow.api.Shadow.extract<
                    org.robolectric.shadows.ShadowViewRootImpl
                  >(
                    root
                  )
                  .callWindowFocusChanged(true)
              }
              org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            rule.runOnUiThread { check(window.decorView.hasWindowFocus()) }
            phase("drain-attach")
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            if (inputMode) {
              if (name == "EditableTextFieldSquare") {
                rule.onNode(hasSetTextAction()).performTextInput("x")
              } else {
                rule.onRoot().performTouchInput { down(center) }
                advanceInputClocks(16)
                rule.waitForIdle()
                rule.onRoot().performTouchInput { move() }
                advanceInputClocks(84)
                rule.waitForIdle()
                rule.onRoot().performTouchInput { up() }
                rule.waitForIdle()
              }
              advanceInputClocks(500)
              rule.waitForIdle()
            }
            phase("settle")
            val interactions = rule.onAllNodes(isRoot(), useUnmergedTree = true)
            val roots = interactions.fetchSemanticsNodes()
            val index =
              roots.indexOfFirst { it.shownDialogWindow() != null }.takeIf { it >= 0 } ?: 0
            phase("roots")
            val renders =
              File(requireNotNull(System.getProperty(RenderEngine.OUTPUT_DIR_PROP))).apply {
                mkdirs()
              }
            // Compose capture requires an Activity context for ordinary roots; the View overload
            // invokes Espresso, which also requires a resumed Activity. Fetch the bitmap directly
            // and use only Roborazzi's bitmap writer. Both matched hosts take this same path.
            if (roots[index].shownDialogWindow() != null) {
              // This fixed-size dialog fixture has no gutter. The engine crops the dialog and
              // resizes it to the declared frame; fetching its own root avoids the screen crop.
              val bitmap =
                requireNotNull(
                  roots[index].fetchImage(RoborazziOptions.RecordOptions(applyDeviceCrop = false))
                )
              android.graphics.Bitmap.createScaledBitmap(bitmap, 320, 320, true)
                .captureRoboImage(file = renders.resolve("$outputName.png"))
            } else {
              requireNotNull(
                  (roots[index].root as ViewRootForTest)
                    .view
                    .fetchImage(RoborazziOptions.RecordOptions(applyDeviceCrop = false))
                )
                .captureRoboImage(file = renders.resolve("$outputName.png"))
            }
            if (inputMode) {
              val image = javax.imageio.ImageIO.read(renders.resolve("$outputName.png"))
              var green = 0
              for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (
                  kotlin.math.abs(((pixel shr 16) and 255) - 0x66) <= 8 &&
                    kotlin.math.abs(((pixel shr 8) and 255) - 0xbb) <= 8 &&
                    kotlin.math.abs((pixel and 255) - 0x6a) <= 8
                )
                  green++
              }
              check(green.toDouble() / (image.width * image.height) >= 0.95) {
                "Input did not change $name to its expected green state: $green pixels"
              }
            }
            phase("capture")
            val root = interactions[index].fetchSemanticsNode()
            UiAutomatorDataProducer.writeArtifacts(
              requireNotNull(renders.parentFile).resolve("data"),
              outputName,
              UiAutomatorHierarchyExtractor.extract(root),
            )
            renders
              .resolve("$outputName-semantics.json")
              .writeText(
                Json.encodeToString(
                  UiAutomatorHierarchyExtractor.extract(root, includeNonActionable = true)
                )
              )
            phase("extract")
          } finally {
            rule.runOnUiThread {
              cleanup()
              phase("cleanup")
            }
          }
        }
      }
    rule.apply(statement, Description.createTestDescription(Fixture::class.java, name)).evaluate()
    phase("rule-exit")
  }

  @Composable
  private fun content(name: String) {
    when (name) {
      "RedSquare" -> RedSquare()
      "ClickToggleSquare" -> ClickToggleSquare()
      "ClickableToggleSquare" -> ClickableToggleSquare()
      "MaterialButtonInteractionState" -> MaterialButtonInteractionState()
      "SerifTextPreview" -> SerifTextPreview()
      "DialogWindowSurface" -> DialogWindowSurface()
      "OpaqueImageSquare" -> OpaqueImageSquare()
      "GradientBackgroundCard" -> GradientBackgroundCard()
      "RadialGradientBackgroundCard" -> RadialGradientBackgroundCard()
      "EmojiAndAnnotatedText" -> EmojiAndAnnotatedText()
      "GraphicsLayerAndWideVector" -> GraphicsLayerAndWideVector()
      "IconButtonRowInputBar" -> IconButtonRowInputBar()
      "LazyColumnListPreview" -> LazyColumnListPreview()
      "EditableTextFieldSquare" -> EditableTextFieldSquare()
      "GenericOutlineShapeSquare" -> GenericOutlineShapeSquare()
      else -> error(name)
    }
  }
}
