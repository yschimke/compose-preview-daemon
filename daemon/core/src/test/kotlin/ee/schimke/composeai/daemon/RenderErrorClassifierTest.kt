package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderErrorClassifierTest {

  @Test
  fun androidxComposeOnDesktopIsClasspathSkewWithAClasspathSuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.NoSuchMethodError: Implemented only in JetBrains fork"
      )
    assertEquals(RenderErrorKind.CLASSPATH_SKEW, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("org.jetbrains.compose"))
  }

  @Test
  fun skikoBindingsAheadOfTheirNativeIsClasspathSkewWithASkikoSuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.UnsatisfiedLinkError: 'int org.jetbrains.skia.paragraph.ParagraphKt" +
          "._nGetUnresolvedCodepointsCount(long)'"
      )
    assertEquals(RenderErrorKind.CLASSPATH_SKEW, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("skiko-awt-runtime"))
  }

  @Test
  fun aMissingSkikoNativeIsClasspathSkew() {
    val c =
      RenderErrorClassifier.classify(
        "org.jetbrains.skiko.LibraryLoadException: Cannot find libskiko-linux-x64.so.sha256, " +
          "proper native dependency missing."
      )
    assertEquals(RenderErrorKind.CLASSPATH_SKEW, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("skiko-awt-runtime"))
  }

  @Test
  fun aSkikoNativeTheHostCannotLoadIsNotBlamedOnTheJars() {
    listOf(
        "org.jetbrains.skiko.LibraryLoadException: Failed to load libskiko\n" +
          "java.lang.UnsatisfiedLinkError: /tmp/skiko/libskiko-linux-x64.so: libGL.so.1: " +
          "cannot open shared object file: No such file or directory",
        "org.jetbrains.skiko.LibraryLoadException: Failed to load libskiko\n" +
          "java.lang.UnsatisfiedLinkError: /lib/x86_64-linux-gnu/libc.so.6: version " +
          "`GLIBC_2.34' not found (required by /tmp/skiko/libskiko-linux-x64.so)",
      )
      .forEach {
        assertTrue(it, RenderErrorClassifier.classify(it).kind != RenderErrorKind.CLASSPATH_SKEW)
      }
  }

  @Test
  fun anUnrelatedUnsatisfiedLinkErrorIsNotBlamedOnSkiko() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.UnsatisfiedLinkError: no sqlitejdbc in java.library.path"
      )
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
  }

  @Test
  fun newerSdkIsSdkMismatchWithAnSdkSuggestion() {
    val c =
      RenderErrorClassifier.classify("PackageParser: Requires newer sdk version #36 (current #35)")
    assertEquals(RenderErrorKind.SDK_MISMATCH, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("compileSdk"))
  }

  @Test
  fun capturePathFailureIsCapture() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.RuntimeException: captureRoboImage / PixelCopy failed"
      )
    assertEquals(RenderErrorKind.CAPTURE, c.kind)
    assertNotNull(c.suggestion)
  }

  @Test
  fun robolectricLockIsCaptureWithSandboxSuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.io.IOException: /home/user/.robolectric-download-lock: permission denied"
      )
    assertEquals(RenderErrorKind.CAPTURE, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains(".robolectric-download-lock"))
  }

  @Test
  fun missingComposableIsMissingComposableWithASuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.NoSuchMethodException: getDeclaredComposableMethod failed"
      )
    assertEquals(RenderErrorKind.MISSING_COMPOSABLE, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("@Composable"))
  }

  @Test
  fun requiredParameterIsUnsetParameterWithASuggestion() {
    val c =
      RenderErrorClassifier.classify(
        "java.lang.IllegalArgumentException: preview parameter 'user' has no value (missing " +
          "@PreviewParameter provider)"
      )
    assertEquals(RenderErrorKind.UNSET_PARAMETER, c.kind)
    assertTrue(c.suggestion, c.suggestion!!.contains("@PreviewParameter"))
  }

  @Test
  fun barePackageParserErrorDoesNotGetTheSdkSuggestion() {
    val c = RenderErrorClassifier.classify("PackageParser: Malformed AndroidManifest.xml")
    assertNull(
      "a non-SDK PackageParser error must not be masked with the compileSdk hint",
      c.suggestion,
    )
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
  }

  @Test
  fun hostInfraFailureStaysInternal() {
    val eof = RenderErrorClassifier.classify("java.io.EOFException: sandbox stdio closed")
    assertEquals(RenderErrorKind.INTERNAL, eof.kind)
    val ioClosed = RenderErrorClassifier.classify("java.io.IOException: Stream closed")
    assertEquals(RenderErrorKind.INTERNAL, ioClosed.kind)
  }

  @Test
  fun timeoutIsTimeout() {
    val c = RenderErrorClassifier.classify("java.util.concurrent.TimeoutException: timed out")
    assertEquals(RenderErrorKind.TIMEOUT, c.kind)
  }

  @Test
  fun unknownCompositionErrorDefaultsToRuntimeWithoutASuggestion() {
    val c = RenderErrorClassifier.classify(IllegalStateException("boom"))
    assertEquals(RenderErrorKind.RUNTIME, c.kind)
    assertNull(c.suggestion)
  }

  @Test
  fun walksTheCauseChainForSignatures() {
    val cause =
      RuntimeException(
        "wrapper",
        IllegalStateException("PackageParser: Requires newer sdk version"),
      )
    assertEquals(RenderErrorKind.SDK_MISMATCH, RenderErrorClassifier.classify(cause).kind)
    assertNotNull(RenderErrorClassifier.classify(cause).suggestion)
  }

  @Test
  fun renderFailureMessagePrefixesTheExceptionType() {
    assertEquals(
      "ClassNotFoundException: com.example.CatalogPreviewsKt",
      renderFailureMessage(ClassNotFoundException("com.example.CatalogPreviewsKt")),
    )
    assertEquals("IllegalStateException", renderFailureMessage(IllegalStateException()))
  }
}
