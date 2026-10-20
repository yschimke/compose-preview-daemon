package ee.schimke.composeai.daemon

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The receiver a preview method is invoked on (compose-preview-daemon#343).
 *
 * A top-level `@Preview` compiles to a static method on its file class and takes no receiver. One
 * declared inside a Kotlin `object` or a class is an instance method, and invoking it with a `null`
 * receiver threw before anything rendered. Resolved the way the batch renderer's
 * `resolvePreviewReceiver` and Compose tooling's `ComposeViewAdapter` do: the `object`'s
 * `INSTANCE`, else a fresh instance from the no-arg constructor.
 */
public object PreviewReceiver {
  /**
   * `null` for a static [method]; otherwise the instance its declaring class supplies. Throws
   * [IllegalStateException] naming the class when it supplies none, rather than letting the invoke
   * fail on a null receiver.
   */
  public fun of(method: Method): Any? {
    if (Modifier.isStatic(method.modifiers)) return null
    val owner = method.declaringClass
    singleton(owner)?.let {
      return it
    }
    return try {
      owner.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    } catch (e: ReflectiveOperationException) {
      throw IllegalStateException(
        "@Preview ${owner.name}.${method.name} is an instance method, but ${owner.name} is " +
          "neither a Kotlin object nor constructible without arguments",
        e,
      )
    }
  }

  /**
   * The Kotlin singleton [owner] is: an `object`'s own `INSTANCE`, or — for a companion object,
   * whose singleton Kotlin stores on the enclosing class — that class's static field of [owner]'s
   * type. A companion also has a private no-arg constructor, so falling through to it would build a
   * second instance and rerun its initialisation.
   */
  private fun singleton(owner: Class<*>): Any? {
    val own =
      try {
        owner.getDeclaredField("INSTANCE")
      } catch (_: NoSuchFieldException) {
        null
      }
    val field =
      own?.takeIf { Modifier.isStatic(it.modifiers) && it.type == owner }
        ?: owner.enclosingClass?.declaredFields?.firstOrNull {
          Modifier.isStatic(it.modifiers) && it.type == owner
        }
        ?: return null
    field.isAccessible = true
    return field.get(null)
  }
}
