package ee.schimke.composeai.daemon

import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** Public lookup metadata, including misses, owned by the receiver class. */
internal class PublicZeroArgMethodCache {
  private val methods =
    object : ClassValue<ConcurrentHashMap<String, Optional<Method>>>() {
      override fun computeValue(type: Class<*>) = ConcurrentHashMap<String, Optional<Method>>()
    }

  fun find(type: Class<*>, name: String): Method? =
    methods
      .get(type)
      .computeIfAbsent(name) {
        // Preserve getMethod's public/inherited/bridge selection. Other lookup failures
        // propagate without caching; callers retain their existing exception handling.
        Optional.ofNullable(
          try {
            type.getMethod(it)
          } catch (_: NoSuchMethodException) {
            null
          }
        )
      }
      .orElse(null)
}
