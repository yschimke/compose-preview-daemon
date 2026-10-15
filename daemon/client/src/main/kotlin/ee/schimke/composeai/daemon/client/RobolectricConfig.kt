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
   * The consumer's own class-name prefixes whose `System.currentTimeMillis()` reads the pinned
   * preview clock (compose-ai-tools#5945). Empty — the default — writes exactly the files this
   * class wrote before it existed. Set through [withClockPackages].
   */
  public var clockPackages: List<String> = emptyList()
    private set

  /**
   * A copy of this config that pins the wall clock the consumer's own code reads: the composable
   * lane's and app-tour lane's files gain `instrumentedPackages=<packages>` and register
   * `ShadowConsumerClock` (whose `@Implements` target, `ShadowSystem`, Robolectric then instruments
   * by itself), and [systemProperties] names the same list for the render JVM, where the shadow
   * reads it to tell the consumer's calls from the framework's. Both halves are needed: the shadow
   * can only intercept calls from classes Robolectric rewrote.
   *
   * [packages] are class-name prefixes matched with `startsWith`, exactly as Robolectric matches
   * `instrumentedPackages` — the Gradle module namespace or a Bazel Android target's `packageName`,
   * ideally with a trailing `.`. Blank entries are dropped, and so is any prefix that overlaps the
   * JDK, Kotlin, Robolectric, the Android framework or this renderer's own packages (mirroring the
   * renderer's `ConsumerClock`), because instrumenting those is unsound.
   */
  public fun withClockPackages(packages: List<String>): RobolectricConfig =
    RobolectricConfig(sdkLevel, useConsumerApplication).also {
      it.clockPackages =
        packages
          .map { p -> p.trim() }
          .filter { p ->
            p.isNotEmpty() && PROTECTED_ROOTS.none { r -> r.startsWith(p) || p.startsWith(r) }
          }
          .distinct()
    }

  /**
   * System properties the render JVM needs alongside these files — `composeai.render.clockPackages`
   * when [clockPackages] is set, otherwise nothing. Merge into the child's `-D`s.
   */
  public fun systemProperties(): Map<String, String> =
    if (clockPackages.isEmpty()) emptyMap()
    else mapOf(CLOCK_PACKAGES_PROPERTY to clockPackages.joinToString(","))

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
    appendClockAndShadows()
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
    appendClockAndShadows()
  }

  /** `shadows=`, plus the consumer-clock half when [clockPackages] is set. */
  private fun StringBuilder.appendClockAndShadows() {
    if (clockPackages.isEmpty()) {
      append("shadows=$FONT_SHADOW")
      return
    }
    appendLine("instrumentedPackages=${clockPackages.joinToString(",")}")
    append("shadows=$FONT_SHADOW,$CONSUMER_CLOCK_SHADOW")
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

    /**
     * The render-JVM system property naming the consumer's class-name prefixes; the renderer's
     * `ConsumerClock.PROPERTY`. Comma-separated.
     */
    public const val CLOCK_PACKAGES_PROPERTY: String = "composeai.render.clockPackages"

    private const val FONT_SHADOW: String =
      "ee.schimke.composeai.renderer.ShadowFontsContractCompat"

    private const val CONSUMER_CLOCK_SHADOW: String =
      "ee.schimke.composeai.renderer.ShadowConsumerClock"

    /** Mirrors the renderer's `ConsumerClock` protected roots; see [withClockPackages]. */
    private val PROTECTED_ROOTS: List<String> =
      listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "kotlin.",
        "kotlinx.",
        "org.junit.",
        "org.robolectric.",
        "android.",
        "com.android.internal.",
        "dalvik.",
        "libcore.",
        "ee.schimke.composeai.renderer.",
        "ee.schimke.composeai.daemon.",
      )

    internal val RENDERER_PACKAGE_PATH: String = RENDERER_PACKAGE.replace('.', '/')
    internal val APP_TOUR_PACKAGE_PATH: String = APP_TOUR_PACKAGE.replace('.', '/')
  }
}
