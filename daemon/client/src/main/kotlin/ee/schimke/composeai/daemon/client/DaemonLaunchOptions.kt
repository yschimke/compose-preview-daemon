package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.config.DaemonProperties
import kotlin.time.Duration

/**
 * The knobs a caller sets when launching a daemon, as fields rather than as strings.
 *
 * ### What this is for
 *
 * `DaemonProperties` made the `composeai.daemon.*` namespace enumerable, typed and renameable —
 * `DaemonPropertyRegistryTest` fails if a bare `"composeai.daemon.…"` literal survives anywhere in
 * `daemon/`. That guarantee stopped at the repository boundary. Outside it, at the time of writing,
 * compose-ai-tools spells 62 of those names as string literals and compose-preview-server spells 7,
 * so renaming a knob is a compile error in one repository and a silent behaviour change in two.
 *
 * Fifty of those fifty-one names are already *declared* in the registry. The namespace was never
 * the problem; the problem is that the registry only offered a way to **read**. This is the writing
 * half, so a launcher references a field and the encoding comes from the property that will parse
 * it back — see [DaemonProperty.render][ee.schimke.composeai.daemon.config.DaemonProperty.render].
 *
 * ### What is deliberately not here
 *
 * - **`composeai.daemon.bta.*`** — the seven in-process-compile inputs. `daemon/bta-host` stayed in
 *   compose-ai-tools when this repository was extracted (DAEMON_SPLIT.md), so the compile inputs
 *   are a tools concern that happens to travel on our namespace. They go through [extra].
 * - **`protocolVersion`** — the daemon states it, a caller does not choose it.
 * - **Everything else in the registry.** This type covers what a launcher sets, not all 53 knobs. A
 *   knob that turns out to belong here gains a field; until then [extra] carries it, keyed on
 *   `DaemonProperties.Names`, which is still a rename-safe reference rather than a literal.
 *
 * ### Null means "leave it to the daemon"
 *
 * Every field defaults to null or empty, and a null field emits **no property at all** rather than
 * an empty one. That distinction is load-bearing: the daemon's own default applies when the
 * property is absent, while an empty string is a value the parser may accept. A caller says "unset"
 * by leaving the field alone, which is also the negative rule in EMBEDDING.md — a default here is a
 * suggestion the caller overrides, never a policy the caller inherits.
 */
public class DaemonLaunchOptions(
  // ── What to render, and where it lives ─────────────────────────────────────────────────────
  /** `previews.json` the daemon reads its preview index from. */
  public val previewsJsonPath: String? = null,
  /** Directories the child-first classloader serves the user's classes from. */
  public val userClassDirs: List<String> = emptyList(),
  /** A packed bundle's manifest, for a bundle-backed daemon. */
  public val bundleManifestPath: String? = null,
  /** Where compiler IR artifacts are staged. */
  public val irDir: String? = null,

  // ── Which workspace this daemon serves ─────────────────────────────────────────────────────
  public val workspaceRoot: String? = null,
  public val modulePath: String? = null,
  public val moduleProjectDir: String? = null,

  // ── Resources and lifetime ─────────────────────────────────────────────────────────────────
  /**
   * Sandboxes this daemon hosts, itself plus one worker JVM per slot beyond the first (Robolectric
   * permits exactly one sandbox per process). Each extra slot costs a whole JVM.
   */
  public val sandboxCount: Int? = null,
  public val maxHeapMb: Int? = null,
  /** Idle time before the daemon exits on its own. */
  public val idleTimeout: Duration? = null,
  public val maxRendersPerSandbox: Int? = null,
  public val historyDir: String? = null,

  // ── Boot and tracing ───────────────────────────────────────────────────────────────────────
  public val backgroundSandboxBoot: Boolean? = null,
  public val warmRenderOnBoot: Boolean? = null,
  public val warmSpare: Boolean? = null,
  public val perfettoTrace: Boolean? = null,
  /** Files whose mtime the daemon may treat as a cheap change signal. */
  public val cheapSignalFiles: List<String> = emptyList(),

  /**
   * Anything without a field yet.
   *
   * Key these on `DaemonProperties.Names.*` rather than on a literal — that keeps a rename a
   * compile error, which is the whole point of this type. A raw string works and is not rejected,
   * because refusing it would only push callers back to editing the `systemProperties` map by hand.
   */
  public val extra: Map<String, String> = emptyMap(),
) {

  /**
   * The `-D` map for a
   * [DaemonLaunchDescriptor][ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor].
   *
   * Absent fields are absent from the result. [extra] is applied last, so a caller can override a
   * typed field through it — deliberate, since the escape hatch would be useless if the typed half
   * outranked it.
   */
  public fun toSystemProperties(): Map<String, String> = buildMap {
    val p = DaemonProperties
    previewsJsonPath?.let { put(p.previewsJsonPath.name, p.previewsJsonPath.render(it)) }
    if (userClassDirs.isNotEmpty()) {
      put(p.userClassDirs.name, p.userClassDirs.render(userClassDirs))
    }
    bundleManifestPath?.let { put(p.bundleManifestPath.name, p.bundleManifestPath.render(it)) }
    irDir?.let { put(p.irDir.name, p.irDir.render(it)) }

    workspaceRoot?.let { put(p.workspaceRoot.name, p.workspaceRoot.render(it)) }
    modulePath?.let { put(p.modulePath.name, p.modulePath.render(it)) }
    moduleProjectDir?.let { put(p.moduleProjectDir.name, p.moduleProjectDir.render(it)) }

    sandboxCount?.let { put(p.sandboxCount.name, p.sandboxCount.render(it)) }
    maxHeapMb?.let { put(p.maxHeapMb.name, p.maxHeapMb.render(it)) }
    idleTimeout?.let { put(p.idleTimeoutMs.name, p.idleTimeoutMs.render(it.inWholeMilliseconds)) }
    maxRendersPerSandbox?.let {
      put(p.maxRendersPerSandbox.name, p.maxRendersPerSandbox.render(it))
    }
    historyDir?.let { put(p.historyDir.name, p.historyDir.render(it)) }

    backgroundSandboxBoot?.let {
      put(p.backgroundSandboxBoot.name, p.backgroundSandboxBoot.render(it))
    }
    warmRenderOnBoot?.let { put(p.warmRenderOnBoot.name, p.warmRenderOnBoot.render(it)) }
    warmSpare?.let { put(p.warmSpare.name, p.warmSpare.render(it)) }
    perfettoTrace?.let { put(p.perfettoTrace.name, p.perfettoTrace.render(it)) }
    if (cheapSignalFiles.isNotEmpty()) {
      put(p.cheapSignalFiles.name, p.cheapSignalFiles.render(cheapSignalFiles))
    }

    putAll(extra)
  }
}
