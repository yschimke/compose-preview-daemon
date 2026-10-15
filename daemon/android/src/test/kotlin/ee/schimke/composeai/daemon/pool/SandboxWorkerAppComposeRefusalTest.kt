package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.AppComposeSelection
import ee.schimke.composeai.daemon.RobolectricHost
import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A spare boots with no user classes, so on the daemon's Compose, and that is fixed for its life.
 * It must refuse a catalog whose own Compose would run (`composeai.daemon.appCompose`) rather than
 * render it on the wrong one; every other catalog adopts it as before. No sandbox is booted here:
 * the refusal compares decisions, and `forkEvery = 1` gives this class a JVM whose decision is the
 * unset-userClassDirs one.
 */
class SandboxWorkerAppComposeRefusalTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  private fun appComposeJar(version: String): File {
    val jar = temp.newFile("app-compose-$version.jar")
    JarOutputStream(jar.outputStream()).use { out ->
      out.putNextEntry(JarEntry(AppComposeSelection.COMPOSER_RESOURCE))
      out.closeEntry()
      out.putNextEntry(JarEntry(AppComposeSelection.RUNTIME_VERSION_RESOURCE))
      out.write(version.toByteArray())
      out.closeEntry()
    }
    return jar
  }

  private fun configure(dirs: List<File>, mode: String? = null) =
    WorkerRequest.Configure(
      systemProperties =
        buildMap {
          put(
            DaemonProperties.Names.USER_CLASS_DIRS,
            dirs.joinToString(File.pathSeparator) { it.absolutePath },
          )
          if (mode != null) put(DaemonProperties.Names.APP_COMPOSE, mode)
        },
      parentPid = 1,
    )

  @Test
  fun `a spare refuses a catalog whose newer Compose would run and adopts the rest`() {
    System.clearProperty(DaemonProperties.Names.USER_CLASS_DIRS)
    assertFalse(RobolectricHost.appComposeDecision.preferApp)

    val classes = temp.newFolder("classes")
    val newer = appComposeJar("99.0.0")

    assertNull(SandboxWorkerMain.appComposeRefusal(configure(listOf(classes))))
    assertNotNull(SandboxWorkerMain.appComposeRefusal(configure(listOf(classes, newer))))
    assertNull(
      SandboxWorkerMain.appComposeRefusal(configure(listOf(classes, newer), mode = "daemon"))
    )
  }
}
