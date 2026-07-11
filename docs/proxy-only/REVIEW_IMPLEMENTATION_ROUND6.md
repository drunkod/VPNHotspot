# Implementation review, round 6 — R5 remediation

Reviewed source commit: `b6ce636db8deb3098c137eb118aefd419dc7ddd0`

Reviewed final branch head: `f14102b480a8272a106ef7ec36d13091fd841de9`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND5.md`

Scope: daemon-generation provenance, direct cleanup retry containment, retry self-cancel removal, zero-MAC rejection and interface-name filtering.

## Verdict

**Changes requested.** The branch improves stale-handle handling across different daemon generations, prevents retry self-cancellation, adds an ordinary-failure boundary around cleanup retries, rejects the zero MAC and filters invalid interface identities. However, the Kotlin scaffold has a source-level constructor mismatch, sanitation failure can leave an active listener without confirmed firewall containment, and handle provenance still tracks only daemon generation rather than the daemon-issued sanitation epoch.

The PR must remain a draft Phase 0 scaffold.

## Resolution audit

| Round-5 item | Status | Round-6 assessment |
| --- | --- | --- |
| Daemon-generation provenance | Partially fixed | Applied state and debt carry daemon generation, and different-generation handles are dominated after sanitation. Provenance does not carry sanitation epoch; unknown or same-generation pre-sanitation handles can still be submitted after a ledger reset. |
| Sanitation before debt retry | Partially fixed | Reconciliation sanitizes before normal debt retry. A sanitation `null` result does not stop/contain an already-running backend; an exception falls into recovery with no explicit sanitation-success token. |
| Retry transaction failure boundary | Improved | Ordinary transaction failures are caught and retries are rescheduled. The catch preserves the current global debt rather than an explicit pre-transaction snapshot, while auxiliary state may already have changed. |
| Persistent cleanup owner | Not proven | A separate scope is mandatory, but no production owner or lifecycle test exists. `serviceWasActivated` remains unused by retry decisions. |
| Retry self-cancel | Fixed in the shown scheduler | `retryJob` is cleared before the transaction, so scheduling the successor does not cancel the executing job. |
| Zero MAC | Fixed | `00:00:00:00:00:00` is rejected. |
| Interface validation | Partially fixed | Empty/oversized names are filtered, but `Char.isLetterOrDigit()` admits non-ASCII Unicode despite the ASCII contract; invalid identities are silently dropped. |
| Review preservation | Fixed at final head | The intermediate source commit deleted review files, but the final branch head restores them. |

## Blockers

### 1. The Kotlin scaffold does not compile

`CleanupDebt` now requires:

```kotlin
firewallHandleGeneration: Long?
```

but `ProxyServiceClient.kt::emergencyCloseListener()` still constructs `CleanupDebt` without that argument.

Required fix: add `firewallHandleGeneration = null` at that construction site and run:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

Search every `CleanupDebt(` call after model changes; constructor completeness must be enforced by compilation rather than inspection alone.

### 2. Sanitation failure can leave the old listener running

On daemon generation change, `reconcile()` calls `cleanOrDenyBeforeRestart()` before cleaning the applied runtime. When the call returns `null`, the controller creates only `daemonCleanPending` debt and returns. It does not stop the existing backend or emergency-close its listener.

Failure sequence:

1. proxy backend/listener is active;
2. daemon restarts and firewall state is absent or unknown;
3. sanitation returns `null`;
4. controller publishes cleanup-degraded state and schedules retry;
5. existing SOCKS listener remains active without confirmed kernel deny containment.

Required fix: on sanitation failure, immediately contain the service side under bounded cleanup—at minimum emergency-close the listener and retain the backend handle as service debt. Do not wait for a later sanitation retry while the listener may remain reachable.

If sanitation throws, `runIteration()` enters generic recovery. Recovery must not infer sanitation dominance from a mismatched or null local generation; it must retain old firewall obligations as `daemonCleanPending` and contain the listener.

Required tests:

- daemon restart while an active runtime exists and sanitation returns `null`;
- sanitation throws;
- listener is closed before the failure state is published;
- no allow state is reported until sanitation and a new deny-first runtime both succeed.

### 3. Daemon generation is not sufficient handle provenance

`cleanOrDenyBeforeRestart()` can issue a new ledger epoch while the daemon generation remains unchanged. A handle created before that sanitation has the same daemon generation but belongs to the old epoch.

Current code treats a handle as stale only when:

```kotlin
firewallHandleGeneration != sanitizedDaemonGeneration
```

This misses same-generation, old-epoch handles and unknown-generation handles. It also assigns provenance from controller state before/around `firewall.start()`, rather than receiving authoritative provenance in the daemon response.

Required fix:

- define `FirewallEpoch`/`FirewallHandle(epoch, id)` as one value;
- return the epoch with every start/replace acknowledgement;
- carry epoch in `AppliedProxyState` and cleanup debt;
- treat unknown provenance as requiring sanitation, never as safe for IPC;
- compare `(epoch, handle)` during debt merges;
- sanitation dominance resolves all older epochs, including epochs from the same daemon generation.

`mergeUnresolved()` currently compares handle IDs but does not reject equal IDs with conflicting provenance. Handle identity must include epoch.

### 4. The epoch/reset contract is still not implemented in daemon IPC/Rust

The Kotlin interface returns `Long?`, but `proxy_firewall.proto` contains only a configuration generation field. It has no sanitation/reset request, acknowledgement response, daemon epoch or handle provenance. The Rust build continues to compile only `daemon.proto`.

Required evidence:

- integrate proxy-firewall commands into the existing daemon envelope;
- add sanitation request and acknowledged epoch response;
- return epoch-qualified runtime handles;
- atomically install deny state and reset/reissue the ledger epoch;
- reject delayed commands from prior epochs;
- test process restart, daemon restart, same-daemon re-sanitation and overflow.

Until this exists, the Kotlin epoch API is a design placeholder rather than a verified security boundary.

### 5. Persistent cleanup ownership is still not wired

The controller requires a `cleanupScope`, but no production construction proves that it outlives the worker and remains attached to a live service/client transport. Cleanup debt is held only in the controller object.

`CleanupDebt.serviceWasActivated` is recorded but retry logic still checks mutable `serviceActivated`. Therefore the added field does not currently make retries independent of worker/controller state.

Required fix/evidence:

- provide a concrete service-owned `CleanupSupervisor` or equivalent;
- give it explicit start, handoff and final-teardown contracts;
- transfer debt atomically when the controller worker exits;
- use service-reported state/handles rather than a stale local Boolean;
- prove retries continue after worker cancellation and stop during final service teardown;
- prove no coroutine or controller leak.

## High-severity amendments

### 6. Sanitation dominance is inferred too broadly in `cleanupApplied()`

`firewallFromCurrentDaemon` is false when `sanitizedDaemonGeneration` is null. The method then marks the firewall handle resolved as if successful sanitation had dominated it. A null sanitation marker means “not proven sanitized,” not “safe to discard.”

Dominance must require an explicit successful sanitation epoch that is strictly newer than the handle epoch. Otherwise retain `daemonCleanPending` and never discard the obligation.

### 7. Interface validation contradicts its ASCII contract

`Char.isLetterOrDigit()` accepts Unicode letters and digits. Linux `IFNAMSIZ` is byte-based, while Kotlin `String.length` counts UTF-16 code units. A non-ASCII name can therefore pass the 15-character check while exceeding the intended byte contract.

Use an explicit ASCII matcher such as a project-approved subset of `[A-Za-z0-9_.:-]`, reject `.`/`..` if unsupported, and validate against the actual downstream interface set before firewall submission.

### 8. Invalid normalization input remains silent and downstream address selection is order-dependent

Invalid interfaces and clients are dropped without a visible diagnostic. `ManagedDownstream` still stores one nullable IPv4 and chooses the first valid observation.

Required amendment:

- expose non-secret normalization diagnostics;
- model a canonical address set, or reject multiple distinct valid addresses with a typed state;
- test reversed observation order;
- distinguish “no tethering” from “all tethering identities rejected.”

### 9. Retry transaction preservation is not a full rollback

The retry failure boundary retains `cleanupDebt`, but retry execution can mutate `serviceActivated`, `firewallGeneration` or `sanitizedDaemonGeneration` before a later invariant failure. The comment claims the pre-transaction state is preserved, but only debt is preserved.

Prefer returning a local transaction result that includes all state transitions and committing it once, or explicitly define which successful external effects are irreversible and reconcile them from authoritative service/daemon state after failure.

## Verified corrections

- attempt-all cleanup remains present;
- retry self-cancellation is removed in the current scheduler;
- different-daemon-generation handles are not intentionally submitted after successful sanitation;
- ordinary retry-transaction exceptions no longer silently kill the retry job;
- zero MAC is rejected;
- empty and oversized interface names are filtered;
- review history is present at the final branch head.

## Build and test status

- no GitHub status checks exist for the current head;
- no PR workflow runs or deterministic controller/normalization/daemon tests were added;
- source inspection identifies the missing `CleanupDebt` argument, so compilation cannot be approved without running the build.

## Required next sequence

1. Fix the `CleanupDebt` constructor mismatch and attach Kotlin compile/test/assemble evidence.
2. Contain the listener immediately on sanitation failure.
3. Replace daemon-generation-only provenance with daemon-issued epoch-qualified handles.
4. Implement sanitation/epoch/handle acknowledgements in proto and Rust.
5. Wire a real service-owned cleanup supervisor and lifecycle tests.
6. Harden ASCII interface validation and normalization diagnostics/address invariants.
7. Add deterministic tests for all R1–R6 failure sequences.
8. Only then continue with Hev/JNI and production firewall implementation.

## Approval boundary

Status after round 6: **changes requested**.

The direction remains valid, but the current branch is not build-verified and does not yet prove fail-closed behavior during sanitation failure or epoch changes.