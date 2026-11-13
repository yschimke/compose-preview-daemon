package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDwfRoutingTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @After
  fun tearDown() {
    System.clearProperty(BundleIrReplayStore.BUNDLE_MANIFEST_PATH_PROP)
    System.clearProperty(BundleIrReplayStore.IR_DIR_PROP)
    BundleIrReplayStore.resetForTest()
  }

  @Test
  fun `CarriedDocumentClock starts at baseEpochMillis and advances with frame timestamps`() {
    val base = DwfDocumentSeed.DEFAULT_CLOCK_EPOCH_MILLIS
    val clock = CarriedDocumentClock(baseEpochMillis = base)
    assertEquals(base, clock.elapsedMillis())

    clock.onFrame(1_000_000_000L)
    assertEquals(base, clock.elapsedMillis())

    clock.onFrame(16_000_000_000L)
    assertEquals(base + 15_000L, clock.elapsedMillis())
  }

  @Test
  fun `PreviewManifestRouter and renderSpecFromInfo seed DWF rc document and preserve it across namedValues overrides`() {
    val dir = tempFolder.newFolder("dwf-manifest")
    val rcBytes = byteArrayOf(10, 20, 30, 40)
    val rcFile = dir.resolve("dwf-renders/dwf__SimpleAnalog.rc")
    rcFile.parentFile.mkdirs()
    rcFile.writeBytes(rcBytes)

    val manifest = dir.resolve("previews.json")
    manifest.writeText(
      """
      {
        "previews": [
          {
            "id": "dwf__SimpleAnalog",
            "functionName": "SimpleAnalog",
            "className": "",
            "params": {
              "device": "id:wearos_large_round",
              "widthDp": 450,
              "heightDp": 450,
              "density": 1.0,
              "showBackground": false,
              "dwfAssetPath": "raw/watchface.xml"
            },
            "captures": [
              { "renderOutput": "dwf-renders/dwf__SimpleAnalog.png" }
            ]
          }
        ]
      }
      """
        .trimIndent(),
    )

    val router =
      PreviewManifestRouter(PreviewManifestRouter.loadManifest(manifest), manifestDir = dir)
    val routed =
      router.routeTarget(
        RenderTarget.Preview(
          previewId = "dwf__SimpleAnalog",
          overrides =
            PreviewOverrides(
              remoteCompose =
                RemoteComposeOverride.Builder()
                  .also {
                    it.namedValues =
                      mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("9800"))
                  }
                  .build(),
            ),
        ),
      ) as RenderTarget.Spec

    val overrides = routed.spec.overrides
    assertNotNull(overrides)
    assertEquals(DwfDocumentSeed.DEFAULT_CLOCK_EPOCH_MILLIS, overrides!!.clockEpochMillis)
    assertEquals(CarriedRemoteCompose.CMP_JVM, overrides.remoteCompose?.playerId)
    assertArrayEquals(rcBytes, Base64.getDecoder().decode(overrides.remoteCompose?.documentBase64))
    assertEquals(
      mapOf("dwf.complication.1.text" to RemoteNamedValue.StringValue("9800")),
      overrides.remoteCompose?.namedValues,
    )

    val infoSpec =
      renderSpecFromInfo(
        info =
          PreviewInfoDto(
            id = "dwf__SimpleAnalog",
            className = "",
            methodName = "SimpleAnalog",
            params =
              PreviewParamsDto(
                device = "id:wearos_large_round",
                widthDp = 450,
                heightDp = 450,
                density = 1.0f,
                dwfAssetPath = "raw/watchface.xml",
              ),
            captures =
              listOf(PreviewCaptureDto(renderOutput = "dwf-renders/dwf__SimpleAnalog.png")),
          ),
        manifestDir = dir,
      )
    assertEquals(DwfDocumentSeed.DEFAULT_CLOCK_EPOCH_MILLIS, infoSpec.overrides?.clockEpochMillis)
    assertEquals(CarriedRemoteCompose.CMP_JVM, infoSpec.overrides?.remoteCompose?.playerId)
    assertArrayEquals(
      rcBytes,
      Base64.getDecoder().decode(infoSpec.overrides?.remoteCompose?.documentBase64),
    )
  }
}
