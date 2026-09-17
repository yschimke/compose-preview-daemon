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
// ## Which classpaths get moved, and why the default is `runtime`
//
//   -PcomposeNextScope=runtime   (default) only `*RuntimeClasspath`
//   -PcomposeNextScope=all                 every configuration, compile classpaths included
//
// `runtime` is the shape a consumer actually produces, and it is the only one that tests what
// ships. The renderer's Compose is `compileOnly`, so the published AAR's bytecode is *linked*
// against the older line and then *executes* against whatever the consumer resolved. That is a
// binary-compatibility question — `AbstractMethodError`, `NoSuchMethodError` — and the only way to
// ask it is to leave the compile classpaths alone. Forcing them too recompiles everything against
// the new line, which asks a different and much weaker question: whether the *sources* still build.
//
// `all` is still worth running, before adopting a newer line as the compile target rather than
// merely tolerating it at runtime. It catches source-level breaks that `runtime` cannot see.
//
// ## Trusting a green run
//
// A green `runtime` run means nothing unless the force actually reaches the test JVM, so verify the
// harness is sensitive before believing it: drop `material3` from the `when` below, leaving the
// other four forced, and the suite must go red with
//
//   AbstractMethodError: … androidx.compose.material3.OutlinedTextFieldDefaults$$Lambda … does not
//   define or inherit an implementation of the resolved method 'abstract void
//   applyStyle(androidx.compose.foundation.style.CustomStyleScope)'
//
// which is the original incident, reproduced on demand. Results and that control are recorded in
// `docs/RENDERER_COMPATIBILITY.md` → "Verifying the renderer on the next Compose line". This file
// is a probe, not part of any build: nothing references it, and CI does not run it.

val composeVersion = "1.12.1"

// The material3 line built against Compose 1.12.0-beta01, forward-compatible to 1.12.1 (a patch
// bump inside one minor). material3 stable is still 1.4.0, which targets an older Compose, so an
// alpha is the only coherent pairing for the 1.12 line.
val material3Version = "1.5.0-alpha27"

// An init script's receiver is `Gradle`, not `Project`, so the `-P` value is read off the start
// parameters rather than through `findProperty`.
val scope = startParameter.projectProperties["composeNextScope"] ?: "runtime"
require(scope == "runtime" || scope == "all") {
  "composeNextScope must be 'runtime' or 'all', was '$scope'"
}

allprojects {
  configurations.configureEach {
    if (scope == "runtime" && !name.endsWith("RuntimeClasspath")) return@configureEach
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
