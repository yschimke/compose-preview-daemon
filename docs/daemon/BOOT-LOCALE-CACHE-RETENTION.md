# Locale cache retention during application reloads

A static screen soak does not exercise unloading application classes. The reload
fixture in `benchmark.screens` asserts it is child-loaded and swaps after each
request. It exposed one additional live child loader per reload on both baseline
and candidate builds, including the unmodified/default JVM.

## Retaining owner

A live heap dump after 20 default-JVM reloads contains 21 child loaders. Inspection
with Apache NetBeans' heap parser (`org-netbeans-lib-profiler:RELEASE250`) finds this
strong path for an old application loader:

```
JNI global android.view.Surface class
  -> AndroidSandbox.SdkSandboxClassLoader
  -> classes / LocaleCompositionLocals class
  -> static resolutions
  -> ConcurrentHashMap.table / Node.key
  -> UserClassLoaderHolder.ChildFirstURLClassLoader
```

The key itself retains the loader, even when its resolution is `Absent`. The
`reportedFailures` set has the same lifetime problem for a broken classpath.
Another path retains the first child via AWT's `AppContext.mainAppContext` and its
`contextClassLoader`; this is a separate initial-loader reference, not the growing
locale-cache chain.

## Change

Use synchronized weak keys and weak resolution values. Weak keys alone are
insufficient: a successful resolution stores a reflective constructor and a
composition local, either of which can point back to its defining loader. Successful
bindings are consequently opportunistic and may resolve again after GC. The
`Absent` result is a singleton and stays cached while its loader is alive, preserving
the cheap repeated-render path on older Compose. Failure reporting also uses weak
keys and remains synchronized.

The regression test creates a disposable loader for both an absent API and a
real constructor defined by that loader, then checks collection after the test's
own strong references leave scope. Real-worker checks below are the stronger
end-to-end evidence; the test does not establish an indefinite memory bound.

## Reproduction

Build `:daemon:android:writeDaemonClasspath` and freeze the resulting classpath
before changing revisions. Use `benchmark-worker-startup.py` with the reload fixture,
`--class-name benchmark.screens.ReloadDashboardPreviewsKt`, the frozen fixture jar as
`--user-class-dir`, `--swap-every 1 --reuse-output --gc-checkpoint-every 10
--heap-histograms --heap-dump`, and dimensions 480 by 1200. The dump is written after
workload measurements and is excluded from their totals; checkpoint diagnostics
remain included. Heap dumps stay in ignored build outputs, not in the repository.

## Result

The updated default-JVM worker completes 60 dashboard renders using 60 distinct
application loader identities. Live child-loader counts at 0/10/20/30/40/50/60 are
**2/2/2/2/2/2/2**, versus **2/11/21/31** in the 30-render baseline control. All dense
PNG/UIA hashes match within and across the baseline and fixed runs. The final live
heap dump confirms only the initial AWT loader and the current application loader
remain; the old locale-cache strong path is gone.

[Checkpoint data](profiles/locale-cache-unloading.json) retains heap readings and
PSS, but no physical-memory saving is claimed from these different-length G1 runs.
This proves unloading for this reload fixture, not arbitrary application caches,
image-heavy screens or indefinite operation. Heap defaults and process recycling
policy are unchanged. The existing locale behavior tests and the disposable-loader
regression pass; no renderer drawing behavior is intentionally changed.
