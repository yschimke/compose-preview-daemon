// Forces this build's Compose stack onto a single, coherent newer line, so the renderer's own test
// suite can be run against it without editing the version catalog.
//
//   scripts/agent-gradle.sh --init-script scripts/experiments/compose-next.init.gradle.kts \
//     :renderer-android:testDebugUnitTest
//
// ## Why this exists
//
// The renderer declares Compose `compileOnly` on purpose — the consumer's versions win at runtime,
// which is mechanism 1 in `docs/RENDERER_COMPATIBILITY.md` — so "does the renderer still work on
// the Compose line our consumers are moving to" is a real question that the committed catalog
// cannot answer. It is also a question that gets asked the hard way: adding a library built against
// a newer Compose (`androidx.xr.glimmer`, say) drags `ui` / `foundation` / `runtime` forward by
// conflict resolution while `material3` stays on whatever the BOM pinned, and the resulting *skew*
// fails with `AbstractMethodError` deep inside an unrelated component. That failure says nothing
// about the newer Compose; it says the two halves disagree.
//
// So this script moves the whole stack together, which is the only comparison worth making. Keep
// [composeVersion] and [material3Version] a matched pair: read the candidate material3's POM and
// take the `foundation` version it declares.
//
//   curl -s https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/\
//     <version>/material3-<version>.pom | grep -A1 'foundation</artifactId>'
//
// Results are recorded in `docs/RENDERER_COMPATIBILITY.md` → "Verifying the renderer on the next
// Compose line". This file is a probe, not part of any build: nothing references it, and CI does
// not run it.

val composeVersion = "1.12.1"

// The material3 line built against Compose 1.12.0-beta01, forward-compatible to 1.12.1 (a patch
// bump inside one minor). material3 stable is still 1.4.0, which targets an older Compose, so an
// alpha is the only coherent pairing for the 1.12 line.
val material3Version = "1.5.0-alpha27"

allprojects {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      when (requested.group) {
        "androidx.compose.ui",
        "androidx.compose.foundation",
        "androidx.compose.runtime",
        "androidx.compose.animation",
        "androidx.compose.material" ->
          // `material-icons-core` / `-extended` were frozen at the 1.7.x line and never published a
          // 1.12, so forcing them is a 404. They keep whatever the graph resolves.
          if (!requested.name.startsWith("material-icons")) useVersion(composeVersion)

        "androidx.compose.material3" -> useVersion(material3Version)
      }
    }
  }
}
