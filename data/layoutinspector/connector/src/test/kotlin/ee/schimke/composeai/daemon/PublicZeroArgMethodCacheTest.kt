package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.fixtures.HiddenPublicMethodFactory
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class PublicZeroArgMethodCacheTest {
  private val cache = PublicZeroArgMethodCache()

  open class Parent {
    open fun value(): Any = "parent"

    fun inherited() = "inherited"

    private fun hidden() = "hidden"
  }

  class Child(var text: String) : Parent() {
    override fun value(): String = text

    fun value(argument: String) = argument

    fun throwing(): String = error(text)
  }

  @Test
  fun `public lookup preserves covariant selection and live receiver values`() {
    val type = Child::class.java
    val method = cache.find(type, "value")!!
    assertEquals(type.getMethod("value"), method)
    assertSame(method, cache.find(type, "value"))
    val receiver = Child("first")
    assertEquals("first", method.invoke(receiver))
    receiver.text = "second"
    assertEquals("second", method.invoke(receiver))
    assertEquals("third", method.invoke(Child("third")))
    assertEquals(type.getMethod("inherited"), cache.find(type, "inherited"))
    assertEquals(String::class.java, method.returnType)
  }

  @Test
  fun `private declarations and argument-taking methods remain absent`() {
    repeat(2) {
      assertNull(cache.find(Child::class.java, "hidden"))
      assertNull(cache.find(Child::class.java, "setText"))
      assertNull(cache.find(Child::class.java, "missing"))
    }
  }

  @Test
  fun `public interface defaults follow getMethod selection`() {
    val type = MethodCacheFixture::class.java
    assertEquals(type.getMethod("inheritedDefault"), cache.find(type, "inheritedDefault"))
    assertEquals("default", cache.find(type, "inheritedDefault")!!.invoke(MethodCacheFixture()))
  }

  @Test
  fun `invocation failures are not cached as lookup misses`() {
    val receiver = Child("first")
    repeat(2) { index ->
      receiver.text = "failure $index"
      val error =
        assertThrows(InvocationTargetException::class.java) {
          cache.find(Child::class.java, "throwing")!!.invoke(receiver)
        }
      assertEquals(receiver.text, error.cause!!.message)
    }
  }

  @Test
  fun `lookup does not bypass inaccessible declaring classes`() {
    val receiver = HiddenPublicMethodFactory.receiver()
    val original = receiver.javaClass.getMethod("value")
    val cached = cache.find(receiver.javaClass, "value")!!
    assertEquals(original, cached)
    assertFalse(cached.canAccess(receiver))
    assertThrows(IllegalAccessException::class.java) { original.invoke(receiver) }
    assertThrows(IllegalAccessException::class.java) { cached.invoke(receiver) }
  }

  private fun disposableLoader(): WeakReference<ClassLoader> {
    val bytes =
      listOf("MethodCacheFixture", "MethodCacheFixtureDefault").associate { simple ->
        val name = "ee.schimke.composeai.daemon.$simple"
        name to
          javaClass.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use {
            it.readBytes()
          }
      }
    val loader =
      object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> {
          val data = bytes[name] ?: throw ClassNotFoundException(name)
          return defineClass(name, data, 0, data.size)
        }
      }
    val type = loader.loadClass("ee.schimke.composeai.daemon.MethodCacheFixture")
    assertNotNull(cache.find(type, "value"))
    assertNotNull(cache.find(type, "inheritedDefault"))
    assertNull(cache.find(type, "missing"))
    return WeakReference(loader)
  }

  @Test
  fun `cached public methods and misses do not retain an application loader`() {
    val loader = disposableLoader()
    try {
      repeat(100) {
        System.gc()
        if (loader.get() == null) return
        Thread.sleep(20)
      }
      assertNull("Public method cache retained the application loader", loader.get())
    } finally {
      Reference.reachabilityFence(cache)
    }
  }
}
