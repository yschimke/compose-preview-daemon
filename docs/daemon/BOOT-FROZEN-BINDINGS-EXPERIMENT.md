# Frozen shadow bindings experiment

This is an offline, opt-in bytecode experiment against Robolectric 4.17-beta-4.
Production dependencies and launch settings are unchanged. It measures how much
Robolectric's support for changing shadow maps costs a worker whose map stays fixed.

`InvokeDynamicSupport.bindWithFallback` normally constructs a fallback invoker,
folds in a rebind operation and guards the target with an invalidation switch point.
The experimental replacement adapts the target to the call-site type and installs
it directly. It retains the existing stack-trace cleanup wrapper. The `constant`
variant additionally returns `ConstantCallSite` from the three method/init
bootstraps; the `frozen` variant retains mutable call sites.

Both variants mark the sandbox's `ShadowInvalidator` frozen on binding. Nonempty
invalidation after that point throws `IllegalStateException`, including changes to
unrelated shadow names. Initial invalidation, empty invalidation and independent
sandboxes remain allowed. This is a conservative diagnostic guard, not support for
concurrent shadow-map mutation or a replacement for Robolectric's normal lifecycle.
Do not use these jars for general Robolectric tests, map switching, or production.

The patcher requires the exact measured sandbox SHA-256
`afd922c4b79db9b38d6eba6bd3e3e8ff2c4b01d5baf43c1178455cbfacaecc1e`
and checks the expected method counts. It copies the jar to a new build directory;
it never modifies the Gradle cache. The reflection verifier runs with `-Xverify:all`
and checks initial/empty invalidation, rejection after binding and instance isolation.
The worker matrix separately exercises the patched bootstraps during real rendering.

## Reproduce

From the repository root, with JDK 17 available:

```sh
./gradlew :daemon:android:writeDaemonClasspath --max-workers=4
python3 scripts/experiments/prepare-frozen-bindings.py \
  --classpath daemon/android/build/daemon-harness/runtime-classpath.txt \
  --jdk /usr/lib/jvm/java-17-openjdk \
  --output daemon/android/build/frozen-experiment
python3 scripts/benchmark-worker-matrix.py \
  --matrix daemon/android/build/frozen-experiment/matrix.json \
  --classpath daemon/android/build/daemon-harness/runtime-classpath.txt \
  --java /usr/lib/jvm/java-17-openjdk/bin/java \
  --output daemon/android/build/frozen-experiment-results \
  --trials 3 --renders 72 \
  --fixture MaterialButtonInteractionState --fixture SerifTextPreview \
  --fixture DialogWindowSurface --fixture OpaqueImageSquare \
  --fixture GradientBackgroundCard --fixture RadialGradientBackgroundCard \
  --fixture EmojiAndAnnotatedText --fixture GraphicsLayerAndWideVector \
  --fixture IconButtonRowInputBar --fixture LazyColumnListPreview \
  --fixture EditableTextFieldSquare --fixture GenericOutlineShapeSquare
```

Each worker renders RedSquare followed by six cycles through the twelve fixtures.
All variants hold C1-only compilation and MethodHandle compilation threshold 30
fixed, as investigated in [the JVM experiment](BOOT-METHOD-HANDLE-EXPERIMENT.md).
Workers run sequentially with variant order rotated across trials. Readiness is
measured from launching a fresh JVM; CPU time is whole-process CPU. Total time
includes readiness and all 73 render requests. PNG bytes and UIA hierarchy hashes
must match the baseline at each frame index.

## Limits and next steps

These fixtures cover text, emoji, dialogs, images, gradients, clipping, lists and
editable-field rendering. They do not establish correctness of interaction replay,
application changes, sandbox replacement, or exceptions in user composables.
Before production integration, establish that the worker's complete lifecycle never
needs a bound class to change its shadow and exercise those paths. A source-level
Robolectric opt-in would be preferable to shipping patched dependency jars.

## Measured results

JDK 17.0.19, three rotated trials, 657 total frames. Every PNG byte hash and
UIA hierarchy hash matched. Values below are medians across the three workers
per variant; render latency is the median of each worker’s last 30 renders.

| Variant | Ready wall ms | Ready CPU ms | Render ms | Total wall ms | Total CPU ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| baseline | 4006 | 5660.0 | 160.5 | 17549 | 35460.0 |
| frozen | 3777 | 5250.0 | 142.0 | 15698 | 28010.0 |
| constant | 3744 | 5130.0 | 140.5 | 15520 | 27410.0 |

Constant bindings reduced median readiness by 262 ms (6.5%), total wall time
by 2,029 ms (11.6%) and total CPU by 8,050 ms (22.7%). Frozen mutable bindings
captured most of the gain. The constant-versus-frozen readiness difference was
only 33 ms, too small to treat as independently established with three trials.

[Raw per-worker measurements](profiles/frozen-bindings-matrix.json) retain all
trials. This confirms a useful optimization direction, not a proven minimum boot
time or sufficient evidence to change production defaults.
