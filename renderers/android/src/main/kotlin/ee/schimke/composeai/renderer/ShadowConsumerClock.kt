package ee.schimke.composeai.renderer

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import org.robolectric.util.reflector.Static

/**
 * Hands the consumer's own `System.currentTimeMillis()` calls [PreviewClock]'s pinned instant,
 * without moving Robolectric's `SystemClock` (compose-ai-tools#5945).
 *
 * ## The path a consumer's clock read takes
 *
 * Robolectric 4.17 rewrites `System.currentTimeMillis()` and `System.nanoTime()` call sites, but
 * only inside *instrumented* classes. `AndroidInterceptors.SystemTimeInterceptor` names both as
 * intercepted methods, `ClassInstrumentor` replaces each `invokestatic
 * java/lang/System.currentTimeMillis()J` with an `invokedynamic` bootstrapped by
 * `InvokeDynamicSupport.bootstrapIntrinsic`, and that binds a `ConstantCallSite` to
 * `org.robolectric.shadows.ShadowSystem.currentTimeMillis()` — which, under `LooperMode.PAUSED`,
 * returns `SystemClock.uptimeMillis()`: 100 ms past the epoch plus whatever the looper advanced. So
 * adding the consumer's packages to `instrumentedPackages` alone makes its clock *deterministic*
 * but wrong — 1 January 1970, not [PreviewClock]'s `10:10` — and makes `fixedTime=off` meaningless.
 *
 * The rewrite target is fixed. `bootstrapIntrinsic` receives the caller's `Lookup` and drops it,
 * and `Interceptor.getMethodHandle(methodName, type)` has no caller parameter, so the intercept
 * cannot be scoped to the consumer's classes; replacing the interceptor
 * (`RobolectricTestRunner.findInterceptors`) would only reach runners we subclass, and the one-shot
 * renderer runs on the `final` `ParameterizedRobolectricTestRunner`. What *can* be reached from
 * both lanes is `ShadowSystem` itself: it is an ordinary class in `org.robolectric.shadows`,
 * acquired (not merely loaded) by the sandbox, so it can be instrumented and shadowed like any
 * other.
 *
 * ## What this shadow does
 *
 * Shadows `ShadowSystem.currentTimeMillis()` and `ShadowSystem.nanoTime()`. Each call walks a few
 * stack frames to the class that made the original `System.*` call:
 * - a class matching [ConsumerClock.packages] gets [PreviewClock.currentTimeMillis] — the pinned
 *   instant, or the host clock under `fixedTime=off` — and the host `System.nanoTime()`, so
 *   instrumenting the consumer changes its wall clock and nothing else;
 * - every other caller (the Android framework, coil, …) gets `ShadowSystem`'s own implementation
 *   through a `@Direct` reflector, exactly as before.
 *
 * With the property unset or empty, both methods return the original immediately without walking
 * the stack.
 *
 * ## Registration — both halves, as for every shadow here
 *
 * The **shadow** is registered by name (`shadows=…ShadowConsumerClock` in `robolectric.properties`,
 * or `SandboxHoldingRunner.getExtraShadows`) and **`ShadowSystem` has to be instrumented**, because
 * Robolectric cannot shadow a class it did not rewrite. On the properties / `@Config(shadows=…)`
 * path that is automatic: `AndroidConfigurer.withConfig` calls `addInstrumentedClass` for every
 * registered shadow's `@Implements` target — the exact class, so `ShadowSystemClock` and the other
 * `ShadowSystem*` siblings a package prefix would catch are untouched. `getExtraShadows` does not
 * go through `withConfig`, so `SandboxHoldingRunner` adds the class itself. And the consumer's own
 * packages have to be instrumented too (`instrumentedPackages=`), or there is no rewritten call
 * site to reach here at all.
 *
 * Both are registered only when [ConsumerClock.PROPERTY] names packages, so an unconfigured render
 * runs exactly as before.
 */
@Implements(className = ConsumerClock.SHADOW_SYSTEM, isInAndroidSdk = false)
class ShadowConsumerClock {
  companion object {
    @JvmStatic
    @Implementation
    fun currentTimeMillis(): Long =
      if (calledFromConsumer()) PreviewClock.currentTimeMillis()
      else reflector(ShadowSystemReflector::class.java).currentTimeMillis()

    @JvmStatic
    @Implementation
    fun nanoTime(): Long =
      if (calledFromConsumer()) System.nanoTime()
      else reflector(ShadowSystemReflector::class.java).nanoTime()

    private fun calledFromConsumer(): Boolean {
      val packages = ConsumerClock.packages()
      if (packages.isEmpty()) return false
      val caller = ConsumerClock.callerOf(ConsumerClock.SHADOW_SYSTEM) ?: return false
      return ConsumerClock.isConsumerClass(caller, packages)
    }
  }

  /** `ShadowSystem`'s original implementations, bypassing this shadow. */
  @ForType(className = ConsumerClock.SHADOW_SYSTEM)
  internal interface ShadowSystemReflector {
    @Static @Direct fun currentTimeMillis(): Long

    @Static @Direct fun nanoTime(): Long
  }
}
