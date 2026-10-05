package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.io.SystemFileSystem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Live `stream/start` subscribers and their per-stream state: dedup heartbeats, fps caps,
 * visibility throttling and sequence numbers. Used only by `JsonRpcServer`.
 */
internal class FrameStreamRegistry(
  private val clock: () -> Long = System::currentTimeMillis,
  fileSystem: FileSystem = SystemFileSystem,
  private val pngBytesReader: (String) -> ByteArray? = { path -> readPngBytes(path, fileSystem) },
  private val supportedCodecs: Set<StreamCodec> = setOf(StreamCodec.PNG),
) {

  /** Per-stream state; mutated from the server's reader thread or its render watcher. */
  internal data class State(
    val frameStreamId: String,
    val previewId: String,
    val codec: StreamCodec,
    val maxFps: Int?,
    // Volatile: written by the reader thread, read by each interactive frame loop through
    // [emitMinIntervalMs]. Nothing else orders a hide, so without it a loop could keep rendering
    // at full rate. The rest of this state stays on one thread.
    @Volatile var visible: Boolean = true,
    @Volatile var visibilityFps: Int? = null,
    var lastEmittedAtMs: Long = Long.MIN_VALUE,
    var lastHash: String? = null,
    var seq: Long = 0L,
    var keyframePending: Boolean = true,
  )

  private val states = ConcurrentHashMap<String, State>()
  private val nextStreamId = AtomicLong(1)

  /** Negotiate an emitting codec for a `stream/start` request. */
  fun negotiateCodec(requested: StreamCodec?): StreamCodec {
    if (requested != null && requested in supportedCodecs) return requested
    // Every renderer produces PNG, so it is the safe downgrade.
    if (StreamCodec.PNG in supportedCodecs) return StreamCodec.PNG
    return supportedCodecs.first()
  }

  fun mintStreamId(): String = "fstream-${nextStreamId.getAndIncrement()}"

  /** Records a subscriber, replacing any state under the same id, and returns its live [State]. */
  fun register(frameStreamId: String, previewId: String, codec: StreamCodec, maxFps: Int?): State {
    val state =
      State(frameStreamId = frameStreamId, previewId = previewId, codec = codec, maxFps = maxFps)
    states[frameStreamId] = state
    return state
  }

  /** Drop a subscriber. Idempotent: a stop on a stream that's already gone is a no-op. */
  fun unregister(frameStreamId: String): State? = states.remove(frameStreamId)

  /**
   * Applies `stream/visibility`; unknown ids are ignored (it can race `stream/stop`). Becoming
   * visible makes the next frame a keyframe and returns true, so the caller can wake a throttled
   * frame loop now rather than at its next slow tick.
   */
  fun setVisibility(frameStreamId: String, visible: Boolean, fps: Int?): Boolean {
    val s = states[frameStreamId] ?: return false
    val wasVisible = s.visible
    s.visible = visible
    s.visibilityFps = if (!visible) (fps ?: 1).coerceAtLeast(1) else null
    if (visible && !wasVisible) {
      s.keyframePending = true
      return true
    }
    return false
  }

  /**
   * The emit gate's current minimum interval for [frameStreamId] (`maxFps` and visibility
   * throttle); `0` when uncapped or unknown. The render loop honours it too, so a hidden stream is
   * not rendered at full rate only to have its frames dropped.
   */
  fun emitMinIntervalMs(frameStreamId: String): Long {
    val s = states[frameStreamId] ?: return 0L
    return effectiveMinIntervalMs(s)
  }

  /** Returns the snapshot view (used by tests). */
  internal fun stateOrNull(frameStreamId: String): State? = states[frameStreamId]

  /** True when at least one stream targets [previewId]. The server uses this to skip work. */
  fun hasStreamsFor(previewId: String): Boolean = states.values.any { it.previewId == previewId }

  /**
   * The `streamFrame` notifications for a render of [previewId]. Per stream: drop the frame if
   * inside the fps gate; send a payload-free heartbeat if [pngHash] is unchanged; otherwise send
   * the frame, as a keyframe if one is pending (which also overrides dedup).
   *
   * [pngPath] is read only when [pngBytes] is absent and some stream needs the bytes; all streams
   * share one copy.
   */
  fun consumeForPreview(
    previewId: String,
    pngPath: String?,
    pngHash: String?,
    widthPx: Int,
    heightPx: Int,
    pngBytes: ByteArray? = null,
  ): List<StreamFrameParams> {
    val targets = states.values.filter { it.previewId == previewId }
    if (targets.isEmpty()) return emptyList()
    val now = clock()
    val out = mutableListOf<StreamFrameParams>()
    var cachedBytes: ByteArray? = pngBytes
    for (s in targets) {
      val minIntervalMs = effectiveMinIntervalMs(s)
      if (minIntervalMs > 0 && s.lastEmittedAtMs != Long.MIN_VALUE) {
        if (now - s.lastEmittedAtMs < minIntervalMs) continue
      }
      val keyframe = s.keyframePending
      val isUnchanged = !keyframe && pngHash != null && s.lastHash == pngHash
      val seq = ++s.seq
      val params: StreamFrameParams =
        if (isUnchanged) {
          heartbeat(s, seq, now, widthPx, heightPx)
        } else {
          val bytes = cachedBytes ?: pngPath?.let(pngBytesReader)?.also { cachedBytes = it }
          // Unreadable bytes (file gone, stub host) degrade to a heartbeat.
          if (bytes == null) {
            heartbeat(s, seq, now, widthPx, heightPx)
          } else {
            StreamFrameParams(
              frameStreamId = s.frameStreamId,
              seq = seq,
              ptsMillis = now,
              widthPx = widthPx,
              heightPx = heightPx,
              codec = s.codec,
              keyframe = keyframe,
              final = false,
              payloadBase64 = base64(bytes),
            )
          }
        }
      s.lastEmittedAtMs = now
      if (pngHash != null) s.lastHash = pngHash
      if (params.codec != null) s.keyframePending = false
      out += params
    }
    return out
  }

  /** A `final` marker so the client can release decoder state; null for an unknown stream. */
  fun finalFrameOnStop(frameStreamId: String): StreamFrameParams? {
    val s = states[frameStreamId] ?: return null
    return StreamFrameParams(
      frameStreamId = frameStreamId,
      seq = ++s.seq,
      ptsMillis = clock(),
      widthPx = 0,
      heightPx = 0,
      codec = null,
      keyframe = false,
      final = true,
      payloadBase64 = null,
    )
  }

  /**
   * [consumeForPreview]'s gating for one externally produced, already-encoded frame (the XR render
   * service). Null when the stream is unknown or the fps gate dropped the frame.
   */
  fun consumeForStream(
    frameStreamId: String,
    payloadBase64: String?,
    widthPx: Int,
    heightPx: Int,
  ): StreamFrameParams? {
    val s = states[frameStreamId] ?: return null
    val now = clock()
    val minIntervalMs = effectiveMinIntervalMs(s)
    if (minIntervalMs > 0 && s.lastEmittedAtMs != Long.MIN_VALUE) {
      if (now - s.lastEmittedAtMs < minIntervalMs) return null
    }
    val hash = payloadBase64?.let(::sha256)
    val keyframe = s.keyframePending
    val isUnchanged = !keyframe && hash != null && s.lastHash == hash
    val seq = ++s.seq
    val params =
      if (isUnchanged || payloadBase64 == null) {
        heartbeat(s, seq, now, widthPx, heightPx)
      } else {
        StreamFrameParams(
          frameStreamId = s.frameStreamId,
          seq = seq,
          ptsMillis = now,
          widthPx = widthPx,
          heightPx = heightPx,
          codec = s.codec,
          keyframe = keyframe,
          final = false,
          payloadBase64 = payloadBase64,
        )
      }
    s.lastEmittedAtMs = now
    if (hash != null) s.lastHash = hash
    if (params.codec != null) s.keyframePending = false
    return params
  }

  /** A payload-free "no new pixels" frame. */
  private fun heartbeat(s: State, seq: Long, now: Long, widthPx: Int, heightPx: Int) =
    StreamFrameParams(
      frameStreamId = s.frameStreamId,
      seq = seq,
      ptsMillis = now,
      widthPx = widthPx,
      heightPx = heightPx,
      codec = null,
      keyframe = false,
      final = false,
      payloadBase64 = null,
    )

  private fun effectiveMinIntervalMs(s: State): Long {
    val visibilityCap = if (!s.visible) (s.visibilityFps ?: 1) else null
    val cap =
      when {
        visibilityCap != null && s.maxFps != null -> minOf(visibilityCap, s.maxFps)
        visibilityCap != null -> visibilityCap
        s.maxFps != null -> s.maxFps
        else -> return 0L
      }
    if (cap <= 0) return 0L
    return 1000L / cap
  }

  companion object {
    private fun readPngBytes(pngPath: String, fileSystem: FileSystem): ByteArray? {
      val path =
        try {
          pngPath.toPath()
        } catch (_: Throwable) {
          return null
        }
      if (!fileSystem.exists(path)) return null
      return try {
        fileSystem.read(path) { readByteArray() }
      } catch (_: Throwable) {
        null
      }
    }

    private fun base64(bytes: ByteArray): String =
      java.util.Base64.getEncoder().encodeToString(bytes)

    /** Content hash for dedup of already-encoded (e.g. XR) frame payloads. */
    private fun sha256(s: String): String {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      return java.util.Base64.getEncoder()
        .encodeToString(digest.digest(s.toByteArray(Charsets.UTF_8)))
    }
  }
}
