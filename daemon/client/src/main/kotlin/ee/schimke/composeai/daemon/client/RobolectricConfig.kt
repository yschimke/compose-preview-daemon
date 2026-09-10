package ee.schimke.composeai.daemon.client

import java.io.File

/**
 * The `robolectric.properties` files the Android backend needs on its classpath.
 *
 * ### Why the daemon writes these and not the caller
 *
 * Robolectric resolves `robolectric.properties` **per test class package**, merging a parent
 * package's file into a child's. The classes that render are in this repository, so the paths these
 * files go to are this repository's package names — and until now they were spelled in
 * compose-ai-tools, which hardcoded `ee/schimke/composeai/renderer` and `ee/schimke/composeai/
 * apptour` into a module in another repository on another release train. Rename a package here and
 * nothing there fails to compile; the config simply stops being found, and Robolectric falls back
 * to defaults that render different pixels.
 *
 * @property sdkLevel the API level to pin. Already clamped by [DaemonBackend.Android].
 * @property useConsumerApplication when false (the default) the composable lane pins the stub
 *   `application=android.app.Application`, so the consumer's own `Application.onCreate()` never
 *   runs. Preview rendering must not execute app bootstrap — Firebase, splash screens, dependency
 *   graphs. Set true only for an Application known to be preview-safe.
 */
public class RobolectricConfig(
  public val sdkLevel: Int,
  public val useConsumerApplication: Boolean = false,
) {

  /**
   * The composable lane's file, for [RENDERER_PACKAGE].
   *
   * `shadows=` registers the GoogleFont shadow that makes a downloadable `Font(GoogleFont(...))`
   * resolvable at all; without it such text silently falls back to the platform face.
   */
  public fun composableLaneBody(): String = buildString {
    appendLine("sdk=$sdkLevel")
    appendLine("graphicsMode=NATIVE")
    if (!useConsumerApplication) appendLine("application=android.app.Application")
    append("shadows=$FONT_SHADOW")
  }

  /**
   * The app-tour lane's file, for [APP_TOUR_PACKAGE] — [composableLaneBody] **without** the stub
   * `application=` line.
   *
   * `kind=ACTIVITY` / `kind=APP_TOUR` previews render from a test class in a sibling package,
   * because Robolectric resolves the Application per test *class*. An Activity **is** the app:
   * launched against the stub, every Hilt / Koin / `AppComponentFactory` activity fails on contact.
   *
   * The line is absent rather than pinned to something else, so that a lane which later packs a
   * merged manifest starts honouring it with no further change here.
   */
  public fun appTourLaneBody(): String = buildString {
    appendLine("sdk=$sdkLevel")
    appendLine("graphicsMode=NATIVE")
    append("shadows=$FONT_SHADOW")
  }

  /**
   * Materialise both files under [root] and return it, for the caller to prepend to the child's
   * classpath — prepend, so this config wins over any copy baked into the shipped renderer jar.
   */
  public fun writeTo(root: File): File {
    File(root, RENDERER_PACKAGE_PATH)
      .apply { mkdirs() }
      .let { File(it, FILE_NAME).writeText(composableLaneBody() + "\n") }
    File(root, APP_TOUR_PACKAGE_PATH)
      .apply { mkdirs() }
      .let { File(it, FILE_NAME).writeText(appTourLaneBody() + "\n") }
    return root
  }

  public companion object {
    public const val FILE_NAME: String = "robolectric.properties"

    /** The renderer test's package — the composable lane. */
    public const val RENDERER_PACKAGE: String = "ee.schimke.composeai.renderer"

    /**
     * The app-tour lane's package. A **sibling** of [RENDERER_PACKAGE], never a child: Robolectric
     * merges a parent package's file into a child's, so nesting it would inherit the stub
     * `application=` line the composable lane pins — which is the one thing this lane must not
     * have.
     */
    public const val APP_TOUR_PACKAGE: String = "ee.schimke.composeai.apptour"

    private const val FONT_SHADOW: String =
      "ee.schimke.composeai.renderer.ShadowFontsContractCompat"

    internal val RENDERER_PACKAGE_PATH: String = RENDERER_PACKAGE.replace('.', '/')
    internal val APP_TOUR_PACKAGE_PATH: String = APP_TOUR_PACKAGE.replace('.', '/')
  }
}
