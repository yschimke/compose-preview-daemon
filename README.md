# compose-preview-daemon

The renderers, the data extractors and the render daemon behind
[compose-ai-tools](https://github.com/yschimke/compose-ai-tools) and
[compose-preview-server](https://github.com/yschimke/compose-preview-server): everything that
executes a composable outside an IDE and returns pixels and data products.

| Directory | What |
| --- | --- |
| `renderers/` | `renderer-android` (Robolectric), `renderer-desktop` (Compose Multiplatform / Skiko), `renderer-xr-client` |
| `data/` | Data products: a `core` model per product, a `connector` that extracts it off a render |
| `daemon/` | `core` (JSON-RPC server, host contract), `android`, `desktop` (the hosts), `client`, `harness` |
| `api/` | `preview-annotations`, `preview-data-api` |
| `runtimes/` | The small runtime libraries previews compile against (slots, Lottie, SVG) |
| `distribution/` | The sidecar archives releases ship (`compose-preview-android-daemon-<v>.zip`, `compose-preview-desktop-daemon-<v>.tar.gz`) |

Every module publishes to Maven Central under `ee.schimke.composeai` at the release tag.

```
./gradlew test jvmTest desktopTest checkKotlinAbi --continue      # what CI runs
./gradlew :distribution:packageAndroidDaemon :distribution:packageDesktopDaemon
./gradlew ktfmtFormatAll
```

Start with [`AGENTS.md`](AGENTS.md) for the rules and [`docs/README.md`](docs/README.md) for the
map; [`docs/design/DAEMON_SPLIT.md`](docs/design/DAEMON_SPLIT.md) is where this repository came
from and why.
