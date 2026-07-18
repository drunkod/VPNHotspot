# Implementation review, round 11 — R10 remediation

Reviewed source commit: `8ac52cd5d0ad0a0774df269dcbc9cfc1e0e2929d`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND10.md`

Scope: authoritative feature-stop dominance, fast-path daemon-generation guarding, and cleanup/retry firewall-command generation guards.

## Verdict

**Changes requested.** The remediation correctly makes successful `stopFeature()` dominate emergency service/listener debt, adds daemon-generation triple equality to the complete-runtime fast path, and adds generation checks before cleanup handle IPC.

However, the cleanup implementations still allow epoch dominance to override a daemon-generation mismatch. A same-session, lower-epoch handle is silently discarded as resolved even when the latest/captured daemon generation differs from the sanitation generation. This contradicts the R10 requirement that any generation mismatch create `daemonCleanPending` and issue no handle-specific IPC.

The PR must remain a draft Phase 0 branch.

## Resolution audit

| Round-10 item | Status | Round-11 assessment |
| --- | --- | --- |
| Successful feature teardown dominates emergency debt | Fixed | On successful `feature_stop`, `emergencyOutcomeDebt` is cleared before the final merge. Stale emergency service/listener debt cannot be resurrected. |
| Fast-path generation guard | Fixed at controller level | Fast-path `replace()` requires latest, reconciliation and sanitation generations to match. A mismatch contains the runtime and creates sanitation debt. |
| Generation guard in `cleanupApplied()` | Partially fixed | Exact-current handle IPC requires generation equality, but the lower-epoch dominated branch ignores generation mismatch and can clear the handle without sanitation. |
| Generation guard in cleanup-debt retry | Partially fixed | Exact-current handle IPC requires captured-generation equality, but lower-epoch dominance can still suppress `daemonCleanPending` after a generation change. |
| Root-daemon session/epoch enforcement | Not fixed | The protobuf/Rust daemon still does not enforce sanitation acknowledgements, session-qualified commands or stale-token rejection. |
| Persistent cleanup ownership | Not fixed | `cleanupScope` remains an abstract lifetime contract and `serviceWasActivated` remains unused as authoritative state. |

## Blocker

### Generation mismatch is incorrectly overridden by epoch dominance

In `cleanupApplied()`:

```kotlin
val generationSafe = latestGen != null && latestGen == sanitizedDaemonGeneration
val firewallCurrent = sameSession && sEpoch != null && fw!!.epoch == sEpoch && generationSafe
val firewallDominated = sameSession && sEpoch != null && fw!!.epoch < sEpoch
val firewallNeedsClean = fw != null && !firewallCurrent && !firewallDominated
```

When `generationSafe == false` but the handle has the same cached session and a lower epoch, `firewallDominated == true`. Consequently `firewallNeedsClean == false`; the handle is dropped as resolved and no `daemonCleanPending` debt is created.

The retry path has the same issue:

```kotlin
val handleDominated = handleSameSession &&
    newSanitizedEpoch != null &&
    firewallHandle!!.epoch < newSanitizedEpoch
val generationSafe = capturedDaemonGeneration != null &&
    capturedDaemonGeneration == newSanitizedDaemonGen
val handleCurrent = handleEpochCurrent && generationSafe
val handleStale = firewallHandle != null && !handleCurrent

if (handleStale) {
    if (!handleDominated) daemonCleanPending = true
    ...
}
```

A generation-mismatched, lower-epoch handle sets `handleDominated == true`, so retry clears the concrete handle without setting `daemonCleanPending`.

Generation mismatch must dominate every epoch relation. A safe formulation is:

```kotlin
val generationSafe = latestGen != null && latestGen == sanitizedDaemonGeneration
val sameSession = fw != null && sId != null && fw.sessionId == sId
val firewallCurrent = generationSafe && sameSession && sEpoch != null && fw!!.epoch == sEpoch
val firewallDominated = generationSafe && sameSession && sEpoch != null && fw!!.epoch < sEpoch
val firewallNeedsClean = fw != null && !firewallCurrent && !firewallDominated
```

Apply the same `generationSafe && ...` condition to `handleDominated` in retry. Alternatively, branch on generation first:

```text
generation mismatch/unknown -> daemonCleanPending, no handle IPC, no epoch dominance
generation match + same session + lower epoch -> dominated by sanitation
generation match + exact session/epoch -> current handle IPC allowed
all other cases -> daemonCleanPending
```

Required deterministic tests:

- cleanup-applied: G1 sanitation, G1 same-session lower-epoch handle, latest snapshot G2 -> `daemonCleanPending`, no `denyAll`/`stop`;
- debt retry: captured G2, sanitation generation G1, same-session lower-epoch handle -> `daemonCleanPending`, no handle IPC;
- matched G1 generation + same-session lower epoch remains safely dominated;
- matched G1 generation + exact session/epoch permits the requested handle operation.

## Remaining structural blockers

1. The root daemon does not yet implement the sanitation/start/replace/deny/stop protocol, session/epoch acknowledgements or stale-command rejection.
2. A concrete service-owned cleanup supervisor and authoritative service-state reconciliation are still absent.
3. Ordinary cleanup failure lists can still grow through repeated concatenation.
4. Invalid normalization inputs are silently dropped and downstream IPv4 selection remains order-dependent.

## CI status

For reviewed source commit `8ac52cd5`:

- `Test`: **success**;
- Rust check, clippy and dependency audit within `Test`: **success**;
- Gradle build and tests within `Test`: **success**;
- `Dependency Review`: **failure** in the dependency-review action step.

The available action log remains truncated before the final dependency-review diagnostic. Do not weaken the severity threshold or suppress the workflow without establishing the actual error.

## Verified corrections

- commit `8ac52cd5` modifies only `ProxyOnlyController.kt` relative to the round-10 review head;
- successful authoritative feature teardown discards earlier emergency debt;
- fast-path handle IPC requires latest/reconciliation/sanitation generation equality;
- generation-mismatched exact-current handles do not receive cleanup IPC;
- the main Test workflow succeeds.

## Required next sequence

1. Make generation safety a prerequisite for both exact-current and lower-epoch-dominated classification in both cleanup paths.
2. Add deterministic generation/epoch matrix tests.
3. Implement and test daemon-enforced session/epoch command validation.
4. Wire the persistent service-owned cleanup supervisor.
5. Obtain the complete Dependency Review diagnostic and fix its actual cause.

## Approval boundary

Status after round 11: **changes requested**.

The three R10 edits are substantially correct, but the lower-epoch branch still permits a generation mismatch to bypass required sanitation.