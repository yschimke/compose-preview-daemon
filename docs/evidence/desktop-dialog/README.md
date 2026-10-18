# Desktop dialog settle regression (#160)

Reproduced on main `2da42ee2` (3.18.8-SNAPSHOT), JDK 17.0.20.1, Kotlin 2.4.21.
The desktop sources are unchanged from released daemon **3.18.6**:
`git diff v3.18.6..2da42ee2 -- daemon/desktop renderers/desktop` is empty.

`RenderEngineDialogSettleTest` renders the real Material `AlertDialog` on both
Compose Multiplatform 1.11.1 / Material 3 1.11.0-alpha07 and the forward runtime,
Compose 1.12.0-rc01 / Material 3 1.12.0-alpha03. The latter Material version matches
the original report, which used daemon 3.8.3.

| Capture | Opaque green container pixels (both runtimes) |
| --- | ---: |
| No settle metadata (`unsettled.png`) | 0 |
| Explicit 80 ms (`early.png`) | 0 |
| Explicit 1000 ms (`exact.png`) | 62,828 |
| Auto settle, bounded at 1000 ms (`auto.png`) | 62,828 |

Images here are the forward runtime captures, at 412×915 px, density 1.
The assertions preserve explicit phase capture while requiring the auto-settled
surface to match an exact late phase. Both runtimes run in CI: the production
`test` task automatically finalizes `forwardComposeInteractionExportTest`, which
now includes this fixture. These tests require no source or baseline regeneration.

Run:

```sh
scripts/agent-gradle.sh :daemon:desktop:test --tests '*RenderEngineDialogSettleTest*'
```

Current daemon rendering is correct **when the manifest carries a settle**. The
unannotated two-frame path still reproduces the dim dialog. The generated-preview
caller in tools loses explicit `@SettledPreview` and does not request settling for
ordinary generated entries; its fix belongs in `PlaygroundPreviews`. This PR adds
regression coverage rather than changing every daemon still to settle, which would
change explicit animation/recording behavior.

No daemon production change, API change or release is required for that tools fix.
Tools already pins released 3.18.6. Release tools, wait for non-draft release and
Maven Central availability, then bump the published tools `render-host` coordinate
in the server in a separate PR. No downstream SNAPSHOT or unreleased pin is needed.

All upstream workarounds remain: EDT/RectList handling, library pairing and Compose
compatibility constraints. The report's dark-theme mismatch is a separate caller
concern and is not claimed fixed by this known-color fixture.
