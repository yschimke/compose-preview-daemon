@AGENTS.md

The file above is the repository's canonical agent instructions. Everything below is
Claude-Code-specific harness mechanics; if the two ever disagree, `AGENTS.md` wins.

## Claude Code

- **Subscribe to every PR you open**, on the same turn you open it (`subscribe_pr_activity`).
  Don't ask — tracking is the default. Stop on request with `unsubscribe_pr_activity`.
- **Keep a check-in scheduled while a PR you own is red or conflicted** (`send_later`, roughly an
  hour out). Re-arm silently when nothing changed; stop once the PR is merged or closed.
- **PR activity events on tracked PRs are not no-ops.** Push the fix when the change is clear and
  in scope, `AskUserQuestion` when it is ambiguous or architecturally significant, skip silently
  only for duplicates and echoes of your own comments.
- **The Android modules need an Android SDK** (`ANDROID_HOME`) and the Robolectric
  `android-all-instrumented` jar in `~/.m2`; a first `:daemon:android:test` downloads the latter.
