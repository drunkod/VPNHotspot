# Implementation review, round 7 — R6 remediation

Reviewed commit: `2e7c5630dd0b8d30d18458dd9130d6fd2cb24667`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND6.md`

Scope: epoch-qualified firewall handles, sanitation-failure listener containment, ASCII interface validation, cleanup-retry state staging and constructor consistency.

## Verdict

**Changes requested.** The commit fixes the previous `CleanupDebt` constructor mismatch, contains the backend/listener when sanitation returns `null`, rejects non-ASCII interface characters and stages several retry-state updates locally. However, the firewall epoch is still not authoritative or collision-safe, the controller accepts handles from impossible future epochs, and `cleanupApplied()` can still discard its copied handles if debt merging throws after `applied` is cleared.

The PR must remain a draft Phase 0 scaffold.

## Resolution audit

| Round-6 item | Status | Round-7 assessment |
| --- | --- | --- |
| Kotlin constructor mismatch | Fixed at source level | `firewallHandleGeneration` was removed and all visible `CleanupDebt(...)` construction sites match the new model. Build evidence is still absent. |
| Listener containment when sanitation returns `null` | Substantially fixed | `cleanupApplied("sanitation failed", daemonAvailable = false)` runs before publication and attempts backend stop/emergency close. The service notification is not moved to a waiting state in this branch, but the listener-side safety action exists. |
| Same-daemon epoch provenance | Partially fixed | `ProxyFirewallHandle` carries `(epoch, id)`, but the controller overwrites the epoch returned by `firewall.start()` and accepts `handle.epoch >= sanitizedEpoch`. |
| Unknown-provenance dominance | Improved | A null sanitation epoch creates daemon-clean debt instead of being treated as successful dominance. |
| ASCII interface validation | Fixed for character class | Explicit ASCII ranges replace `Char.isLetterOrDigit()`. `.`/`..`, silent dropping and the one-address invariant remain unresolved. |
| Retry auxiliary-state staging | Partially fixed | Local state variables are committed after debt combination, but external service/daemon effects are still irreversible and `cleanupApplied()` itself is not conflict-safe. |
| Persistent cleanup ownership | Not fixed | A separate `cleanupScope` remains only a constructor contract; `serviceWasActivated` remains unused by retry decisions. |
| Epoch/reset daemon implementation | Not fixed | The standalone proto and Rust daemon still do not implement sanitation acknowledgement, epoch-qualified handles or stale-command rejection. |

## Blockers

### 1. The controller overwrites the daemon-returned handle epoch

Startup currently does:

```kotlin
partial.firewall = firewall.start(config).copy(epoch = sanitizedEpoch!!)
```

`ProxyFirewallClient.start()` already returns a `ProxyFirewallHandle(epoch, id)`. Replacing that returned epoch with local controller state defeats the purpose of an authoritative daemon acknowledgement. A buggy, stale or compromised client response with the wrong epoch is silently rewritten into a valid-looking handle.

Required fix:

```kotlin
val handle = firewall.start(config)
if (handle.epoch != sanitizedEpoch) {
    // fail closed; do not start the backend or publish Running
    // reconcile through sanitation using the authoritative returned handle/session
}
partial.firewall = handle
```

Do not manufacture provenance with `copy()`.

Required tests:

- start acknowledgement returns the current epoch;
- start returns an older epoch;
- start returns a newer epoch;
- no mismatched acknowledgement can reach backend startup or `Running`.

### 2. Epoch comparison treats future/conflicting handles as current

`cleanupApplied()` defines current as:

```kotlin
fw.epoch >= sanitizedEpoch
```

and retry defines stale only as:

```kotlin
handle.epoch < sanitizedEpoch
```

A handle whose epoch is greater than the controller's acknowledged epoch is not a valid current handle. It indicates conflicting state, a delayed/corrupt response, or an epoch protocol bug. Sending `denyAll`/`stop` with it is unsafe.

Required policy:

```text
handle.epoch == sanitizedEpoch  -> current, IPC allowed
handle.epoch <  sanitizedEpoch  -> dominated by successful sanitation
handle.epoch >  sanitizedEpoch  -> conflict; no handle IPC, contain listener, require authoritative sanitation/reconciliation
sanitizedEpoch == null          -> unknown; no handle IPC, require sanitation
```

Required tests cover all four cases in both `cleanupApplied()` and debt retry.

### 3. Epoch-only handles are not collision-safe across daemon restarts

The R6 change removed daemon-generation provenance from firewall handles. That is safe only if the daemon guarantees that sanitation epochs are globally unique and strictly increasing across daemon process restarts.

No such implementation exists. A restarted daemon can issue the same or a lower epoch. An old handle can then compare equal to—or greater than—the new epoch and be submitted to the new daemon.

Use one of these authoritative identities:

- opaque daemon session/boot token + epoch + runtime id; or
- daemon generation/instance id + epoch + runtime id; or
- a globally unique opaque epoch token whose uniqueness survives daemon restart.

Numeric ordering alone is insufficient unless the daemon implementation proves persistence, overflow policy and atomic issuance.

Required tests:

- daemon restart reuses epoch number;
- daemon restart issues a lower epoch;
- same-daemon re-sanitation issues a new epoch;
- old-session handles are never submitted even when numeric epochs collide.

### 4. `cleanupApplied()` can still lose copied handles on merge conflict

The function copies `applied` into `current`, then immediately sets:

```kotlin
applied = null
```

Later it combines primary debt with service-reported emergency debt using `mergeUnresolved()`, which uses `require()` for conflicting service/firewall handles.

Failure sequence:

1. controller holds service handle A;
2. `stopBackend(A)` reports a stale-handle failure;
3. emergency close reports actual service handle B;
4. primary and emergency debt conflict;
5. `mergeUnresolved()` throws;
6. `applied` is already null and no `CleanupOutcome` is returned;
7. generic recovery calls `cleanupApplied()` again and sees nothing applied.

The retry scheduler's top-level boundary does not protect this path because the exception occurs during normal reconciliation cleanup.

Required fix:

- do not clear `applied` until a complete cleanup/debt result is constructed; or
- replace `require()` conflicts with a typed conflict debt capable of retaining both observations and forcing service reconciliation/full teardown; or
- snapshot authoritative service state before cleanup and construct one non-throwing result.

No cleanup merge should throw after the only applied-resource record has been detached.

Required tests:

- stale controller service handle plus different service-reported handle;
- conflicting firewall handles/epochs;
- every copied handle remains represented in applied state, debt, or an authoritative full-teardown result.

### 5. The epoch/reset protocol remains interface-only

`cleanOrDenyBeforeRestart(): Long?` and `ProxyFirewallHandle(epoch, id)` are Kotlin contracts only.

The current `proxy_firewall.proto` contains only configuration and a command generation. It has no:

- sanitation/reset request;
- sanitation acknowledgement;
- daemon session identity;
- epoch-qualified start response;
- replace/stop acknowledgement;
- stale-epoch error.

The Rust build still compiles only `daemon.proto`; no proxy-firewall runtime or ledger implementation exists.

Until proto/Rust implement this contract, the controller cannot prove that:

- sanitation and deny installation are atomic;
- the returned epoch is authoritative;
- epochs cannot collide across daemon restarts;
- delayed commands from prior sessions are rejected.

## High-severity amendments

### 6. Persistent cleanup ownership is still not wired

The controller still receives an abstract `cleanupScope`; no production construction transfers debt to a service-owned supervisor or proves the scope survives worker cancellation with working service IPC.

`CleanupDebt.serviceWasActivated` is recorded but retry uses mutable `serviceActivated`. A stale false value can leave service/listener debt permanently unattempted; a stale true value can call an inactive service.

Provide a concrete owner with authoritative service-state queries and lifecycle tests.

### 7. Retry staging is not a true external transaction

Local variables prevent partial in-memory commits, but backend stop, feature stop and sanitation may succeed externally before a later `mergeUnresolved()` failure. The catch preserves old local debt, not the external result.

This can be made safe only through idempotent authoritative queries/reconciliation, not local staging alone. Add service/daemon read-back or typed operation outcomes that identify the exact resolved resource/session.

### 8. Sanitation failure does not update the foreground-service waiting notification

The null-return path contains resources and publishes controller state, but does not call `enterWaitingIfActive(state)` before returning. The foreground notification can remain visually stale even though the listener has been stopped.

Update the service to a listener-free waiting/cleanup-degraded notification after containment.

### 9. Normalization diagnostics and downstream address invariants remain open

Invalid interfaces and clients are still silently dropped. `ManagedDownstream` retains one nullable address and chooses the first valid observation, so reversed observation order can change the endpoint.

Also consider explicitly rejecting `.` and `..` interface names rather than merely matching the ASCII character set.

## Verified corrections

- all visible `CleanupDebt` construction sites match the new constructor;
- sanitation `null` invokes immediate service-side cleanup before state publication;
- null sanitation epoch no longer means successful firewall dominance;
- zero MAC remains rejected;
- interface validation is ASCII-only;
- retry job self-cancellation remains removed;
- ordinary retry-transaction exceptions are caught and rescheduled;
- round-1 through round-6 review documents remain present in commit `2e7c5630`.

## Build and test status

- GitHub reports no combined status checks for `2e7c5630dd0b8d30d18458dd9130d6fd2cb24667`.
- No pull-request workflow runs exist for the commit.
- No deterministic epoch, sanitation, cleanup-conflict, supervisor-lifecycle or normalization tests were added.
- A local build could not be run in the review environment because `github.com` DNS resolution failed.

Required evidence remains:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

## Required next sequence

1. Preserve and validate the daemon-returned handle epoch; remove the local `copy(epoch=...)` rewrite.
2. Require exact epoch/session equality for handle IPC; treat future/unknown provenance as conflict/sanitation debt.
3. Add daemon-session identity so epoch collisions across daemon restarts cannot validate old handles.
4. Make `cleanupApplied()` conflict-safe without clearing the only applied-resource record before a result exists.
5. Implement sanitation/start/replace/stop acknowledgements in proto and Rust.
6. Wire a concrete cleanup supervisor and authoritative service-state reconciliation.
7. Add deterministic tests for all R1–R7 failure sequences and attach compile/test/assembly evidence.
8. Only then continue with Hev/JNI and production firewall implementation.

## Approval boundary

Status after round 7: **changes requested**.

The R6 remediation fixes the immediate constructor and sanitation-containment defects, but the current branch does not yet provide authoritative, collision-safe firewall handle provenance or conflict-safe cleanup ownership.
