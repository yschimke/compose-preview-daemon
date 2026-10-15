package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue

/**
 * Shared shape for the two `composeai.daemon.appCompose` sandbox tests (one per JVM: Robolectric
 * caches one sandbox per process and `forkEvery = 1` gives each test class its own).
 *
 * The "app's Compose" is a copy of the daemon's own `androidx.compose.runtime` jar with two changes
 * a test can see from inside the sandbox: its version marker reads [APP_VERSION] (newer than any
 * real one), and it carries one extra class, [MARKER_CLASS], that the daemon's classpath does not.
 * A real newer Compose would differ in its bytecode instead; the bytes are deliberately otherwise
 * identical so the render half of the test exercises the loading arrangement, not a Compose
 * upgrade.
 */
internal object AppComposeTestSupport {

  const val APP_VERSION: String = "1.99.0-app"

  const val MARKER_CLASS: String = "androidx.compose.runtime.AppComposeMarker"

  class Observed(
    val composerSource: String,
    val markerLoaderType: String,
    val pngMostlyRed: Double,
    val appJar: File,
  )

  /** Boots a host over the fixtures + an app Compose jar, dumps forensics, renders `RedSquare`. */
  fun bootRenderAndProbe(outputRoot: File): Observed {
    val appJar = appComposeJar(Files.createTempDirectory("app-compose").toFile())
    val fixtureDir = stageFixtureClasses(Files.createTempDirectory("app-compose-user").toFile())
    // The Bazel/bundle lane shape: the app's own classes and its runtime jars on the child loader.
    System.setProperty(
      UserClassLoaderHolder.USER_CLASS_DIRS_PROP,
      listOf(fixtureDir, appJar).joinToString(File.pathSeparator) { it.absolutePath },
    )
    val renders = File(outputRoot, "renders").apply { mkdirs() }
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, renders.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val holder =
      UserClassLoaderHolder(
        urls = UserClassLoaderHolder.urlsFromSysprop(),
        parentSupplier = {
          DaemonHostBridge.currentSandboxClassLoader() ?: error("sandbox prologue did not run")
        },
      )
    val host = RobolectricHost(userClassloaderHolder = holder)
    host.start()
    try {
      val dump = File(outputRoot, "forensics.json")
      host.submit(
        RenderRequest.Render(
          target =
            RenderTarget.Forensic(
              outPath = dump.absolutePath,
              survey = listOf("androidx.compose.runtime.Composer", MARKER_CLASS),
            )
        ),
        timeoutMs = 180_000,
      )
      val classes =
        Json.parseToJsonElement(dump.readText()).jsonObject["classes"]!!.jsonArray.associate {
          it.jsonObject["fqn"]!!.jsonPrimitive.content to it.jsonObject
        }
      val result =
        host.submit(
          RenderRequest.Render(
            target =
              RenderTarget.Spec(
                RenderSpec(
                  className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                  functionName = "RedSquare",
                  widthPx = 64,
                  heightPx = 64,
                  density = 1.0f,
                  showBackground = true,
                  outputBaseName = "red-square",
                )
              )
          ),
          timeoutMs = 120_000,
        )
      val png = File(result.artifact.pathOrNull() ?: error("render reported no PNG: $result"))
      assertTrue("rendered PNG must exist: $png", png.isFile)
      return Observed(
        composerSource =
          classes.getValue("androidx.compose.runtime.Composer").string("bytecodeSource"),
        markerLoaderType = classes.getValue(MARKER_CLASS).loaderType(),
        pngMostlyRed = redFraction(png),
        appJar = appJar,
      )
    } finally {
      host.shutdown()
    }
  }

  private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

  private fun JsonObject.loaderType(): String =
    this["classloader"]?.jsonObject?.string("type") ?: "error: ${string("error")}"

  private fun redFraction(png: File): Double {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }!!
    var match = 0
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val rgb = img.getRGB(x, y)
      val r = (rgb shr 16) and 0xFF
      val g = (rgb shr 8) and 0xFF
      val b = rgb and 0xFF
      if (
        kotlin.math.abs(r - 0xEF) <= 8 &&
          kotlin.math.abs(g - 0x53) <= 8 &&
          kotlin.math.abs(b - 0x50) <= 8
      ) {
        match++
      }
    }
    return match.toDouble() / (img.width * img.height)
  }

  /** The daemon's runtime jar, re-versioned to [APP_VERSION] and carrying [MARKER_CLASS]. */
  private fun appComposeJar(dir: File): File {
    val source =
      File(
        androidx.compose.runtime.Composer::class.java.protectionDomain.codeSource.location.toURI()
      )
    require(source.isFile) {
      "expected the Compose runtime as a jar on the test classpath: $source"
    }
    val out = File(dir, "app-compose-runtime.jar")
    JarFile(source).use { jar ->
      JarOutputStream(out.outputStream()).use { dst ->
        for (entry in jar.entries()) {
          if (entry.name == AppComposeSelection.RUNTIME_VERSION_RESOURCE) continue
          dst.putNextEntry(JarEntry(entry.name))
          if (!entry.isDirectory) jar.getInputStream(entry).use { it.copyTo(dst) }
          dst.closeEntry()
        }
        dst.putNextEntry(JarEntry(AppComposeSelection.RUNTIME_VERSION_RESOURCE))
        dst.write(APP_VERSION.toByteArray())
        dst.closeEntry()
        dst.putNextEntry(JarEntry(MARKER_CLASS.replace('.', '/') + ".class"))
        dst.write(emptyClass(MARKER_CLASS.replace('.', '/')))
        dst.closeEntry()
      }
    }
    return out
  }

  /** A minimal valid `public class [internalName] extends Object` with no members. */
  private fun emptyClass(internalName: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
      out.writeInt(0xCAFEBABE.toInt())
      out.writeShort(0)
      out.writeShort(52)
      out.writeShort(5)
      out.writeByte(1)
      out.writeUTF(internalName)
      out.writeByte(7)
      out.writeShort(1)
      out.writeByte(1)
      out.writeUTF("java/lang/Object")
      out.writeByte(7)
      out.writeShort(3)
      out.writeShort(0x0021)
      out.writeShort(2)
      out.writeShort(4)
      repeat(4) { out.writeShort(0) }
    }
    return bytes.toByteArray()
  }

  /**
   * The testFixtures classes, copied so the child loader serves them
   * (ClassloaderForensicsDaemonTest).
   */
  private fun stageFixtureClasses(dir: File): File {
    val resource = "ee/schimke/composeai/daemon/RedFixturePreviewsKt.class"
    val url =
      AppComposeTestSupport::class.java.classLoader!!.getResource(resource)
        ?: error("testFixtures class not on the test classpath: $resource")
    if (url.protocol == "file") {
      var root = File(url.toURI()).parentFile
      repeat(4) { root = root.parentFile }
      root.copyRecursively(dir, overwrite = true)
    } else {
      val jar = File(url.toString().removePrefix("jar:file:").substringBefore("!"))
      JarFile(jar).use { j ->
        for (e in j.entries()) {
          if (e.isDirectory || !e.name.startsWith("ee/schimke/composeai/daemon/")) continue
          val f = File(dir, e.name).apply { parentFile.mkdirs() }
          j.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }
        }
      }
    }
    return dir
  }
}
