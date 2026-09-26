package ee.schimke.composeai.daemon.client

/**
 * The environment a daemon JVM (or a spare sandbox worker) is started with.
 *
 * A daemon is a child of whatever spawned it — a CLI, an IDE, a long-lived preview server — and by
 * default a child process receives its parent's entire environment. That environment belongs to the
 * parent: a server's operator tokens and credentials have no business in a JVM that renders catalog
 * or playground code. So daemons start with [Default], an [Allowlist] of the variables the JVM, the
 * renderers and the daemon itself read, and nothing else.
 *
 * Applied by [SubprocessDaemonClientFactory] and [SandboxSparePool] through
 * `ProcessBuilder.environment()`: the allowlisted names are kept, then [Allowlist.extra] is put on
 * top. [Inherit] restores the previous behaviour for a caller that needs the full environment.
 */
public sealed class ChildEnvironment {

  /**
   * The child's environment, given the [parent]'s. Pure; [SubprocessDaemonClientFactory] and
   * [SandboxSparePool] apply its result, and tests read it directly.
   */
  public abstract fun resolve(parent: Map<String, String>): Map<String, String>

  /** Pass the parent's environment through unchanged — the behaviour before this policy existed. */
  public data object Inherit : ChildEnvironment() {
    override fun resolve(parent: Map<String, String>): Map<String, String> = parent
  }

  /**
   * Keep only the parent variables named in [names] or starting with one of [prefixes], then add
   * [extra] (which wins over an inherited value of the same name).
   *
   * Names and prefixes match case-insensitively. Windows environment names are case-insensitive, so
   * `Path` there is `PATH`; on other platforms upper-case is the convention for every variable
   * listed here, and a lower-case duplicate is kept rather than silently dropped.
   *
   * @property names variables to keep by exact name. Defaults to [DEFAULT_NAMES].
   * @property prefixes variables to keep by prefix. Defaults to [DEFAULT_PREFIXES].
   * @property extra variables to set on the child regardless of the parent.
   */
  public data class Allowlist(
    val names: Set<String> = DEFAULT_NAMES,
    val prefixes: Set<String> = DEFAULT_PREFIXES,
    val extra: Map<String, String> = emptyMap(),
  ) : ChildEnvironment() {
    private val upperNames: Set<String> = names.mapTo(HashSet()) { it.uppercase() }
    private val upperPrefixes: List<String> = prefixes.map { it.uppercase() }

    /** This allowlist with [more] names kept as well. */
    public fun plusNames(vararg more: String): Allowlist = copy(names = names + more)

    /** This allowlist with [more] set on the child as well. */
    public fun plusExtra(more: Map<String, String>): Allowlist = copy(extra = extra + more)

    public fun keeps(name: String): Boolean {
      val upper = name.uppercase()
      return upper in upperNames || upperPrefixes.any { upper.startsWith(it) }
    }

    override fun resolve(parent: Map<String, String>): Map<String, String> =
      parent.filterKeys(::keeps) + extra
  }

  public companion object {
    /**
     * What a daemon reads from its environment, directly or through the JVM and the libraries it
     * loads:
     * - process basics: `PATH` (the daemon runs `git` for render provenance), `HOME`, `USER`,
     *   `LOGNAME`, `LANG`, `LANGUAGE`, `TZ`, `TMPDIR`;
     * - the JVM: `JAVA_HOME`, and `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` / `_JAVA_OPTIONS`, which
     *   a host uses to set `composeai.daemon.*` properties on every daemon;
     * - native loading: `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH` (recorded on render error sidecars,
     *   and which libskiko is found), `FONTCONFIG_FILE` / `FONTCONFIG_PATH`, `DISPLAY`,
     *   `WAYLAND_DISPLAY`, `XAUTHORITY`;
     * - caches and SDKs: `XDG_CACHE_HOME` (native runtime and XR material caches),
     *   `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_RUNTIME_DIR`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`,
     *   `ANDROID_USER_HOME`, `GRADLE_USER_HOME`;
     * - renderer tools: `PLAYWRIGHT_BROWSERS_PATH` (Figma SVG fidelity), `XR_COMPOSITE_BIN`,
     *   `XR_COMPOSITE_MATERIALS`;
     * - Windows process basics, without which a JVM there cannot start child processes or find its
     *   temp and profile directories.
     */
    public val DEFAULT_NAMES: Set<String> =
      setOf(
        "PATH",
        "HOME",
        "USER",
        "LOGNAME",
        "LANG",
        "LANGUAGE",
        "TZ",
        "TMPDIR",
        "JAVA_HOME",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        "LD_LIBRARY_PATH",
        "DYLD_LIBRARY_PATH",
        "FONTCONFIG_FILE",
        "FONTCONFIG_PATH",
        "DISPLAY",
        "WAYLAND_DISPLAY",
        "XAUTHORITY",
        "XDG_CACHE_HOME",
        "XDG_CONFIG_HOME",
        "XDG_DATA_HOME",
        "XDG_RUNTIME_DIR",
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
        "ANDROID_USER_HOME",
        "GRADLE_USER_HOME",
        "PLAYWRIGHT_BROWSERS_PATH",
        "XR_COMPOSITE_BIN",
        "XR_COMPOSITE_MATERIALS",
        // Windows.
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "PATHEXT",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "USERNAME",
        "APPDATA",
        "LOCALAPPDATA",
        "PROGRAMDATA",
        "PROGRAMFILES",
        "NUMBER_OF_PROCESSORS",
        "PROCESSOR_ARCHITECTURE",
      )

    /**
     * `LC_*` (locale categories), `COMPOSEAI_*` (this project's own knobs — render provenance's
     * `COMPOSEAI_AGENT_ID` / `COMPOSEAI_WORKTREE_ID`, `COMPOSEAI_FIGMA_FIDELITY`, …) and `SKIKO_*`
     * (the desktop renderer's graphics backend selection).
     */
    public val DEFAULT_PREFIXES: Set<String> = setOf("LC_", "COMPOSEAI_", "SKIKO_")

    /** What daemons and spare workers start with unless the caller says otherwise. */
    public val Default: ChildEnvironment = Allowlist()
  }
}

/** Replace [builder]'s environment with [policy]'s view of it. */
internal fun ProcessBuilder.applyEnvironment(policy: ChildEnvironment): ProcessBuilder = apply {
  if (policy is ChildEnvironment.Inherit) return@apply
  val env = environment()
  val resolved = policy.resolve(HashMap(env))
  env.clear()
  env.putAll(resolved)
}
