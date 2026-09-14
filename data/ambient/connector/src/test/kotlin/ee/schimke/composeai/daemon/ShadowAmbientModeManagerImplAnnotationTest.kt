package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.annotation.Implements

class ShadowAmbientModeManagerImplAnnotationTest {

  @Test
  fun `shadow targets the private Wear Compose implementation by name`() {
    val annotation = ShadowAmbientModeManagerImpl::class.java.getAnnotation(Implements::class.java)
    assertNotNull("ShadowAmbientModeManagerImpl must carry @Implements", annotation)
    assertEquals(
      "androidx.wear.compose.foundation.AmbientModeManagerImpl",
      annotation.className,
    )
    assertFalse(annotation.isInAndroidSdk)
  }
}
