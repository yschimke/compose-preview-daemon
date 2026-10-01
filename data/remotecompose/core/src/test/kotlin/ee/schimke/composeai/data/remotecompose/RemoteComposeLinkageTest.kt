package ee.schimke.composeai.data.remotecompose

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteComposeLinkage] against real drift: a caller compiled against one version of a library,
 * loaded beside another — the shape of yschimke/wear-m3-catalog#652, where a bundle's code met an
 * older `remote-creation-compose` than it was built against.
 */
class RemoteComposeLinkageTest {
  private val compiler = ToolProvider.getSystemJavaCompiler()

  private val lib =
    """
    package lib;
    public class Library {
      public static int CONSTANT = 1;
      public Library() {}
      public Library(String label) {}
      public int present() { return 1; }
      public int removed() { return 2; }
      public String toString() { return "Library"; }
    }
    """

  private val libWithoutRemoved =
    """
    package lib;
    public class Library {
      public static int CONSTANT = 1;
      public Library() {}
      public int present() { return 1; }
      public String toString() { return "Library"; }
    }
    """

  private val caller =
    """
    package app;
    public class Caller {
      public int call() {
        lib.Library library = new lib.Library();
        new lib.Library("label");
        return library.present() + library.removed() + lib.Library.CONSTANT + library.hashCode();
      }
    }
    """

  @Test
  fun `a caller against the library it was built with links`() {
    val loader = loaderFor(callerBuiltAgainst = lib, runningAgainst = lib)
    val report = check(loader)
    assertTrue(report.toString(), report.isLinked)
    assertEquals(1, report.classes)
  }

  @Test
  fun `a member the running library lacks is named, and nothing else is`() {
    val report = check(loaderFor(callerBuiltAgainst = lib, runningAgainst = libWithoutRemoved))
    assertEquals(
      listOf("lib/Library.<init>(Ljava/lang/String;)V", "lib/Library.removed()I"),
      report.missing,
    )
  }

  @Test
  fun `a library that is absent altogether is named by class`() {
    val report = check(loaderFor(callerBuiltAgainst = lib, runningAgainst = null))
    assertEquals(listOf("lib/Library (class not found)"), report.missing)
  }

  @Test
  fun `references outside the library packages are not checked`() {
    val loader = loaderFor(callerBuiltAgainst = lib, runningAgainst = libWithoutRemoved)
    val report =
      RemoteComposeLinkage.check(
        Class.forName("app.Caller", false, loader),
        listOf("app"),
        libraryPackages = listOf("other."),
      )
    assertTrue(report.isLinked)
    assertEquals(0, report.references)
  }

  private fun check(loader: ClassLoader): RemoteComposeLinkageReport =
    RemoteComposeLinkage.check(
      Class.forName("app.Caller", false, loader),
      listOf("app"),
      libraryPackages = listOf("lib."),
    )

  /** `app.Caller` compiled against [callerBuiltAgainst], in a loader holding [runningAgainst]. */
  private fun loaderFor(callerBuiltAgainst: String, runningAgainst: String?): ClassLoader {
    val built = compile(mapOf("lib/Library.java" to callerBuiltAgainst))
    val app = compile(mapOf("app/Caller.java" to caller), classpath = built)
    val runtime = runningAgainst?.let { compile(mapOf("lib/Library.java" to it)) }
    val urls = listOfNotNull(app, runtime).map { it.toURI().toURL() }.toTypedArray()
    return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
  }

  private fun compile(sources: Map<String, String>, classpath: File? = null): File {
    val src = Files.createTempDirectory("linkage-src").toFile()
    val out = Files.createTempDirectory("linkage-out").toFile()
    val files = sources.map { (path, text) ->
      File(src, path).apply {
        parentFile.mkdirs()
        writeText(text.trimIndent())
      }
    }
    val args =
      listOfNotNull("-d", out.path) +
        (classpath?.let { listOf("-classpath", it.path) } ?: emptyList()) +
        files.map { it.path }
    check(compiler.run(null, null, null, *args.toTypedArray()) == 0) { "compile failed: $sources" }
    return out
  }
}
