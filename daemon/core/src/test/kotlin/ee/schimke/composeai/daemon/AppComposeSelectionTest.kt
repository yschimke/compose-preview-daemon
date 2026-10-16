package ee.schimke.composeai.daemon

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AppComposeSelection] decides whose Compose a sandbox defines, and [AppComposeFirstLoader] is how
 * that decision reaches Robolectric. The sandbox half (a real render on the app's bytes) is
 * `:daemon:android`'s `AppComposeSandboxTest`.
 */
class AppComposeSelectionTest {

  @get:Rule val temp: TemporaryFolder = TemporaryFolder()

  private fun composeJar(name: String, version: String?, marker: String = name): File {
    val jar = temp.newFile("$name.jar")
    JarOutputStream(jar.outputStream()).use { out ->
      fun put(entry: String, bytes: ByteArray) {
        out.putNextEntry(JarEntry(entry))
        out.write(bytes)
        out.closeEntry()
      }
      put(AppComposeSelection.COMPOSER_RESOURCE, emptyClass("androidx/compose/runtime/Composer"))
      put("androidx/compose/ui/R\$id.class", marker.toByteArray())
      put("kotlinx/coroutines/Job.class", marker.toByteArray())
      put("com/example/app/MainKt.class", marker.toByteArray())
      if (marker == "APP") put("android/support/v4/app/SupportActivity.class", marker.toByteArray())
      if (version != null) put(AppComposeSelection.RUNTIME_VERSION_RESOURCE, version.toByteArray())
    }
    return jar
  }

  /** A minimal valid `public class [internalName] extends Object` with no members. */
  private fun emptyClass(internalName: String): ByteArray {
    val bytes = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(bytes).use { out ->
      out.writeInt(0xCAFEBABE.toInt())
      out.writeShort(0) // minor
      out.writeShort(52) // Java 8
      out.writeShort(5) // constant pool count + 1
      out.writeByte(1) // #1 Utf8 this
      out.writeUTF(internalName)
      out.writeByte(7) // #2 Class #1
      out.writeShort(1)
      out.writeByte(1) // #3 Utf8 super
      out.writeUTF("java/lang/Object")
      out.writeByte(7) // #4 Class #3
      out.writeShort(3)
      out.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
      out.writeShort(2)
      out.writeShort(4)
      repeat(4) { out.writeShort(0) } // interfaces, fields, methods, attributes
    }
    return bytes.toByteArray()
  }

  private fun plainJar(name: String): File {
    val jar = temp.newFile("$name.jar")
    JarOutputStream(jar.outputStream()).use { out ->
      out.putNextEntry(JarEntry("com/example/app/MainKt.class"))
      out.write(name.toByteArray())
      out.closeEntry()
    }
    return jar
  }

  private fun daemonLoader(version: String?): ClassLoader =
    URLClassLoader(arrayOf(composeJar("daemon", version).toURI().toURL()), null)

  private fun File.url(): URL = toURI().toURL()

  private fun URL.text(): String = openStream().use { it.readBytes().decodeToString() }

  @Test
  fun userClasspathWithoutComposeKeepsTheDaemonsCompose() {
    val d =
      AppComposeSelection.decide(listOf(plainJar("app").url()), daemonLoader("1.12.1"), "auto")
    assertFalse(d.preferApp)
    assertEquals("user-classpath-has-no-compose", d.reason)
    assertTrue(d.appUrls.isEmpty())
  }

  @Test
  fun newerAppComposeRunsByDefault() {
    val app = composeJar("app", "1.13.0-alpha01")
    val d = AppComposeSelection.decide(listOf(app.url()), daemonLoader("1.12.1"), "auto")
    assertTrue(d.logLine(), d.preferApp)
    assertEquals("app-newer", d.reason)
    assertEquals("1.13.0-alpha01", d.appVersion)
    assertEquals("1.12.1", d.daemonVersion)
    assertTrue(d.logLine().contains("runs=app"))
  }

  @Test
  fun escapeHatchKeepsTheDaemonsCompose() {
    val app = composeJar("app", "1.13.0-alpha01")
    val d = AppComposeSelection.decide(listOf(app.url()), daemonLoader("1.12.1"), "daemon")
    assertFalse(d.preferApp)
    assertEquals("escape-hatch", d.reason)
  }

  @Test
  fun sameOrOlderAppComposeKeepsTheDaemonsUnlessForced() {
    val same = composeJar("same", "1.12.1")
    val older = composeJar("older", "1.10.0")
    val daemon = daemonLoader("1.12.1")
    assertEquals(
      "same-version",
      AppComposeSelection.decide(listOf(same.url()), daemon, "auto").reason,
    )
    val olderAuto = AppComposeSelection.decide(listOf(older.url()), daemon, "auto")
    assertFalse(olderAuto.preferApp)
    assertEquals("app-older", olderAuto.reason)
    val olderForced = AppComposeSelection.decide(listOf(older.url()), daemon, "app")
    assertTrue(olderForced.preferApp)
    assertEquals("forced", olderForced.reason)
  }

  @Test
  fun unversionedOrDaemonlessComposeRunsTheApps() {
    val unversioned = composeJar("unversioned", version = null)
    assertEquals(
      "app-version-unknown",
      AppComposeSelection.decide(listOf(unversioned.url()), daemonLoader("1.12.1"), "auto").reason,
    )
    val app = composeJar("app", "1.12.1")
    val noCompose = URLClassLoader(arrayOf(plainJar("bare-daemon").url()), null)
    assertEquals(
      "daemon-has-no-compose",
      AppComposeSelection.decide(listOf(app.url()), noCompose, "auto").reason,
    )
  }

  @Test
  fun unknownModeFallsBackToAuto() {
    val app = composeJar("app", "1.13.0")
    val d = AppComposeSelection.decide(listOf(app.url()), daemonLoader("1.12.1"), "prefer")
    assertEquals("auto", d.mode)
    assertTrue(d.preferApp)
    assertTrue(d.reason, d.reason.contains("unknown mode 'prefer'"))
  }

  @Test
  fun versionOrdering() {
    fun cmp(a: String, b: String) = Integer.signum(AppComposeSelection.compareVersions(a, b))
    assertEquals(1, cmp("1.13.0-alpha01", "1.12.1"))
    assertEquals(-1, cmp("1.13.0-alpha01", "1.13.0"))
    assertEquals(-1, cmp("1.13.0-alpha03", "1.13.0-beta01"))
    assertEquals(-1, cmp("1.13.0-beta01", "1.13.0-rc01"))
    assertEquals(1, cmp("1.13.0-SNAPSHOT", "1.13.0"))
    assertEquals(0, cmp("1.12.1", "1.12.1"))
    assertEquals(1, cmp("1.12.10", "1.12.9"))
  }

  /**
   * The loader Robolectric is handed answers `androidx/` (and the legacy `android/support/` classes
   * AndroidX jars ship) from the app first and everything else — Kotlin, coroutines, the app's own
   * classes — from the daemon, and never pretends to be a `URLClassLoader` (Robolectric would then
   * take its URLs as the whole sandbox classpath).
   */
  @Test
  fun appComposeFirstLoaderPrefersOnlyAndroidxResources() {
    val app = composeJar("app", "1.13.0", marker = "APP")
    val daemon = URLClassLoader(arrayOf(composeJar("daemon", "1.12.1", "DAEMON").url()), null)
    val loader = AppComposeFirstLoader(listOf(app.url()), daemon)

    assertFalse(URLClassLoader::class.java.isInstance(loader))
    assertTrue(
      loader.getResource(AppComposeSelection.COMPOSER_RESOURCE)!!.path.contains("/app.jar!")
    )
    assertEquals("APP", loader.getResource("androidx/compose/ui/R\$id.class")!!.text())
    assertEquals(
      "1.13.0",
      loader.getResource(AppComposeSelection.RUNTIME_VERSION_RESOURCE)!!.text(),
    )
    assertEquals("DAEMON", loader.getResource("kotlinx/coroutines/Job.class")!!.text())
    assertEquals("DAEMON", loader.getResource("com/example/app/MainKt.class")!!.text())
    // Only the app ships it (inside androidx.core); the sandbox must still find it.
    assertEquals(
      "APP",
      loader.getResource("android/support/v4/app/SupportActivity.class")!!.text(),
    )
    assertEquals(
      listOf("app.jar", "daemon.jar"),
      Collections.list(loader.getResources(AppComposeSelection.COMPOSER_RESOURCE)).map {
        it.path.substringBefore("!").substringAfterLast('/')
      },
    )
  }

  /**
   * The behaviour issue compose-ai-tools#6016 reports, pinned. An `androidx.` lookup goes to the
   * parent first, so whenever the daemon carries a class the app's copy is never consulted; only a
   * class the daemon lacks falls through to the child's own URLs (`URLClassLoader`'s fallback) —
   * which is how a newer app Compose ends up split across two loaders rather than run whole.
   */
  @Test
  fun childLoaderPrefersTheDaemonsAndroidxClasses() {
    assertTrue(UserClassLoaderHolder.mustDelegateToParent("androidx.compose.runtime.Composer"))
    assertTrue(
      UserClassLoaderHolder.mustDelegateToParent("androidx.compose.ui.platform.ComposeView")
    )
    val app = composeJar("app", "1.13.0").url()

    val daemonWithCompose = URLClassLoader(arrayOf(composeJar("daemon", "1.12.1").url()), null)
    val childOverCompose =
      UserClassLoaderHolder(urls = listOf(app), parentSupplier = { daemonWithCompose })
        .currentChildLoader()
    assertSame(
      daemonWithCompose,
      childOverCompose.loadClass("androidx.compose.runtime.Composer").classLoader,
    )

    val daemonWithout = URLClassLoader(arrayOf(plainJar("bare-daemon").url()), null)
    val childOverNothing =
      UserClassLoaderHolder(urls = listOf(app), parentSupplier = { daemonWithout })
        .currentChildLoader()
    assertSame(
      childOverNothing,
      childOverNothing.loadClass("androidx.compose.runtime.Composer").classLoader,
    )
  }
}
