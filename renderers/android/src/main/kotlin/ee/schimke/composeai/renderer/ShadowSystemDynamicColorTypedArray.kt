package ee.schimke.composeai.renderer

import android.content.res.TypedArray
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * Serves [SystemDynamicColors]' table to `TypedArray.getColor` for arrays obtained through its
 * wrapped context, and to no other array.
 *
 * Material 3 1.5.0 stopped reading `android.R.color.system_*` with `Resources.getColor`: its
 * `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` now resolve a style of its
 * own (`mc3_palette`, `mc3_light_scheme`, `mc3_dark_scheme`) whose items reference those colours,
 * through `context.theme.obtainStyledAttributes(...)`. The asset manager resolves those references
 * while it fills the array, so the wrapped `Resources` never sees the lookup.
 *
 * The array still records which `Resources` it came from and which resource each entry resolved to
 * (`getResourceId`). [SystemDynamicColors.wrap]'s context hands out themes created from its
 * `SystemDynamicColorResources`, so an array from that theme carries it: `getColor` answers the
 * table's colour for a `system_*` entry there, and the real value everywhere else. Like the wrapped
 * `Resources`, this stays scoped to one composition by construction; no process-global state is
 * set.
 */
@Implements(TypedArray::class)
class ShadowSystemDynamicColorTypedArray {

  @RealObject private lateinit var realArray: TypedArray

  @Implementation
  fun getColor(index: Int, defValue: Int): Int {
    val colors = (realArray.resources as? SystemDynamicColorResources)?.colors
    val served = colors?.get(realArray.getResourceId(index, 0))
    return served ?: reflector(TypedArrayReflector::class.java, realArray).getColor(index, defValue)
  }

  /** Direct (un-shadowed) access to the real `TypedArray.getColor`. */
  @ForType(TypedArray::class)
  internal interface TypedArrayReflector {
    @Direct fun getColor(index: Int, defValue: Int): Int
  }

  companion object {
    /** Fully-qualified name, for the sandbox runner's shadow list. */
    const val SHADOW_FQN: String =
      "ee.schimke.composeai.renderer.ShadowSystemDynamicColorTypedArray"
  }
}
