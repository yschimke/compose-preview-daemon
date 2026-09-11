package ee.schimke.composeai.daemon

import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Reflection metadata owned by the receiver class, so application reloads can unload it. */
internal class ZeroArgMethodCache {
  private val methods =
    object : ClassValue<ConcurrentHashMap<String, Optional<Method>>>() {
      override fun computeValue(type: Class<*>) = ConcurrentHashMap<String, Optional<Method>>()
    }

  fun find(type: Class<*>, name: String): Method? =
    methods
      .get(type)
      .computeIfAbsent(name) { Optional.ofNullable(findUncached(type, it)) }
      .orElse(null)

  private fun findUncached(type: Class<*>, name: String): Method? {
    var current: Class<*>? = type
    while (current != null) {
      current.declaredMethods
        .firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let {
          return it
        }
      current = current.superclass
    }
    return type.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
  }
}
