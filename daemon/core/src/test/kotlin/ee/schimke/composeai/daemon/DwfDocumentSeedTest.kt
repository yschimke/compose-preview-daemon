package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DwfDocumentSeedTest {

  @After
  fun tearDown() {
    System.clearProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP)
    System.clearProperty(BundleIrReplayStore.IR_DIR_PROP)
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun `non-dwf preview returns baked overrides untouched`() {
    val baked = PreviewOverrides(clockEpochMillis = 1234L)
    assertEquals(
      baked,
      DwfDocumentSeed.apply(
        previewId = "foo",
        dwfAssetPath = null,
        defaultPlayerId = "cmp-android",
        bakedOverrides = baked,
      ),
    )
    assertNull(
      DwfDocumentSeed.apply(
        previewId = "foo",
        dwfAssetPath = "",
        defaultPlayerId = "cmp-jvm",
        bakedOverrides = null,
      ),
    )
  }

  @Test
  fun `resolves sibling rc from declared renderOutput and seeds default clock and player`() {
    val dir = Files.createTempDirectory("dwf-seed-sibling").toFile()
    try {
      val rcBytes = byteArrayOf(1, 2, 3, 4, 5)
      val rcFile = dir.resolve("dwf-renders/dwf__SimpleAnalog.rc")
      rcFile.parentFile.mkdirs()
      rcFile.writeBytes(rcBytes)

      val seeded =
        DwfDocumentSeed.apply(
          previewId = "dwf__SimpleAnalog",
          dwfAssetPath = "src/main/res/raw/watchface.xml",
          defaultPlayerId = "cmp-android",
          manifestDir = dir,
          declaredRenderOutput = "dwf-renders/dwf__SimpleAnalog.png",
        )
      assertNotNull(seeded)
      assertEquals(DwfDocumentSeed.DEFAULT_CLOCK_EPOCH_MILLIS, seeded!!.clockEpochMillis)
      assertEquals("cmp-android", seeded.remoteCompose?.playerId)
      assertArrayEquals(rcBytes, Base64.getDecoder().decode(seeded.remoteCompose?.documentBase64))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `prefers BundleIrReplayStore over disk and layers baked overrides on top`() {
    val dir = Files.createTempDirectory("dwf-seed-bundle").toFile()
    try {
      val irDir = dir.resolve("ir").apply { mkdirs() }
      val bundleBytes = byteArrayOf(9, 8, 7)
      irDir.resolve("dwf__Weather.rc").writeBytes(bundleBytes)
      val bundleJson = dir.resolve("bundle.json")
      bundleJson.writeText(
        """
        {
          "intermediateRepresentations": [
            {
              "previewId": "dwf__Weather",
              "format": "remotecompose",
              "path": "ir/dwf__Weather.rc"
            }
          ]
        }
        """
          .trimIndent(),
      )
      System.setProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP, bundleJson.absolutePath)
      System.setProperty(BundleIrReplayStore.IR_DIR_PROP, irDir.absolutePath)
      BundleIrReplayStore.resetForTest()

      val diskRc = dir.resolve("dwf-renders/dwf__Weather.rc")
      diskRc.parentFile.mkdirs()
      diskRc.writeBytes(byteArrayOf(0, 0, 0))

      val baked =
        PreviewOverrides(
          clockEpochMillis = 1_700_000_000_000L,
          remoteCompose =
            RemoteComposeOverride.Builder()
              .also {
                it.namedValues =
                  mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("22°"))
              }
              .build(),
        )

      val seeded =
        DwfDocumentSeed.apply(
          previewId = "dwf__Weather",
          dwfAssetPath = "src/main/res/raw/watchface.xml",
          defaultPlayerId = "cmp-jvm",
          bakedOverrides = baked,
          manifestDir = dir,
          declaredRenderOutput = "dwf-renders/dwf__Weather.png",
        )
      assertNotNull(seeded)
      assertEquals(1_700_000_000_000L, seeded!!.clockEpochMillis)
      assertEquals("cmp-jvm", seeded.remoteCompose?.playerId)
      assertEquals(
        mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("22°")),
        seeded.remoteCompose?.namedValues,
      )
      assertArrayEquals(
        bundleBytes,
        Base64.getDecoder().decode(seeded.remoteCompose?.documentBase64),
      )
    } finally {
      dir.deleteRecursively()
    }
  }
}
