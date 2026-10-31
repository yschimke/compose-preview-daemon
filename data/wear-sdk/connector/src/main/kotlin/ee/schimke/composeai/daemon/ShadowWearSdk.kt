package ee.schimke.composeai.daemon

import android.annotation.SuppressLint
import android.content.Context
import android.os.OutcomeReceiver
import android.view.HapticFeedbackConstants
import com.google.wear.WearApiVersion
import com.google.wear.services.tiles.TileInstance
import com.google.wear.services.tiles.TilesManager
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Safe simulator implementations for the small optional Wear SDK surface reached by Wear Compose
 * and AndroidX Tiles on startup.
 *
 * The production Wear SDK jar contains platform stubs, so merely placing it on Robolectric's
 * classpath trades `NoClassDefFoundError` for `RuntimeException("Stub!")`. These shadows are
 * deliberately narrow: haptic calls return framework identifiers (Robolectric performs no physical
 * actuation), and Tiles reports a deterministic empty registry unless the simulator's system-UI
 * tile bridge supplies one in a later layer. APIs not represented here retain the signature jar's
 * explicit unsupported failure instead of pretending device behavior succeeded.
 */
@Implements(className = "com.google.wear.input.WearHapticFeedbackConstants")
class ShadowWearHapticFeedbackConstants {
  companion object {
    @JvmStatic @Implementation fun getScrollItemFocus(): Int = HapticFeedbackConstants.KEYBOARD_TAP

    @JvmStatic @Implementation fun getScrollLimit(): Int = HapticFeedbackConstants.REJECT

    @JvmStatic @Implementation fun getScrollTick(): Int = HapticFeedbackConstants.CLOCK_TICK
  }
}

/** Provides only the Tiles manager that the simulator can safely model. */
@Implements(className = "com.google.wear.Sdk")
class ShadowWearSdk {
  companion object {
    @JvmStatic
    @Implementation
    fun <T> getWearManager(
      @Suppress("UNUSED_PARAMETER") context: Context,
      managerClass: Class<T>,
    ): T? = managerFor(managerClass)

    @JvmStatic
    @Implementation
    fun <T> getWearManager(managerClass: Class<T>): T? = managerFor(managerClass)

    @JvmStatic
    @Implementation
    fun hasApiFeature(@Suppress("UNUSED_PARAMETER") feature: String): Boolean = false

    @JvmStatic
    @Implementation
    fun isApiVersionAtLeast(@Suppress("UNUSED_PARAMETER") version: WearApiVersion): Boolean = false

    private fun <T> managerFor(managerClass: Class<T>): T? =
      if (managerClass == TilesManager::class.java) managerClass.cast(TilesManager()) else null
  }
}

/**
 * AndroidX Tiles only needs an asynchronous snapshot of active tiles while an app starts. The
 * simulator has no implicit installed tiles, so report an immutable empty result on the caller's
 * executor, matching the platform callback contract without inventing a tile installation.
 */
@Implements(className = "com.google.wear.services.tiles.TilesManager")
class ShadowTilesManager {
  @SuppressLint("NewApi")
  @Implementation
  fun getActiveTiles(
    executor: Executor,
    receiver: OutcomeReceiver<List<TileInstance>, Exception>,
  ) {
    executor.execute { receiver.onResult(emptyList()) }
  }
}
