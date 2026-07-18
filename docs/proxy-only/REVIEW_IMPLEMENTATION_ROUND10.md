# Implementation review, round 10 — R9 remediation and CI follow-up

Reviewed source commit: `94dbb30ccc1188dc743c2e6ab915ee9616688d09`

Current branch head at review start: `8957bc6683dee56465de6067a73169a0cf3f0edc`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND9.md`

Scope: service-handle conflict teardown, primary sanitation generation capture, final deny-to-allow provenance check, conflict failure deduplication, selector compilation and current CI status.

## Verdict

**Changes requested.** The three specifically described R9 controller changes are present and the current branch passes the main `Test` workflow, including the Gradle build-and-test step. The selector compilation failure was fixed by replacing the nonexistent `LinkProperties.allInterfaceNames` reference with the public nullable `interfaceName` property while retaining duplicate-observation merging.

However, authoritative service teardown can currently be followed by stale emergency debt being merged back into controller state, and firewall-handle IPC is still not guarded against a newly observed daemon generation on the fast path or during cleanup. The daemon session/epoch protocol and persistent cleanup owner also remain unimplemented scaffolding.

The PR must remain a draft Phase 0 branch.

## Resolution audit

| Round-9 item | Status | Round-10 assessment |
| --- | --- | --- |
| Service-handle conflict deadlock | Partially fixed | Conflicting concrete handles are discarded and full service teardown becomes reachable. A successful teardown can nevertheless be followed by stale emergency debt being reintroduced. |
| Primary sanitation generation race | Fixed at controller level | The expected generation is captured before sanitation IPC and the acknowledgement is discarded when the latest observed generation changes. |
| Generation check before final startup allow | Fixed at controller level | The generation is re-read after probes and startup is aborted before deny-to-allow replacement when it changed. |
| Conflict failure deduplication | Partially fixed | Conflict-step failures are deduplicated, but ordinary failure lists can still be repeatedly concatenated across merges. |
| Provenance comments | Fixed | Comments now describe authoritative daemon-issued session/epoch values. |
| Selector compilation | Fixed | The implementation now uses `LinkProperties.interfaceName`; the main Test workflow passes at the current head. |
| Daemon protocol implementation | Not fixed | Session/epoch values remain Kotlin contracts without protobuf/Rust enforcement. |
| Persistent cleanup ownership | Not fixed | `cleanupScope` remains a constructor lifetime promise and `serviceWasActivated` is still not authoritative retry state. |

## Blockers

### 1. Successful authoritative feature teardown can resurrect stale emergency debt

The conflict policy now correctly clears both conflicting service handles, marks listener ownership unknown and sets `featureStopPending`.

During retry, the controller performs these operations in order:

1. when `listenerPending` is true, call `safeEmergencyClose()` and retain its returned debt in `emergencyOutcomeDebt`;
2. when `featureStopPending` is true and no concrete controller handle remains, call authoritative `service.stopFeature()`;
3. on successful `stopFeature()`, clear `listenerPending`, clear `featureStopPending` and set `newServiceActivated = false`;
4. afterward, merge the earlier `emergencyOutcomeDebt` into the updated debt.

If emergency close fails but `stopFeature()` then succeeds, the service has authoritatively removed its internal backend and listener. The later merge nevertheless restores the emergency debt's concrete service handle and listener flag. Controller state then contains service/listener debt while `serviceActivated == false` and `featureStopPending == false`; no future retry can act on that debt.

Required fix:

- authoritative `stopFeature()` success must dominate all service-handle and listener debt observed earlier in the same transaction;
- discard or strip `emergencyOutcomeDebt.serviceHandlePending` and `listenerClosePending` after successful full teardown;
- commit one local service outcome only after all service operations are complete.

Required tests:

- service-handle conflict, emergency close fails, `stopFeature()` succeeds;
- same sequence with simultaneous firewall debt;
- verify final debt contains no service handle, listener or feature-service item;
- verify `serviceActivated` becomes false and the next reconciliation can activate normally.

### 2. The complete-runtime fast path still lacks a fresh daemon-generation check

The fast path verifies that the cached firewall handle matches cached `sanitizedSessionId` and `sanitizedEpoch`, but it does not compare `latestSnapshot.daemonGeneration` with both the reconciliation snapshot and `sanitizedDaemonGeneration` immediately before `firewall.replace()`.

The snapshot collector updates `latestSnapshot` outside `stateMutex`. Therefore it can observe daemon generation G2 while reconciliation for G1 still owns the mutex. The cached G1 handle and cached G1 sanitation markers still match, so the fast path can submit `replace()` through a transport now connected to G2 before the queued G2 snapshot is reconciled.

Required fix:

- before fast-path handle IPC, require `latestSnapshot.daemonGeneration == next.daemonGeneration == sanitizedDaemonGeneration`;
- on mismatch, perform listener containment, classify the firewall handle as unknown provenance and create daemon-clean debt;
- do not publish `Running` for the stale snapshot.

### 3. Cleanup handle IPC can still target a newly restarted daemon

`cleanupApplied()` allows `denyAll()` and `stop()` based on cached session/epoch markers and a caller-provided Boolean `daemonAvailable`. It does not require the latest observed daemon generation to equal `sanitizedDaemonGeneration` immediately before handle IPC.

`retryCleanupDebtSafely()` captures the latest snapshot generation, but determines `handleCurrent` only from cached session/epoch values. If the collector has observed G2 while sanitation markers and debt still refer to G1, retry can submit a G1 handle to G2 before running sanitation.

Required fix:

- in `cleanupApplied()`, allow handle-specific firewall IPC only when the latest observed generation exactly equals `sanitizedDaemonGeneration` and the handle exactly matches the sanitation session/epoch;
- in debt retry, additionally require `capturedDaemonGeneration == newSanitizedDaemonGen` before `denyAll()` or `stop()`;
- otherwise clear concrete firewall-handle operations, retain `daemonCleanPending` and perform no handle IPC;
- add deterministic races for G1 handle + G2 latest snapshot in both cleanup paths.

Controller checks reduce exposure, but authoritative stale-token rejection in the root daemon remains mandatory to close the check-to-use race.

### 4. Session/epoch protocol remains interface-only

`SanitationResult(sessionId, epoch)` and `ProxyFirewallHandle(sessionId, epoch, id)` are still Kotlin-only contracts. The standalone proxy-firewall proto and Rust daemon do not implement:

- sanitation/reset acknowledgement;
- daemon boot/session identity;
- epoch-qualified start response;
- expected session/epoch on replace, deny and stop;
- stale-session/epoch rejection;
- atomic deny installation plus ledger reset;
- overflow/non-zero validity policy.

No controller-only sequence can make these values an enforced security boundary.

## High-severity amendments

### 5. Persistent cleanup ownership and service state remain unproven

`cleanupScope` is still provided abstractly. No concrete foreground-service/application owner proves that it outlives the worker, survives the intended recovery window and is cancelled during final teardown.

`CleanupDebt.serviceWasActivated` remains unused; retry eligibility still depends on mutable controller-local `serviceActivated`. Process/service state drift can therefore leave service debt unreachable.

### 6. Failure history is only partially bounded

Conflict failures are deduplicated by step, but `mergeUnresolved()` still concatenates `failures + other.failures`. Repeated merges of the same ordinary cleanup report can duplicate identical entries indefinitely. Use bounded structured history or deduplicate by a stable failure identity.

### 7. Normalization diagnostics and address choice remain open

Invalid interface/client records are silently removed, and duplicate downstream observations still retain one selected IPv4 address rather than exposing an explicit deterministic selection policy and diagnostics.

## CI status

At review time, current head `8957bc6683dee56465de6067a73169a0cf3f0edc` had:

- `Test`: **success**;
- Rust check, clippy and dependency audit: successful within that workflow;
- Gradle `assembleDebug check`: successful within that workflow;
- `Dependency Review`: **failure** in the dependency-review action step.

The dependency-review log excerpt available to this review did not include the final action diagnostic, so its exact cause is not established here. Do not weaken the severity threshold or add `continue-on-error` without the actual error. Determine whether the failure is caused by repository dependency-graph configuration, action permissions or a reported dependency before changing the workflow.

## Verified corrections

- current selector uses `LinkProperties.interfaceName` and compiles;
- main Test workflow passes at the current head;
- service conflicts no longer retain one arbitrary controller handle;
- `feature_stop` is reachable when a conflict cleared the concrete controller handle;
- primary sanitation binds acknowledgement storage to a captured generation;
- final startup allow checks for a generation change after probes;
- conflict-step failures are deduplicated;
- comments document daemon-issued session/epoch provenance.

## Required next sequence

1. Make successful authoritative feature teardown dominate earlier emergency service/listener debt.
2. Require fresh daemon-generation equality before fast-path, cleanup and retry firewall-handle IPC.
3. Implement session/epoch acknowledgements and stale-command rejection in proto/Rust.
4. Wire a concrete service-owned cleanup supervisor with authoritative service state.
5. Add deterministic tests for teardown dominance and G1-to-G2 command races.
6. Obtain the complete Dependency Review diagnostic and fix its actual configuration or dependency cause.
7. Keep the PR draft until all focused tests and checks pass.

## Approval boundary

Status after round 10: **changes requested**.

The R9 remediation fixes the reported compile failure and materially improves service-conflict and sanitation-race handling, but service debt can still be resurrected after successful authoritative teardown and old firewall handles can still cross a daemon-generation boundary in unguarded command paths.