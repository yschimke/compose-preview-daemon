package ee.schimke.composeai.daemon

import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

class ZeroArgMethodCacheTest {
  private val cache = ZeroArgMethodCache()

  private open class Parent {
    private fun value() = "parent"

    fun inherited() = "inherited"
  }

  private class Child(var text: String) : Parent() {
    private fun value() = text

    fun value(argument: String) = argument
  }

  @Test
  fun `nearest no-arg declaration wins and receiver values are never cached`() {
    val child = Child("first")
    val method = cache.find(Child::class.java, "value")!!
    assertEquals(Child::class.java, method.declaringClass)
    method.isAccessible = true
    assertEquals("first", method.invoke(child))
    child.text = "second"
    assertSame(method, cache.find(Child::class.java, "value"))
    assertEquals("second", method.invoke(child))
    assertEquals(Parent::class.java, cache.find(Parent::class.java, "value")!!.declaringClass)
    assertEquals("inherited", cache.find(Child::class.java, "inherited")!!.invoke(child))
    repeat(2) { assertNull(cache.find(Child::class.java, "setText")) }
  }

  @Test
  fun `public interface default method is found after class hierarchy`() {
    val method = cache.find(MethodCacheFixture::class.java, "inheritedDefault")!!
    assertTrue(method.declaringClass.isInterface)
    assertEquals("default", method.invoke(MethodCacheFixture()))
  }

  private fun disposableLoader(): WeakReference<ClassLoader> {
    val names = listOf("MethodCacheFixture", "MethodCacheFixtureDefault")
    val bytes = names.associate { simple ->
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
  fun `cached methods and misses do not retain a disposable application loader`() {
    val loader = disposableLoader()
    repeat(100) {
      System.gc()
      if (loader.get() == null) return
      Thread.sleep(20)
    }
    assertNull("Method cache retained the application loader", loader.get())
  }
}
