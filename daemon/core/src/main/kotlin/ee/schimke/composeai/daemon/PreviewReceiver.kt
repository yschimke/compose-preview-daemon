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

  private fun singleton(owner: Class<*>): Any? =
    try {
      val field = owner.getDeclaredField("INSTANCE")
      if (Modifier.isStatic(field.modifiers) && field.type == owner) {
        field.isAccessible = true
        field.get(null)
      } else null
    } catch (_: NoSuchFieldException) {
      null
    }
}
