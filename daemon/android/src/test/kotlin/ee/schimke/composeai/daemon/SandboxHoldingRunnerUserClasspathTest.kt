package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.config.DaemonProperties
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod

/**
 * The availability gates when the consumer's AndroidX jars reach the sandbox only through
 * `composeai.daemon.userClassDirs` — the Bazel and bundle lanes (compose-preview-daemon#341).
 *
 * There the worker thread's context loader is an [AppComposeFirstLoader]: it serves the consumer's
 * `androidx/` bytecode, which Robolectric's sandbox loader then defines, but defines no class
 * itself. A gate that only tried `Class.forName` against it, or against the daemon's own loader,
 * reported Remote Compose absent, so `ShadowRemoteContentPreview` was never registered.
 */
class SandboxHoldingRunnerUserClasspathTest {
  private val root: File = Files.createTempDirectory("user-classpath").toFile()
  private val previousContext = Thread.currentThread().contextClassLoader
  private val previousUserClassDirs = System.getProperty(USER_CLASS_DIRS)

  @After
  fun restore() {
    Thread.currentThread().contextClassLoader = previousContext
    if (previousUserClassDirs == null) System.clearProperty(USER_CLASS_DIRS)
    else System.setProperty(USER_CLASS_DIRS, previousUserClassDirs)
    root.deleteRecursively()
  }

  /** A user class directory carrying (placeholder) bytecode for [classNames]. */
  private fun userClasses(vararg classNames: String): File =
    root.resolve("classes").apply {
      classNames.forEach { name ->
        resolve(name.replace('.', '/') + ".class").apply {
          parentFile.mkdirs()
          writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        }
      }
    }

  @Test
  fun `a class only the app-Compose-first parent serves counts as available`() {
    // A name nothing on the daemon's own test classpath carries.
    val consumerOnly = "androidx.consumeronly.Marker"
    val dir = userClasses(consumerOnly)
    val parent = SandboxHoldingRunner::class.java.classLoader!!
    assertFalse(isClassVisible(consumerOnly, parent))
    assertTrue(
      isClassVisible(consumerOnly, AppComposeFirstLoader(listOf(dir.toURI().toURL()), parent))
    )
  }

  @Test
  fun `the runner registers the RemoteContentPreview shadow from the sandbox parent's view`() {
    val dir = userClasses(REMOTE_COMPOSE_HOST_ACTION, ShadowRemoteContentPreview.TARGET_CLASS)
    val parent = SandboxHoldingRunner::class.java.classLoader!!
    val runner = ExposedRunner(DummyTest::class.java)
    val method = FrameworkMethod(DummyTest::class.java.getMethod("stub"))
    assertFalse(
      runner.exposedExtraShadows(method).any { it == ShadowRemoteContentPreview::class.java }
    )

    Thread.currentThread().contextClassLoader =
      AppComposeFirstLoader(listOf(dir.toURI().toURL()), parent)
    assertTrue(
      runner.exposedExtraShadows(method).any { it == ShadowRemoteContentPreview::class.java }
    )
  }

  @Test
  fun `the IR sidecar channel is shared with the host, not reloaded in the sandbox`() {
    val runner = ExposedRunner(DummyTest::class.java)
    val config = runner.exposedClassLoaderConfig(runner.firstChild())
    assertFalse(config.shouldAcquire(IR_SIDECAR_CHANNEL))
    assertFalse(config.shouldAcquire("$IR_SIDECAR_CHANNEL\$IrCapture"))
    // The rest of the package stays the sandbox's.
    assertTrue(config.shouldAcquire("ee.schimke.composeai.data.render.PreviewContext"))
  }

  @Test
  fun `the user classpath answers for the host-side registry, the daemon's own does not`() {
    System.clearProperty(USER_CLASS_DIRS)
    assertFalse(isOnUserClasspath(REMOTE_COMPOSE_HOST_ACTION))
    System.setProperty(USER_CLASS_DIRS, userClasses(REMOTE_COMPOSE_HOST_ACTION).absolutePath)
    assertTrue(isOnUserClasspath(REMOTE_COMPOSE_HOST_ACTION))
    // Bootstrap-parented: a class the daemon itself carries is not "on the user classpath".
    assertFalse(isOnUserClasspath(SandboxHoldingRunner::class.java.name))
  }

  private class ExposedRunner(testClass: Class<*>) : SandboxHoldingRunner(testClass) {
    fun exposedExtraShadows(method: FrameworkMethod): Array<Class<*>> = getExtraShadows(method)

    fun exposedClassLoaderConfig(method: FrameworkMethod) = createClassLoaderConfig(method)

    fun firstChild(): FrameworkMethod = children.first()
  }

  // See SandboxHoldingRunnerFontShadowTest.DummyTest: read back only, never bootstrapped.
  class DummyTest {
    @Test fun stub() = Unit
  }

  private companion object {
    const val USER_CLASS_DIRS = DaemonProperties.Names.USER_CLASS_DIRS
  }
}
