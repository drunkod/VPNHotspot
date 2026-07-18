# Implementation review, round 8 — R7 remediation

Reviewed commit: `b0fe21555be9d9214404443b32abce70d2a8cb52`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND7.md`

Scope: daemon-session-qualified firewall handles, exact epoch comparison, authoritative start acknowledgement validation, sanitation-failure notification, cleanup ownership preservation and interface-name hardening.

## Verdict

**Changes requested.** The commit correctly adds `SanitationResult(sessionId, epoch)`, carries `(sessionId, epoch, id)` in firewall handles, removes local epoch manufacturing, uses exact equality for current-handle IPC, updates the foreground notification after sanitation failure and rejects `.`/`..` interface names.

However, the newly added start-acknowledgement mismatch path loses ownership of the firewall runtime it has just created: the returned handle is rejected before it is recorded in `applied`, so cleanup sees no firewall resource and creates no daemon-clean debt. The cleanup transaction also remains split because `cleanupApplied()` clears `applied` before the caller merges its returned debt with pre-existing global debt. A separate sanitation-retry race can associate a daemon acknowledgement with the wrong observed daemon generation.

The PR must remain a draft Phase 0 scaffold.

## Resolution audit

| Round-7 item | Status | Round-8 assessment |
| --- | --- | --- |
| Preserve daemon-returned epoch | Partially fixed | `.copy(epoch = ...)` is gone and the returned handle is validated. On mismatch, the handle is discarded before cleanup ownership is recorded. |
| Exact epoch/session equality | Fixed in `cleanupApplied()` and debt retry | Current requires exact `(sessionId, epoch)` equality; older same-session epochs are dominated; future/wrong-session handles require sanitation. The fast path still does not revalidate provenance before `replace()`. |
| Collision-safe daemon identity | Fixed at Kotlin interface/model level | `ProxyFirewallHandle(sessionId, epoch, id)` and `SanitationResult(sessionId, epoch)` are present. No daemon/proto implementation proves uniqueness or stale-command rejection. |
| Preserve `applied` through cleanup conflicts | Partially fixed | `applied` survives the local primary/emergency debt merge, but is cleared before the returned debt is merged with existing global debt. |
| Sanitation-failure notification | Fixed | The null-result path calls `enterWaitingIfActive(state)` after containment and before publication. |
| Reject `.` and `..` interfaces | Fixed | Both names are explicitly rejected in addition to the ASCII allow-list. |
| Persistent cleanup owner | Not fixed | `cleanupScope` remains an abstract constructor contract and retry still relies on mutable `serviceActivated`. |
| Epoch/session daemon protocol | Not fixed | The standalone proto and Rust daemon still do not implement sanitation/start acknowledgements or session-qualified command rejection. |

## Blockers

### 1. A mismatched start acknowledgement leaves the created firewall runtime untracked

Startup currently does:

```kotlin
val returnedHandle = firewall.start(config)
if (returnedHandle.sessionId != sanitizedSessionId ||
    returnedHandle.epoch != sanitizedEpoch) {
    transitionAfterExpectedProbeFailure(...)
    return
}
partial.firewall = returnedHandle
```

`firewall.start()` may already have created a daemon-side runtime before returning the mismatched acknowledgement. Because `partial.firewall` is assigned only after validation, `transitionAfterExpectedProbeFailure()` calls `cleanupApplied()` with `current.firewall == null`.

Failure sequence:

1. sanitation succeeds with expected `(session=S1, epoch=E1)`;
2. `firewall.start()` creates runtime R and returns `(S2, E2, R)` or any mismatched pair;
3. the controller rejects the acknowledgement before storing the handle;
4. `cleanupApplied()` sees no firewall handle and creates no `DAEMON_CLEAN` debt;
5. `applied` is cleared;
6. the sanitation markers remain satisfied for the observed daemon generation;
7. the next snapshot can start another firewall runtime over the untracked runtime.

This violates the restart gate and resource-ownership invariant.

Required fix:

- treat any start-acknowledgement mismatch as a daemon protocol conflict, not as an ordinary probe failure;
- retain the returned handle long enough to preserve evidence of the created runtime, but do not issue handle-specific IPC against mismatched provenance;
- invalidate the sanitation markers or construct explicit `daemonCleanPending` debt unconditionally;
- force authoritative sanitation before another `start()`;
- do **not** classify a just-returned lower epoch as safely dominated merely because it is numerically lower — it was returned after the current sanitation request and therefore represents a protocol conflict.

One safe shape is:

```kotlin
partial.firewall = returnedHandle
if (!matchesSanitation(returnedHandle)) {
    sanitizedDaemonGeneration = null
    sanitizedSessionId = null
    sanitizedEpoch = null
    // cleanupApplied now classifies the handle as unknown/conflicting,
    // performs no handle IPC and records daemonCleanPending.
    transitionAfterFirewallProtocolConflict(...)
    return
}
```

Required tests:

- start returns wrong session with lower/equal/higher epoch;
- start returns correct session with lower/higher epoch;
- no mismatch starts the backend;
- every mismatch creates observable daemon-clean debt;
- a second firewall start is impossible until sanitation succeeds.

### 2. `applied` is still cleared before the global cleanup-debt commit

The R7 change moves `applied = null` after the local merge of `primaryDebt` and `emergencyDebt`. That protects against a conflict inside `cleanupApplied()`.

Every caller then separately executes:

```kotlin
val outcome = cleanupApplied(...)
mergeDebt(outcome.debt)
```

`mergeDebt()` can call `cleanupDebt.mergeUnresolved(incoming)` and throw when the newly returned service/firewall handle conflicts with pre-existing global debt. At that point `cleanupApplied()` has already cleared `applied`.

This is the same lost-ownership class as round 7, one transaction boundary later.

Required fix:

- make cleanup calculation side-effect-free with respect to `applied`;
- merge the returned debt with existing global debt first;
- only after the global merge succeeds, atomically clear the exact `AppliedProxyState` snapshot and commit the combined debt;
- or replace throwing handle conflicts with a typed conflict state that retains every observation and forces authoritative service/daemon reconciliation.

A cleanup transaction must not clear the sole applied-resource record until both local and global debt construction have succeeded.

Required tests:

- existing global service debt B plus applied service handle A;
- existing global firewall debt from one session plus applied handle from another;
- global `mergeUnresolved()` failure leaves `applied` unchanged;
- every resource remains represented in applied state, cleanup debt or an authoritative full-teardown result.

### 3. Debt-retry sanitation can be labelled with the wrong daemon generation

`retryCleanupDebtSafely()` reads only a Boolean at the beginning:

```kotlin
val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true
```

After sanitation succeeds, it reads the snapshot again:

```kotlin
newSanitizedDaemonGen = latestSnapshot.get()?.daemonGeneration
```

The source collector updates `latestSnapshot` outside `stateMutex`. The observed daemon generation can therefore change while `cleanOrDenyBeforeRestart()` is in flight.

Failure sequence:

1. retry starts while snapshot says daemon generation G1;
2. sanitation request is sent to session S1;
3. collector publishes generation G2 while the request is in flight;
4. sanitation returns `(S1, E1)`;
5. controller stores `sanitizedDaemonGeneration = G2` with session S1;
6. reconciliation for G2 can skip the sanitation gate because the generation appears current.

Start-handle validation may later detect a session mismatch, but the current mismatch path is itself untracked as described in blocker 1.

Required fix:

- capture one snapshot/generation before the sanitation call;
- associate the result only with that captured generation;
- after the call, if the latest daemon generation changed, do not mark the newer generation sanitized; retain/recreate sanitation debt and let reconciliation sanitize the new daemon;
- ideally bind the firewall client call to an authoritative daemon session/channel identity rather than relying only on an independently updated flow snapshot.

Required tests:

- daemon generation changes while sanitation is suspended;
- acknowledgement from G1 is never stored as sanitation for G2;
- reconciliation always sanitizes G2 before runtime start.

### 4. The fast path does not validate handle provenance before `replace()`

The complete-runtime fast path checks only `RuntimeKey` equality and then calls:

```kotlin
firewall.replace(current.firewall!!, config)
```

`RuntimeKey` includes the controller-observed daemon generation, but not the authoritative sanitation session/epoch. If sanitation identity changes while `applied` survives a conflict/recovery path, the key can remain equal while the firewall handle is no longer current.

Required fix:

- require exact `(sessionId, epoch)` equality immediately before every handle IPC, including fast-path `replace()`;
- or include authoritative sanitation identity in the applied runtime key and still retain an explicit assertion before IPC;
- a mismatch must close/contain the listener and create sanitation debt, never call `replace()`.

### 5. The session/epoch contract remains interface-only

The Kotlin interface now expresses the right shape, but `proxy_firewall.proto` and the Rust daemon still have no:

- sanitation/reset request and acknowledgement;
- daemon session/boot identity;
- epoch-qualified start acknowledgement;
- expected session/epoch on commands;
- stale-session/epoch response;
- atomic ledger reset plus deny installation;
- overflow policy.

Until this exists, `sessionId` and `epoch` are unverified values supplied by a hypothetical implementation.

The command `generation` also remains a signed Kotlin `Long` while the proto declares `uint64`. `AtomicLong.incrementAndGet()` can wrap. Define non-zero/session validity, ordering and overflow behavior before treating the fields as a security boundary.

## High-severity amendments

### 6. Persistent cleanup ownership is still not wired

`cleanupScope` remains an abstract scope passed to the controller. There is no concrete service-owned supervisor, debt handoff or authoritative service-state query.

`CleanupDebt.serviceWasActivated` is still not used to decide retry eligibility; retry copies mutable `serviceActivated` instead. The implementation therefore does not yet prove cleanup after worker cancellation or controller-state drift.

### 7. Local retry staging does not reconcile irreversible external effects

The retry transaction commits local fields only after debt merging, which prevents partial in-memory commits. Backend stop, feature stop and sanitation can still succeed externally before a later merge conflict throws. The retry catch preserves old debt rather than reading back authoritative service/daemon state.

Idempotence helps but is not a complete ownership proof. Add typed operation outcomes or service/daemon read-back sufficient to reconcile the exact surviving handle/session after an interrupted transaction.

### 8. Normalization diagnostics and downstream address selection remain open

Invalid interfaces and client identities are silently removed. `ManagedDownstream` still chooses the first valid IPv4 observation instead of enforcing or modelling a canonical address set. Reversed observation order can change the advertised endpoint.

## Verified corrections

- `ProxyFirewallHandle` now carries `sessionId`, `epoch` and runtime `id`;
- `cleanOrDenyBeforeRestart()` returns `SanitationResult?`;
- local `.copy(epoch = ...)` provenance manufacturing is removed;
- cleanup and debt retry require exact session/epoch equality for current handles;
- lower same-session epochs are dominated without handle IPC;
- future, wrong-session and unknown handles require sanitation in cleanup/retry;
- sanitation null-return updates the service waiting/degraded notification;
- `.` and `..` interface names are rejected;
- the local primary/emergency debt merge completes before `applied` is cleared;
- review documents through round 7 remain present.

## Build and test status

- GitHub reports no combined status checks for `b0fe21555be9d9214404443b32abce70d2a8cb52`.
- No pull-request workflow runs exist for the commit.
- No deterministic session/epoch, start-ack mismatch, global-debt conflict, sanitation-race or cleanup-supervisor tests were added.
- A local build could not be run in the review environment because `github.com` DNS resolution failed.

Required evidence remains:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

## Required next sequence

1. Preserve cleanup ownership for a mismatched `firewall.start()` acknowledgement and require sanitation before any retry.
2. Commit global debt and clear `applied` in one atomic controller transaction.
3. Bind sanitation acknowledgements to the captured daemon generation/session and handle generation changes during the call.
4. Validate authoritative session/epoch before every firewall-handle IPC, including the fast path.
5. Implement the sanitation/start/replace/stop protocol and stale-command rejection in proto/Rust.
6. Wire a concrete service-owned cleanup supervisor with authoritative state reconciliation.
7. Add deterministic tests for all R1–R8 failure sequences and attach compile/test/assembly evidence.
8. Only then continue with Hev/JNI and production firewall integration.

## Approval boundary

Status after round 8: **changes requested**.

The R7 remediation fixes the data model and most comparison semantics, but the controller still loses ownership of a firewall runtime on a mismatched start acknowledgement and has not made cleanup commitment atomic across global debt.