package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.Base64
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Seeds a Declarative Watch Face (DWF) preview's base [PreviewOverrides] with its compiled Remote
 * Compose (`.rc`) document, backend default player, and deterministic watch-face preview epoch.
 *
 * A DWF preview (`params.dwfAssetPath != null`) has no Kotlin `@Composable` entrypoint (`className
 * = "android.watchface.XmlWatchFace"`): the Gradle plugin's `RenderDwfPreviewsTask` compiles
 * `watchface.xml` to a sibling `.rc` Remote Compose document (`dwf-renders/<id>.rc`), and bundles
 * package it under `ir/<id>.rc` ([BundleIrReplayStore]). Seeding the `.rc` document into the
 * preview's base [PreviewOverrides] lets both daemons render and stream DWF sessions — one-shot
 * `renderNow`, `interactive/start`, `stream/start`, and `recording/start` — through their carried
 * Remote Compose players (`cmp-android` on Android, `cmp-jvm` on Desktop) without loading the
 * synthetic `XmlWatchFace` class, while preserving per-key `overrides.remoteCompose.namedValues`
 * (complications, user-style themes, ambient state) and `overrides.clockEpochMillis` overrides via
 * [layeredOver] and [mergePreviewOverrides].
 */
public object DwfDocumentSeed {

  /**
   * Default watch-face preview epoch: `2026-11-18T10:10:30Z` (Wednesday 10:10:30 UTC), matching
   * `DwfRenderMain.DEFAULT_PREVIEW_TIME_MILLIS` so live and one-shot daemon renders open on the
   * exact clock hands/digits the static bake captured unless `overrides.clockEpochMillis` is set.
   */
  public const val DEFAULT_CLOCK_EPOCH_MILLIS: Long = 1_763_460_630_000L

  /**
   * Layers [bakedOverrides] over the DWF document + clock seed for [previewId], or returns
   * [bakedOverrides] unchanged when [dwfAssetPath] is null/blank or no `.rc` document is reachable.
   */
  public fun apply(
    previewId: String,
    dwfAssetPath: String?,
    defaultPlayerId: String,
    bakedOverrides: PreviewOverrides? = null,
    manifestDir: File? = null,
    declaredRenderOutput: String? = null,
    fileSystem: FileSystem = SystemFileSystem,
  ): PreviewOverrides? {
    if (dwfAssetPath.isNullOrBlank()) return bakedOverrides
    val rcBytes =
      resolveDocumentBytes(
        previewId = previewId,
        manifestDir = manifestDir,
        declaredRenderOutput = declaredRenderOutput,
        fileSystem = fileSystem,
      ) ?: return bakedOverrides
    val seed =
      PreviewOverrides(
        clockEpochMillis = DEFAULT_CLOCK_EPOCH_MILLIS,
        remoteCompose =
          RemoteComposeOverride.Builder()
            .also { b ->
              b.documentBase64 = Base64.getEncoder().encodeToString(rcBytes)
              b.playerId = defaultPlayerId
            }
            .build(),
      )
    return bakedOverrides.layeredOver(seed)
  }

  /**
   * Resolves [previewId]'s `.rc` bytes from [BundleIrReplayStore] first (bundle daemon / `serve`),
   * then from disk relative to [manifestDir]: the sibling `.rc` of [declaredRenderOutput]
   * (`dwf-renders/<id>.png` → `dwf-renders/<id>.rc`), `dwf-renders/<previewId>.rc`, and
   * `ir/<previewId>.rc`.
   */
  internal fun resolveDocumentBytes(
    previewId: String,
    manifestDir: File?,
    declaredRenderOutput: String?,
    fileSystem: FileSystem = SystemFileSystem,
  ): ByteArray? {
    BundleIrReplayStore.lookup(previewId)
      ?.takeIf { it.format == BundleIrReplayStore.FORMAT_REMOTECOMPOSE }
      ?.bytes
      ?.let {
        return it
      }
    val dir = manifestDir ?: return null
    val candidates = buildList {
      val out = declaredRenderOutput?.takeIf { it.isNotBlank() }
      if (out != null) {
        add(DeclaredRenderOutput.resolve(dir, siblingRcPath(out)))
      }
      add(DeclaredRenderOutput.resolve(dir, "dwf-renders/$previewId.rc"))
      add(DeclaredRenderOutput.resolve(dir, "ir/$previewId.rc"))
    }
    for (candidate in candidates) {
      val path = candidate.path.toPath()
      if (fileSystem.exists(path)) {
        return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
      }
    }
    return null
  }

  private fun siblingRcPath(renderOutput: String): String {
    val leafStart = renderOutput.lastIndexOf('/') + 1
    val dot = renderOutput.lastIndexOf('.')
    return if (dot > leafStart) renderOutput.substring(0, dot) + ".rc" else "$renderOutput.rc"
  }
}
