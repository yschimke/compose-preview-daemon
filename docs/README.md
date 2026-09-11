# Documentation

- [`design/DAEMON_SPLIT.md`](design/DAEMON_SPLIT.md) — the extraction from compose-ai-tools: what
  moved, what stayed, the layer rule, and the consumer switch-over.
- [`design/EMBEDDING.md`](design/EMBEDDING.md) — what this repository should
  own when a caller wants to run a daemon, and what the caller owns. Argues the seam from what the
  two current consumers actually do today.
- [`daemon/README.md`](daemon/README.md) — the render daemon: design, protocol, classloaders, the
  sandbox pool, startup profile and tunables.
- [`daemon/ROBOLECTRIC-UPSTREAM-FEEDBACK.md`](daemon/ROBOLECTRIC-UPSTREAM-FEEDBACK.md) — maintained upstream feedback: friction, coupling, performance, bugs, APIs and simulator embedding.
- [`daemon/BOOT-ROADMAP.md`](daemon/BOOT-ROADMAP.md) — the ranked plan for a 2-3 s sandbox boot.
- [`DATA_PRODUCTS.md`](DATA_PRODUCTS.md) — the data products and the extractors under `data/`.
- [`RENDERER_COMPATIBILITY.md`](RENDERER_COMPATIBILITY.md) — renderer / consumer AndroidX alignment.
- [`DESKTOP_NATIVE_DEPS.md`](DESKTOP_NATIVE_DEPS.md) — Skiko and the desktop renderer's natives.

Documents that describe how the tools *drive* this daemon (the Gradle plugin, the CLI, the VS Code
extension, the preview server) stay with those repositories.
