package ee.schimke.composeai.daemon

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional `ffmpeg` subprocess encoder for MP4 (H.264) and WEBM (VP9) recordings; see RECORDING.md,
 * "encoded formats". APNG is the always-available pure-JVM default; when `ffmpeg` is missing the
 * host simply does not advertise these formats. The subprocess is killed on timeout.
 */
public object FfmpegEncoder {

  /** Encode timeout; a failure is better surfaced than a half-written file. */
  public const val ENCODE_TIMEOUT_MS: Long = 60_000L

  @Volatile private var detected: Boolean? = null

  /** Whether `ffmpeg -version` on `PATH` exits 0; probed once per JVM. */
  public fun available(): Boolean {
    val cached = detected
    if (cached != null) return cached
    val result =
      try {
        val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() } // drain so the subprocess exits cleanly
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          false
        } else {
          process.exitValue() == 0
        }
      } catch (_: Throwable) {
        false
      }
    detected = result
    return result
  }

  /** Test hook — clears the detection cache so the next [available] call re-probes. */
  internal fun resetDetectionForTesting() {
    detected = null
  }

  /**
   * Encodes `frame-NNNNN.png` (contiguous from 0) in [framesDir] to [out]. Throws on a missing
   * binary, non-zero exit, timeout or empty output.
   *
   * @param audioTrack optional audio (e.g. the TalkBack announcement track) muxed as a second
   *   input.
   */
  public fun encodeFromPngFrames(
    framesDir: File,
    fps: Int,
    format: RecordingFormatChoice,
    out: File,
    audioTrack: File? = null,
  ) {
    require(fps in 1..120) { "FfmpegEncoder: fps=$fps out of range [1, 120]" }
    require(framesDir.isDirectory) {
      "FfmpegEncoder: framesDir does not exist or is not a directory: ${framesDir.absolutePath}"
    }
    require(audioTrack == null || audioTrack.isFile) {
      "FfmpegEncoder: audioTrack does not exist or is not a file: ${audioTrack?.absolutePath}"
    }
    if (!available()) {
      error("FfmpegEncoder: ffmpeg not found on PATH; install ffmpeg or use RecordingFormat.APNG")
    }

    out.parentFile?.mkdirs()
    if (out.exists()) out.delete()

    val args = buildArgs(framesDir, fps, format, out, audioTrack)

    val pb = ProcessBuilder(args).redirectErrorStream(true)
    val proc = pb.start()
    // Drain output concurrently: ffmpeg's progress chatter would otherwise fill the pipe and stall.
    val log = StringBuilder()
    val drainThread = Thread {
      try {
        proc.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            synchronized(log) {
              log.appendLine(line)
              if (log.length > MAX_LOG_BYTES) {
                log.delete(0, log.length - MAX_LOG_BYTES)
              }
            }
          }
        }
      } catch (_: Throwable) {
        // Process exited; drain done.
      }
    }
      .apply {
        isDaemon = true
        start()
      }

    val finished = proc.waitFor(ENCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    if (!finished) {
      proc.destroyForcibly()
      runCatching { drainThread.join(2_000) }
      error(
        "FfmpegEncoder: ffmpeg ${format.name.lowercase()} encode timed out after ${ENCODE_TIMEOUT_MS}ms"
      )
    }
    runCatching { drainThread.join(2_000) }
    val exit = proc.exitValue()
    if (exit != 0) {
      val tail = synchronized(log) { log.toString().takeLast(2_000) }
      error("FfmpegEncoder: ffmpeg ${format.name.lowercase()} exited with $exit; tail: $tail")
    }
    if (!out.isFile || out.length() == 0L) {
      error(
        "FfmpegEncoder: ffmpeg ${format.name.lowercase()} produced no output at ${out.absolutePath}"
      )
    }
  }

  /** The `ffmpeg` argv for [encodeFromPngFrames]; separate so it can be tested without ffmpeg. */
  internal fun buildArgs(
    framesDir: File,
    fps: Int,
    format: RecordingFormatChoice,
    out: File,
    audioTrack: File?,
  ): List<String> {
    val args = mutableListOf("ffmpeg", "-y", "-framerate", fps.toString())
    args.add("-i")
    args.add(File(framesDir, "frame-%05d.png").absolutePath)
    if (audioTrack != null) {
      args.add("-i")
      args.add(audioTrack.absolutePath)
    }
    when (format) {
      RecordingFormatChoice.MP4 -> {
        // yuv420p: mobile players and QuickTime reject libx264's yuv444 default. `+faststart`
        // lets an HTTP-served clip start playing before it has fully downloaded.
        args.addAll(
          listOf(
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-preset",
            "veryfast",
            "-movflags",
            "+faststart",
          )
        )
        if (audioTrack != null) args.addAll(listOf("-c:a", "aac"))
      }
      RecordingFormatChoice.WEBM -> {
        // `-deadline good -cpu-used 4` is the speed/quality middle ground, like x264 `veryfast`.
        args.addAll(
          listOf(
            "-c:v",
            "libvpx-vp9",
            "-pix_fmt",
            "yuv420p",
            "-deadline",
            "good",
            "-cpu-used",
            "4",
            "-row-mt",
            "1",
          )
        )
        if (audioTrack != null) args.addAll(listOf("-c:a", "libopus"))
      }
    }
    if (audioTrack != null) {
      // `apad` + `-shortest`: output is exactly the video's length whether the audio is short or
      // long, so a short TTS track never truncates the video.
      args.addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-af", "apad", "-shortest"))
    }
    args.add(out.absolutePath)
    return args
  }

  /** The [ee.schimke.composeai.daemon.protocol.RecordingFormat]s this encoder handles. */
  public enum class RecordingFormatChoice {
    MP4,
    WEBM,
  }

  private const val MAX_LOG_BYTES: Int = 16 * 1024
}
