# Bouncy Castle startup experiments

`AndroidTestEnvironment` in Robolectric 4.17-beta-4 constructs a static
`BouncyCastleProvider` eagerly and registers it during setup, including when
Conscrypt is enabled. The original untuned roadmap profile attributed about
0.75 s to this work. The following measurements use JDK 17, C1 compilation,
method-handle threshold 30 and the guarded constant-binding experiment, with no
application CDS archive. They must not be subtracted from a separately measured
JDK 25/CDS result to claim combined readiness.

## Omission establishes a cost bound, not a candidate fix

The offline `OmitBouncyCastle.java` patch replaces construction with null and
skips only the corresponding registration. It pins the original class SHA-256
and requires exactly the expected three edits. The original Gradle cache and
production dependencies are unchanged. Explicit BC lookups and BC-only algorithms
can fail in this variant; matching render fixtures do not establish crypto parity.

Three rotated trials, each with RedSquare followed by six cycles of the 12-fixture
mixed workload, passed **438 exact PNG and UIA comparisons**.

| Median | Baseline | Omit BC |
| --- | ---: | ---: |
| Worker ready wall | 3794 ms | 3534 ms |
| Worker ready CPU | 5260 ms | 4910 ms |
| Total workload wall | 15547 ms | 15310 ms |
| Total workload CPU | 27490 ms | 26420 ms |
| Last 30 renders | 141.5 ms | 140 ms |

Omission saves **260 ms (6.9%)** to readiness in this tuned configuration.
This is an empirical cost bound for the tested workload, not proof that compatible
deferral can recover all of it. [Raw measurements](profiles/omit-bc-matrix.json).

Reproduce preparation (after preparing the guarded constant-binding classpath):

```sh
python scripts/experiments/prepare-omit-bc.py \
  --classpath daemon/android/build/frozen-repro-pinned/constant-classpath.txt \
  --jdk /usr/lib/jvm/java-17-openjdk \
  --output /tmp/omit-bc
```

Use the generated `matrix.json` with `benchmark-worker-matrix.py`, three trials,
72 renders, and the same 12 fixtures listed in
[the frozen-binding experiment](BOOT-FROZEN-BINDINGS-EXPERIMENT.md).
Rebuilding the patch in a second directory produced byte-identical jars.

## Concurrent construction

A second offline patch changes the private static provider field to a future.
Its factory starts construction of the original, unmodified Bouncy Castle provider
on the common pool. At the original field read, setup joins the future and casts
the result to the real provider before the unchanged `Security.addProvider` call.
Registration still precedes resource/application setup. The experiment does not
replace the provider with a proxy or leave registration out.

This can overlap constructor/configuration work between class initialization and
registration. It cannot overlap work after registration, because crypto behavior
in application setup must see the provider. New failure wrapping from `join`,
executor scheduling, and classloader lifetime need evaluation before adoption.
The factory runs from the sandbox's copied Robolectric jar and the patch pins the
same original class bytes as the omission experiment.

```sh
python scripts/experiments/prepare-async-bc.py \
  --classpath daemon/android/build/frozen-repro-pinned/constant-classpath.txt \
  --jdk /usr/lib/jvm/java-17-openjdk \
  --output /tmp/async-bc
```

Three rotated overlap/baseline trials passed another **438 exact PNG/UIA
comparisons**. These are a separate batch from omission; compare variants within
their own batch rather than treating baseline drift as an optimization.

| Median | Baseline | Concurrent BC |
| --- | ---: | ---: |
| Worker ready wall | 3861 ms | 3738 ms |
| Worker ready CPU | 5390 ms | 5460 ms |
| Total workload wall | 16023 ms | 16247 ms |
| Total workload CPU | 28380 ms | 28430 ms |
| Last 30 renders | 143.5 ms | 144.5 ms |

Readiness improves **123 ms (3.2%)**, but total workload wall time is **224 ms
(1.4%) slower** and CPU is effectively unchanged. This is a tentative startup
improvement, not evidence of an overall performance win. It recovers only part
of the omission cost bound, as expected when registration still waits for the
provider. [Raw measurements](profiles/async-bc-matrix.json).

## Provider validation

`BouncyCastleProviderSquare` runs through the real worker protocol and requires the
actual `BouncyCastleProvider` class to be installed before composable invocation.
It checks the known SHA-256 digest of `abc`, an explicit BC
`AES/ECB/PKCS7Padding` encryption/decryption round trip with a fixed test key,
and cipher/provider identity. The square is green only after all assertions pass.
This cipher mode is a deterministic test operation, not an application encryption
recommendation.

Both baseline and concurrent variants passed three crypto frames with exact
PNG/UIA parity and independent solid-green checks, under `-Xverify:all`. Both also
passed configure/classloader-swap, throwing-composable recovery, and graceful
shutdown. The omission variant failed at the null BC-provider lookup in the same
fixture, confirming that the check detects missing registration.
[Recorded checks](profiles/bc-crypto-validation.json).

Run `benchmark-worker-startup.py` with the appropriate prepared classpath,
`--fixture BouncyCastleProviderSquare --renders 3 --exercise-recovery`, the two
compiler-tuning JVM arguments, and `--jvm-arg=-Xverify:all`. Omission is an expected
negative-control failure and does not reach recovery.

This verifies the tested JCA routes, not every algorithm, provider mutation,
construction failure, application-onCreate path or concurrent sandbox lifecycle.
Production behavior and dependencies remain unchanged. Further work should test
this overlap under trained CDS, measure the time actually spent waiting at
registration, and verify application-time crypto before considering adoption.

## Current-main dependency validation

Main upgraded Robolectric to 4.17 and contracts to 2.16.0 while these probes were
being developed. The released `AndroidTestEnvironment.class` has the same pinned
SHA-256 (`330d3fd538e82e0d1321da943f1e3f8f48af3bd82debfb3259be1b3d069f8c48`),
and its sandbox jar is also byte-identical to the previously pinned jar. Preparation
now locates the module independently of the version directory and retains the
exact byte checks. Both binding invalidation verifiers pass on the released jar.
The refreshed build uses Gradle 9.7.1 and preserves production dependency ownership.

Conscrypt-OFF setup with an existing BC provider remains an explicit compatibility
case: the registration branch can be skipped, leaving asynchronous construction
unjoined. A production implementation needs to define failure propagation and
completion there, not merely pass the default Conscrypt-ON path.

Three fresh rotated trials on current main add the BC fixture to each cycle
(13 fixtures, 78 renders after RedSquare). All **474 PNG/UIA artifacts** match,
and all **36 crypto frames** independently have the expected solid-green pixels.

| Median, current main | Baseline | Concurrent BC |
| --- | ---: | ---: |
| Worker ready wall | 3864 ms | 3654 ms |
| Worker ready CPU | 5380 ms | 5360 ms |
| Total workload wall | 16789 ms | 16398 ms |
| Total workload CPU | 29260 ms | 29680 ms |
| Last 30 renders | 139 ms | 136.5 ms |

The **210 ms (5.4%)** readiness saving replicates the direction of the first
batch. Total workload wall improves by 391 ms (2.3%) in this batch, whereas the
first batch was 224 ms slower. CPU rises by 420 ms (1.4%). Retain both batches:
startup is consistently promising, but small sustained differences are not a
settled result. The extra crypto fixture changes the workload, so compare within
each batch. [Current-main measurements](profiles/async-bc-current-matrix.json).

The refreshed baseline/concurrent workers also pass `-Xverify:all`, the BC fixture,
configure/swap/error recovery and graceful shutdown. Refreshed omission still
fails at the missing-provider check. [Current validation](profiles/bc-current-crypto-validation.json).
Both `checkDependencyOwnership` and `checkHttpServerFloor` pass on the follow-up
branch; no production dependency policy is relaxed.
