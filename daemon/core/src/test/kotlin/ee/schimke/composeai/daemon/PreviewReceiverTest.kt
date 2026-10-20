package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver a preview method is invoked on (compose-preview-daemon#343). */
class PreviewReceiverTest {
  @Test
  fun `a top-level preview takes no receiver`() {
    val method = Class.forName(TOP_LEVEL_FILE_CLASS).getDeclaredMethod("topLevelPreview")
    assertNull(PreviewReceiver.of(method))
  }

  @Test
  fun `a preview inside an object is invoked on the singleton`() {
    val method = ObjectPreviews::class.java.getDeclaredMethod("itemPreview")
    assertSame(ObjectPreviews, PreviewReceiver.of(method))
    assertEquals("object", method.invoke(PreviewReceiver.of(method)))
  }

  @Test
  fun `a preview inside a class is invoked on a fresh instance`() {
    val method = ClassPreviews::class.java.getDeclaredMethod("itemPreview")
    val first = PreviewReceiver.of(method)
    assertTrue(first is ClassPreviews)
    assertNotSame(first, PreviewReceiver.of(method))
    assertEquals("class", method.invoke(first))
  }

  @Test
  fun `a preview inside a companion object is invoked on the companion itself`() {
    val method = WithCompanion.Companion::class.java.getDeclaredMethod("itemPreview")
    assertSame(WithCompanion.Companion, PreviewReceiver.of(method))
    assertEquals(1, WithCompanion.initialisations)
  }

  @Test
  fun `a class with no no-arg constructor is named`() {
    val method = NeedsArgument::class.java.getDeclaredMethod("itemPreview")
    val error = runCatching { PreviewReceiver.of(method) }.exceptionOrNull()
    assertTrue("$error", error is IllegalStateException)
    assertTrue(error!!.message!!.contains(NeedsArgument::class.java.name))
  }

  object ObjectPreviews {
    fun itemPreview(): String = "object"
  }

  class ClassPreviews {
    fun itemPreview(): String = "class"
  }

  class WithCompanion {
    companion object {
      var initialisations = 0

      init {
        initialisations++
      }

      fun itemPreview(): String = "companion"
    }
  }

  class NeedsArgument(private val label: String) {
    fun itemPreview(): String = label
  }

  private companion object {
    const val TOP_LEVEL_FILE_CLASS = "ee.schimke.composeai.daemon.PreviewReceiverTestKt"
  }
}

@Suppress("unused") internal fun topLevelPreview(): String = "top-level"
