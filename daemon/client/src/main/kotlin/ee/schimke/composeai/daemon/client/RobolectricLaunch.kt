package ee.schimke.composeai.daemon.client

import java.io.File

/**
 * How to run this repository's Robolectric renderer, independent of what is being launched.
 *
 * ### Why this is separate from [DaemonBackend.Android]
 *
 * These facts — the `--add-opens` set our host needs to reflect into `java.base` on JDK 17+, the
 * `robolectric.*` values our renderer expects, and the font properties a parent `-D` cannot deliver
 * to a child JVM — are properties of the *renderer*, not of the daemon that usually hosts it. A
 * caller that spawns the renderer directly for a one-shot batch render needs every one of them and
 * has no daemon at all.
 *
 * [DaemonBackend.Android] carries an `android.jar` because a daemon's **classpath** needs one. Its
 * `jvmArgs()` and `systemProperties()` never read it, so reaching them through it forced a caller
 * with no jar to invent one. compose-ai-tools hit this on its first adoption pass: threading a jar
 * through three call sites purely to construct a backend broke `AndroidBundleLaunch`'s published
 * constructor, which compose-preview-server calls across a repository boundary
 * (yschimke/compose-ai-tools#5379). The facts were in the right repository and behind the wrong
 * door.
 *
 * So the door is here, and [DaemonBackend.Android] is one caller of it. Everything it returns is
 * identical to what that backend returns — `DaemonBackendTest` pins that they cannot drift — which
 * is what makes a renderer launch and a daemon launch configure Robolectric the same way.
 *
 * ### What is not here
 *
 * The SDK level. It changes nothing in either of these — it belongs to [RobolectricConfig], which
 * writes the `robolectric.properties` Robolectric actually reads it from, and is reachable without
 * a jar already.
 */
public object RobolectricLaunch {

  /**
   * JVM args the Robolectric process needs before anything else initialises.
   *
   * Without the `--add-opens` set, Robolectric's reflective access into `java.base` internals fails
   * with `IllegalAccessException` on SDK 36 sandboxes (compose-ai-tools#1328).
   */
  public fun jvmArgs(): List<String> =
    listOf(
      DaemonBackend.ENABLE_NATIVE_ACCESS,
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )

  /**
   * Renderer-facing system properties, plus the four this process must name explicitly because a
   * `-D` on a parent JVM does not reach a spawned one.
   *
   * The forwarded four are only present when this process actually set them, so an unset property
   * stays absent rather than arriving as an empty value the child would parse.
   */
  public fun systemProperties(): Map<String, String> = buildMap {
    put("robolectric.graphicsMode", "NATIVE")
    put("robolectric.looperMode", "PAUSED")
    put("robolectric.conscryptMode", "OFF")
    put("robolectric.pixelCopyRenderMode", "hardware")
    put("roborazzi.test.record", "true")
    putAll(DaemonBackend.fontSystemProperties())
    // Android-only: the interceptor that fails a preview on an unresolved downloadable font lives
    // in `renderers/android`.
    System.getProperty(FAIL_ON_FALLBACK)?.let { put(FAIL_ON_FALLBACK, it) }
  }

  /**
   * The `robolectric.properties` files for [sdkLevel] — the same object
   * [DaemonBackend.Android.robolectricConfig] returns, reachable without a backend.
   *
   * @param useConsumerApplication see [RobolectricConfig].
   */
  public fun config(sdkLevel: Int, useConsumerApplication: Boolean = false): RobolectricConfig =
    RobolectricConfig(
      sdkLevel = sdkLevel.coerceIn(AndroidSdk.MIN_SDK, AndroidSdk.MAX_SDK),
      useConsumerApplication = useConsumerApplication,
    )

  /**
   * Where an Android renderer's own jars live, as a convenience over [DaemonRuntimeLocation]: a
   * one-shot renderer launch needs [DaemonRuntimeArtifact.RENDERER] and, unlike a daemon launch,
   * nothing else.
   */
  public fun rendererJars(runtime: DaemonRuntimeLocation): List<File> =
    runtime.jarsFor(DaemonRuntimeArtifact.RENDERER)

  private const val FAIL_ON_FALLBACK: String = "composeai.fonts.failOnFallback"
}
