package ee.schimke.composeai.buildlogic

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ClientCompatibilityFloorTest {

  @Test
  fun `allows versions at or below ceiling and flags versions above`() {
    val ceilings =
      listOf(
        ClientCompatibilityFloor.Ceiling("androidx.compose.ui:", 1, 9, "compose-bom-compat 2025.11.x"),
        ClientCompatibilityFloor.Ceiling("androidx.core:core", 1, 13, "keyboard connector floor"),
        ClientCompatibilityFloor.Ceiling("com.materialkolor:material-kolor", 4, 0, "wallpaper floor"),
      )
    val resolved =
      listOf(
        ClientCompatibilityFloor.ResolvedModule("debugCompileClasspath", "androidx.compose.ui:ui", "1.9.5"),
        ClientCompatibilityFloor.ResolvedModule("debugCompileClasspath", "androidx.compose.ui:ui-text", "1.10.0"),
        ClientCompatibilityFloor.ResolvedModule("debugCompileClasspath", "androidx.core:core", "1.13.1"),
        ClientCompatibilityFloor.ResolvedModule("debugCompileClasspath", "androidx.core:core-ktx", "1.19.1"),
        ClientCompatibilityFloor.ResolvedModule("runtimeClasspath", "com.materialkolor:material-kolor", "4.1.0"),
      )

    val offenders = ClientCompatibilityFloor.findVersionOffenders(resolved, ceilings)
    assertEquals(2, offenders.size)
    assertTrue(offenders.any { it.contains("androidx.compose.ui:ui-text:1.10.0") })
    assertTrue(offenders.any { it.contains("com.materialkolor:material-kolor:4.1.0") })
  }

  @Test
  fun `flags class files above Java 17 while ignoring multi-release entries`(@TempDir tempDir: File) {
    val jar = File(tempDir, "sample.jar")
    ZipOutputStream(jar.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("com/example/Java17.class"))
      zos.write(classHeader(61))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("META-INF/versions/21/com/example/Java21Mr.class"))
      zos.write(classHeader(65))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("com/example/Java25.class"))
      zos.write(classHeader(69))
      zos.closeEntry()
    }

    val offenders = ClientCompatibilityFloor.findBytecodeOffenders(listOf(jar), maxClassMajor = 61)
    assertEquals(listOf("sample.jar!com/example/Java25.class (major 69)"), offenders)
  }

  @Test
  fun `reads class major version and rejects non-class bytes`() {
    assertEquals(61, ClientCompatibilityFloor.majorVersion(ByteArrayInputStream(classHeader(61))))
    assertEquals(
      null,
      ClientCompatibilityFloor.majorVersion(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))),
    )
  }

  private fun classHeader(major: Int): ByteArray =
    byteArrayOf(
      0xCA.toByte(),
      0xFE.toByte(),
      0xBA.toByte(),
      0xBE.toByte(),
      0x00,
      0x00,
      ((major shr 8) and 0xFF).toByte(),
      (major and 0xFF).toByte(),
    )
}
