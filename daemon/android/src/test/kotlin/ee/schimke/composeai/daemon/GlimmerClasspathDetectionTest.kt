package ee.schimke.composeai.daemon

import java.net.URLClassLoader
import org.junit.Assert.assertFalse
import org.junit.Test

class GlimmerClasspathDetectionTest {

  @Test
  fun returnsFalseForAConsumerWithoutGlimmer() {
    val emptyLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
    assertFalse(isGlimmerAvailable(emptyLoader))
  }
}
