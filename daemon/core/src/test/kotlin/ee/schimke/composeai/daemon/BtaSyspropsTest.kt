package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.DefaultBtaCompileService
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class BtaSyspropsTest {

  @Test
  fun `compile classpath drops entries that do not exist`() {
    val present = Files.createTempDirectory("bta-present").toFile()
    val jar = File.createTempFile("bta", ".jar")
    val missing = File(present, "javac/debugUnitTest/classes")
    val classpath = listOf(present.path, missing.path, jar.path).joinToString(File.pathSeparator)

    val value = BtaSysprops.lookup(DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH) { classpath }

    assertEquals(listOf(present.path, jar.path).joinToString(File.pathSeparator), value)
  }

  @Test
  fun `other properties pass through unchanged`() {
    val missing = "/does/not/exist"
    assertEquals(
      missing,
      BtaSysprops.lookup(DefaultBtaCompileService.SYSPROP_OUTPUT_DIR) { missing },
    )
    assertEquals(null, BtaSysprops.lookup(DefaultBtaCompileService.SYSPROP_MODULE_NAME) { null })
  }
}
