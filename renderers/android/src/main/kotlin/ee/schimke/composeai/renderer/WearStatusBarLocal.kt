package ee.schimke.composeai.renderer

import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Resolves Wear Compose Material 3's `LocalStatusBarEnabled` by reflection so the renderer and
 * daemon can opt Wear Compose 1.8.0-alpha02+ previews into the Wear OS Global Status Bar (GSB)
 * without taking a compile-time dependency on `androidx.wear.compose:compose-material3`.
 *
 * Wear Compose Material 3 1.8.0-alpha02+ declares:
 * ```
 * package androidx.wear.compose.material3
 * internal val LocalStatusBarEnabled: CompositionLocal<Boolean> =
 *     compositionLocalWithComputedDefaultOf { isStatusBarEnabled(...) }
 * ```
 *
 * `compositionLocalWithComputedDefaultOf` returns a `ComputedProvidableCompositionLocal<Boolean>`
 * which is a [ProvidableCompositionLocal], so casting the reflective result allows providing `true`
 * via `CompositionLocalProvider` even when Robolectric runs on SDK 35/36 rather than the Wear OS
 * 7.1 Canary API 37.2 emulator.
 *
 * When the consumer module is on Wear Compose <= 1.7 or is not a Wear module, the lookup returns
 * `null` and the caller leaves the preview untouched so legacy curved `TimeText` is never
 * double-drawn under a synthetic status bar.
 *
 * **Classloader & caching.** Follows the exact same disposable child-classloader discipline as
 * [WearReduceMotionLocal]: own-loader results are memoised; distinct child loaders from the daemon
 * are resolved fresh so swapped loaders are never pinned.
 */
object WearStatusBarLocal {
  private val own: ClassLoader? = WearStatusBarLocal::class.java.classLoader

  private class Holder(val local: ProvidableCompositionLocal<Boolean>?)

  private val ownResult: Holder by lazy { Holder(own?.let(::resolve)) }

  /** Resolve against this class's own loader — the standalone renderer's JUnit classpath. */
  fun get(): ProvidableCompositionLocal<Boolean>? = ownResult.local

  /**
   * Resolve `LocalStatusBarEnabled` from [loader] — pass the child (app) classloader in the daemon,
   * where the user's `wear-compose-material3` isn't on the daemon's own loader. Null [loader] falls
   * back to the own loader. Returns `null` when Wear Compose Material 3 1.8.0-alpha02+ isn't
   * reachable from [loader].
   */
  fun get(loader: ClassLoader?): ProvidableCompositionLocal<Boolean>? {
    if (loader == null || loader === own) return ownResult.local
    return resolve(loader)
  }

  @Suppress("UNCHECKED_CAST")
  private fun resolve(loader: ClassLoader): ProvidableCompositionLocal<Boolean>? = runCatching {
    val clazz = Class.forName("androidx.wear.compose.material3.StatusBarKt", false, loader)
    val method = clazz.getDeclaredMethod("getLocalStatusBarEnabled")
    method.invoke(null) as? ProvidableCompositionLocal<Boolean>
  }
    .getOrNull()
}
