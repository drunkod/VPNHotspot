# Implementation review, round 3 — R2 remediation

Reviewed commit: `ac11cdf31858c32fb84513ea08c10c35eba6334a`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND2.md`

Scope: the single commit that claims to close all five round-2 blockers and five high-severity amendments.

## Verdict

**Changes requested.** The commit genuinely fixes the probe-failure return path, stale-handle severity, feature-stop ordering, the credential-provider boundary, and VPN-observation interface merging. However, the Kotlin scaffold is still not buildable as committed, cancellation/timeout semantics remain incorrect, and the firewall-generation and normalization claims are stronger than the implementation.

The branch must remain a draft Phase 0 development branch.

## Resolution audit

| Round-2 item | Status | Round-3 assessment |
| --- | --- | --- |
| B1 compile/signature mismatch | **Not fixed** | Call signatures were updated conceptually, but three `firewallConfig` calls use a positional argument after a named argument, which is invalid Kotlin. `ProxyServiceClient.kt` also uses `CancellationException` without importing it. |
| B2 feature-stop ordering | Fixed in controller logic | `feature_stop` is now gated on `serviceHandle == null && !listenerPending`. Deterministic tests are still required. |
| B3 cancellation propagation | **Partially fixed** | Ordinary `CancellationException` is rethrown, but `TimeoutCancellationException` is caught first and can absorb the outer transaction timeout as a step timeout. Debt retry also remains wrapped in `NonCancellable`. |
| B4 emergency debt overwrite | Partially fixed | Retry commits the primary update before merging emergency debt, so the direct overwrite is removed. `cleanupApplied` still mutates global debt internally and returns separate primary debt for callers to merge, so cleanup is not one atomic local transaction. |
| B5 stale handle treated as success | Fixed | `staleHandle` now carries a critical failure. |
| H6 reachable endpoints | Partially fixed | Loopback fallback was removed and `Running` carries per-downstream endpoints. Addresses remain unparsed/unvalidated strings; any non-null value is considered routable. `NoReachableDownstreamAddress` is declared but never published. |
| H7 restart-safe firewall generation | **Not fixed** | Resetting the process-local counter to `daemonGeneration shl 20` can reuse or move behind values already accepted by a surviving daemon with the same generation. No acknowledged epoch/sequence reset exists. |
| H8 credential hardening | Partially fixed | Credentials moved behind a provider and the secret container is no longer a data class. It still stores immutable `String` secrets and is embedded in a `ProxyBackendConfig` data class; lifetime/clearing semantics remain undefined. |
| H9 typed/canonical normalization | **Not fixed** | The implementation sorts and uses `distinctBy`, but does not parse typed MAC/IPv4 values. Duplicate downstreams and clients are discarded rather than merged, which can lose a valid address or ACL binding. |
| H10 VPN duplicate-property merge | Fixed for scaffold | Duplicate network-handle observations merge interface-name sets deterministically. A production selector should still fresh-read current `LinkProperties`. |

## Blockers

### 1. Source-level Kotlin compilation errors remain

The controller calls:

```kotlin
next.firewallConfig(denyAll = false, firewallGeneration)
next.firewallConfig(denyAll = true, firewallGeneration)
```

Kotlin does not allow a positional argument after a named argument. These must use:

```kotlin
next.firewallConfig(
    denyAll = false,
    generationCounter = firewallGeneration,
)
```

or use both arguments positionally.

Separately, `ProxyServiceClient.kt` catches `CancellationException` but imports only `TimeoutCancellationException`; it needs an explicit `kotlinx.coroutines.CancellationException` (or the intended equivalent) import.

Required evidence after correction:

- `./gradlew :mobile:compileDebugKotlin`
- `./gradlew :mobile:testDebugUnitTest`
- `./gradlew :mobile:assembleDebug`

### 2. Outer transaction timeouts can still be swallowed as cleanup-step timeouts

Both `CleanupAccumulator.stepSucceeded` and the controller's local `attempt`/`safeEmergencyClose` helpers catch `TimeoutCancellationException` before `CancellationException`.

`TimeoutCancellationException` is itself a cancellation exception. If the outer 30-second transaction timeout fires while a nested cleanup step is running, the inner helper can classify the outer timeout as an ordinary step failure and continue instead of aborting the transaction.

Required fix: use a timeout primitive that only converts its own timeout, such as `withTimeoutOrNull`, while allowing parent/outer cancellation to propagate, or otherwise track and distinguish the timeout owner. Catch ordering alone is insufficient for nested timeouts.

Also remove the full `NonCancellable` wrapper around the complete `RetryCleanupDebt` transaction. Reserve `NonCancellable` for bounded final cleanup operations only.

Required tests:

- outer transaction timeout during each cleanup step escapes to `runIteration` recovery;
- inner cleanup-step timeout creates debt but does not cancel the parent;
- parent job cancellation interrupts debt retry promptly;
- terminal finalization remains bounded.

### 3. Firewall generation is not restart-safe

The controller resets the sequence to:

```kotlin
AtomicLong(daemonGeneration shl 20)
```

when it sanitizes a daemon generation.

If the app process restarts while the same root daemon survives, the daemon generation may remain unchanged. The new controller then starts again at the same base, potentially reusing values lower than or equal to values already accepted by the daemon. A Boolean `cleanOrDenyBeforeRestart()` response does not prove that the daemon reset its generation ledger.

Required fix: make generation a daemon-issued epoch plus sequence, or have sanitation return an acknowledged new epoch/start sequence after atomically resetting the proxy firewall ledger. Do not derive ordering from a shifted generation number without an acknowledgement contract.

Required tests:

- app process restart while daemon survives;
- sanitation succeeds but previous sequence was above the new process base;
- stale replacement from the previous process is rejected;
- first replacement in the acknowledged new epoch is accepted.

### 4. Normalization drops duplicate data rather than merging it

Current downstream normalization sorts and then `distinctBy(interfaceName)`. If the first duplicate has no IPv4 address and a later duplicate has a valid address, the valid address is discarded and the controller can incorrectly enter `WaitingForTethering`.

Client normalization similarly `distinctBy(mac.uppercase())` after processing each entry independently. Two observations for the same MAC with different IPv4 bindings lose all bindings except the first entry's list. The retained MAC string is not canonicalized, so case-only changes can still churn equality and firewall input.

Required fix:

- introduce parsed/canonical MAC and IPv4 value types;
- group downstreams by canonical interface and merge validated addresses;
- group clients by canonical MAC and union validated IPv4 bindings;
- reject malformed, loopback, unspecified and otherwise unusable endpoint addresses;
- sort canonical byte representations only after merging.

Required tests:

- duplicate downstream: null address followed by valid address;
- duplicate client MAC with disjoint IPv4 lists;
- case-variant MAC observations;
- malformed, loopback and unspecified downstream addresses;
- semantically equivalent snapshots produce the same runtime key.

## Additional lifecycle findings

### 5. Cleanup still has split global commits

`cleanupApplied` builds primary debt, calls `mergeDebt(emergencyOutcomeDebt)` internally, and returns the primary debt for its caller to merge separately. This avoids the old overwrite but does not satisfy the stated single local transaction model. It also duplicates emergency failures between the primary accumulator and emergency debt.

Required fix: return one fully merged `CleanupOutcome` from `cleanupApplied` without mutating global `cleanupDebt`; the caller should commit global debt once.

### 6. Terminal debt retry cannot rely on the terminating controller scope

`terminalStopSafely` schedules debt retry through the controller scope. When called from the worker's `finally`, that scope is already cancelling/terminating, so the scheduled retry may never execute. Unresolved native or firewall cleanup therefore needs an external persistent owner/recovery path, not a job launched into the dying controller scope.

Required test: cancel the controller while backend/firewall stop fails and prove that cleanup remains owned and retried by a surviving component.

### 7. Credential-fetch cancellation is converted to an internal failure

The credential-provider call catches all `Throwable` and maps it to `InternalFailure("credential_fetch")`. Cancellation exceptions should be rethrown before ordinary provider failures are mapped.

## Items verified as corrected

- Every non-success probe decision returns before firewall allow rules or `Running`.
- `feature_stop` waits until the controller's service-handle and listener obligations are clear.
- Stale service handles are critical failures.
- `Running` no longer deliberately advertises loopback and now models multiple downstream endpoints.
- Credentials are fetched only immediately before backend start and are absent from `DesiredProxyState`.
- Duplicate VPN observations merge interface-name sets by network handle.

## Build and test status

- GitHub reports no status checks for `ac11cdf31858c32fb84513ea08c10c35eba6334a`.
- No deterministic controller/native/Rust tests were added in this commit.
- Build status is unverified; the source-level Kotlin errors above are sufficient to keep the commit unapprovable.

## Required next sequence

1. Fix the Kotlin import and named/positional argument errors.
2. Correct nested-timeout ownership and remove broad `NonCancellable` debt retry.
3. Replace generation derivation with an acknowledged daemon epoch/sequence contract.
4. Implement typed merge-based normalization and endpoint validation.
5. Make cleanup outcome commits atomic and provide a persistent owner for terminal cleanup debt.
6. Add deterministic tests for all R1–R3 failure sequences.
7. Run and record Kotlin compilation, unit tests and debug assembly.
8. Only after these gates pass, continue with Hev/JNI and root-firewall integration.

## Approval boundary

Status after round 3: **changes requested**.

The design direction remains valid, but commit `ac11cdf3` does not yet close all round-2 findings and must not be treated as a buildable or completed Phase 0 implementation.
