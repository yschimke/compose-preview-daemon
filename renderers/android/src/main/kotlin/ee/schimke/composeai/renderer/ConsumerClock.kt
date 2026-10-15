package ee.schimke.composeai.renderer

/**
 * Which classes count as the *consumer's own code* for [PreviewClock] pinning, and how to recognise
 * a call from one.
 *
 * ## Why this exists
 *
 * [PreviewClock] pins the wall clock a render sees, but only where something can intercept the
 * read. [ShadowWearTimeSource] covers Wear's `TimeText`. A consumer's own time-display component —
 * `SimpleDateFormat("h:mm").format(System.currentTimeMillis())`, an "x minutes ago" label, a date
 * picker defaulting to today — reads the clock from the consumer's own classes, which Robolectric
 * does not instrument, so it painted the host's time and two renders of one commit differed
 * (compose-ai-tools#5945: `8:26` vs `2:18`).
 *
 * ## Configuring it
 *
 * `-Dcomposeai.render.clockPackages=com.example.app,com.example.lib` on the render JVM: a comma (or
 * semicolon) separated list of **class-name prefixes**, matched with `startsWith` exactly as
 * Robolectric's `instrumentedPackages` matches, so a trailing `.` is honoured and recommended. The
 * tools set it to the consumer's application/library package(s) — the Gradle module namespace, or a
 * Bazel Android target's `packageName`. Unset or empty means today's behaviour: nothing is
 * instrumented and the consumer reads the host clock.
 *
 * The same list has to reach the lane's instrumentation config, because a class Robolectric did not
 * rewrite never reaches [ShadowConsumerClock]:
 * - the **daemon** reads this property in `SandboxHoldingRunner` (instrumented packages, the
 *   `ShadowSystem` instrumentation and the shadow) and in its user-class loader, which rewrites the
 *   consumer classes it defines itself (`ConsumerClockRewriter`);
 * - the **one-shot renderer** gets it from `robolectric.properties` (`instrumentedPackages=` plus
 *   `shadows=…ShadowConsumerClock`), written by `RobolectricConfig.withClockPackages` or the Gradle
 *   plugin's `GenerateRobolectricPropertiesTask`, and still needs this property for the caller
 *   check.
 *
 * ## What it cannot reach
 *
 * Exactly what [PreviewClock] cannot: `java.time.*.now()`, `Calendar.getInstance()` and `Date()`
 * read the clock inside the JDK, where nothing is instrumented. Only a consumer class's *own*
 * `System.currentTimeMillis()` call site is redirected.
 */
object ConsumerClock {

  /** System property naming the consumer's class-name prefixes. See the class KDoc. */
  const val PROPERTY: String = "composeai.render.clockPackages"

  /**
   * Prefixes that must never be treated as consumer code, whatever the property says. A prefix that
   * covers any of these (or sits under one) is dropped by [parse]:
   * - the JDK, Kotlin, JUnit and Robolectric — instrumenting them is unsound or impossible;
   * - the Android framework, which Robolectric already instruments and whose `currentTimeMillis()`
   *   keeps reading the emulated clock;
   * - this renderer and the daemon, because [PreviewClock] itself calls
   *   `System.currentTimeMillis()` for `fixedTime=off`, and an instrumented copy of it would be
   *   redirected back into itself.
   */
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

  /** The prefixes [PROPERTY] currently names, minus any [PROTECTED_ROOTS] would reject. */
  @JvmStatic fun packages(): List<String> = parse(System.getProperty(PROPERTY))

  /**
   * Pure parse of [raw]: split on `,` / `;`, trimmed, blanks and duplicates dropped, and every
   * prefix that overlaps a protected root removed (see [PROTECTED_ROOTS]). An empty prefix would
   * match every class, so it is dropped with the rest.
   */
  @JvmStatic
  fun parse(raw: String?): List<String> =
    raw
      .orEmpty()
      .split(',', ';')
      .map { it.trim() }
      .filter { it.isNotEmpty() && !isProtected(it) }
      .distinct()

  /** True when [className] starts with one of [packages] — the same test Robolectric applies. */
  @JvmStatic
  fun isConsumerClass(className: String, packages: List<String>): Boolean = packages.any {
    className.startsWith(it)
  }

  /**
   * Robolectric's rewrite target for an instrumented `System.currentTimeMillis()` /
   * `System.nanoTime()` call — the class [ShadowConsumerClock] shadows. See that class's KDoc.
   */
  const val SHADOW_SYSTEM: String = "org.robolectric.shadows.ShadowSystem"

  /** Frames to inspect before giving up; the caller sits four or five frames down. */
  private const val MAX_FRAMES = 16L

  private val stackWalker: StackWalker = StackWalker.getInstance()

  /**
   * The class that called into [target] — the first frame below the topmost run of [target] frames,
   * skipping `java.lang.invoke` / `jdk.internal` plumbing. `null` when [target] is not on the top
   * of the stack.
   *
   * The default [StackWalker] already hides lambda-form frames, so for an instrumented call site
   * the stack reads: this shadow, `ShadowSystem` (the instrumented stub that dispatched to it),
   * then the class that wrote `System.currentTimeMillis()`.
   */
  internal fun callerOf(target: String): String? = stackWalker.walk { frames ->
    var seenTarget = false
    frames
      .limit(MAX_FRAMES)
      .map { it.className }
      .filter { name ->
        when {
          name == target -> {
            seenTarget = true
            false
          }
          !seenTarget -> false
          else -> !name.startsWith("java.lang.invoke.") && !name.startsWith("jdk.internal.")
        }
      }
      .findFirst()
      .orElse(null)
  }

  private fun isProtected(prefix: String): Boolean = PROTECTED_ROOTS.any { root ->
    root.startsWith(prefix) || prefix.startsWith(root)
  }
}
