# Structural review, round 3 — proxy-only design (`agent/proxy-only-design`)

Reviewed at `d5a7da7a` (31 commits). Scope: the round-2 consistency corrections —
service activation gating, explicit waiting commands, timeout/cancellation separation,
and the cleanup-debt contract.

## Verdict

All round-2 required changes and amendments are incorporated in substance. The worker
is now exception-safe, `ProxyService` is the sole backend owner, probes are
outbound-only, the UDP return policy is correctly gated behind Phase 0 topology
evidence, and the FGS persists through waiting/fail-closed states. The state machine,
sketches and test plan tell one consistent story.

Round 3 examines the newly introduced machinery. It contains one logic bug in the
debt-clearing path, two lifecycle gaps, and one diagnosability regression. None changes
the product direction; all four should be corrected in the docs before Phase 0 code,
since they live exactly in the controller/service contract Phase 0 will implement.

---

## 1. Findings

### 1.1 Debt clearing does not match debt content (logic bug)

`CODE_SKETCHES.md §6, retryCleanupDebtSafely`:

```kotlin
val emergency = service.emergencyCloseListener("cleanup debt retry")
if (emergency.failures.isNotEmpty()) return
if (debt.requiresDaemonClean && !firewall.cleanOrDenyBeforeRestart()) return
cleanupDebt = null
```

The retry resolves only two things: listener closure and (when `requiresDaemonClean`)
daemon Clean. But debt can be created by a failed `deny` or `firewall_stop` step while
the daemon is healthy (`requiresDaemonClean = false`). For such debt, this code clears
`cleanupDebt` after nothing more than an emergency listener close — the unresolved
firewall runtime and its rules are never re-stopped, yet the
`check(cleanupDebt == null)` restart gate is now satisfied. The next startup installs a
second firewall runtime on top of stale rules, which is precisely what the debt
mechanism exists to prevent.

**Fix:** make `CleanupDebt` carry per-resource unresolved state (listener, firewall
handle, daemon-clean required) and have the retry attempt each unresolved item —
emergency close, then `firewall.denyAll`/`firewall.stop` for a surviving handle, then
daemon Clean — clearing only the items that succeeded. Debt is cleared when all items
are resolved, not when the listener alone is closed. Add a daemon-healthy
`firewall_stop`-failure test to the debt-clearing suite (the current tests cover debt
*creation* and *blocking*, not partial resolution).

### 1.2 Cleanup debt is only retried when a new snapshot arrives

Debt retry lives at the top of `reconcile`, which runs once per desired-state
emission. If the system is quiescent — settings, VPN, tethering and clients all stable —
no snapshot arrives and `CleanupDegraded` persists indefinitely with no retry, even
when the failure was transient. The user's proxy stays down until something unrelated
changes.

**Fix:** on unresolved debt, schedule a bounded backoff re-enqueue of the last
snapshot (or a synthetic retry event) into the conflated channel. The state machine's
`CleanupDegraded -> retry` edge currently has no trigger; give it one and test
"debt clears with no external state change."

### 1.3 Activation context is not modeled — the sketch attempts the FGS start the docs forbid

The docs correctly state that resurrection after process death "is not assumed" and
enabling "must originate from a user-approved foreground action." The sketch does not
implement that distinction: `reconcile` calls `service.activateFeature(...)` whenever
`enabled && !serviceActivated`. On process restart with persisted `enabled = true`, the
first snapshot arrives while the app is backgrounded, the FGS start throws
(`ForegroundServiceStartNotAllowedException`), and control lands in `recoverSafely` —
which then calls `service.enterWaiting(...)` on a service that never activated
(undefined behaviour in the current contract).

**Fix:** three parts.

1. `DesiredProxyState` carries an activation grant (set only by a foreground user
   action, consumed on successful activation) — persisted `enabled` alone never
   triggers `activateFeature`.
2. Add a distinct state (`ActivationRequired` or similar): feature enabled, service
   not startable from this context; UI shows "open the app to resume."
3. Guard every `service.*` call in `enterWaiting`/`recoverSafely`/`terminalStopSafely`
   with `serviceActivated`; define `ProxyServiceClient` behaviour for calls before
   activation (no-op report, not exception).

### 1.4 Typed failure states are unreachable through the generic `check()`

`reconcile` ends startup with `check(probes.allRequiredPassed)`. Any probe failure —
including the app-UID bind probe failing with EPERM — becomes an
`IllegalStateException`, lands in `recoverSafely`, and publishes
`FailClosed("Proxy stopped after internal failure")`. The `VpnPermissionDenied` state
that rounds 1–2 introduced for exactly this diagnosis exists in the model and the test
plan (`TEST_PLAN` L130 expects it) but is **unreachable** in the sketch: no code path
publishes it. `MultipleVpnCandidates` is wired; `VpnPermissionDenied` is not.

**Fix:** have `runOutboundProbes` return typed failures and map them before the
generic check: `APP_UID_BIND` EPERM → `VpnPermissionDenied` (a waiting state, not a
cleanup-triggering internal failure), DNS/TCP/UDP probe failures → distinct
`FailClosed` reasons. Also make `ProbeReport.allRequiredPassed` config-aware —
it currently requires `OUTBOUND_UDP` to pass even when `udpEnabled = false`.

---

## 2. Minor notes

- **Idle emergency-close churn:** in `cleanupApplied`, the `emergency_close` step runs
  whenever `current?.service == null` — including `current == null`, i.e. every waiting
  snapshot with nothing applied. Harmless when it succeeds, but a failure while idle
  fabricates cleanup debt for resources that never existed. Guard the step with
  `current != null`.
- **Duplicate feature-stop:** `terminalStopSafely` runs `feature_stop` inside
  `cleanupApplied(stopFeature = true)` and then again directly when `serviceActivated`
  is still true. Two consecutive retries with no distinct semantics — give feature-stop
  one owner.
- **`check(cleanupDebt == null)` as control flow:** the mid-`reconcile` restart gate
  throws and relies on `recoverSafely` (which runs a redundant second cleanup on an
  already-empty `applied`). Use the same publish-`CleanupDegraded`-and-return pattern
  as the top-of-method debt handling.
- **Deny is now implicit in the proto:** `deny_all_ipv4` was dropped, so
  `firewallConfig(denied = true)` is presumably an empty `allowed_clients` list. Deny
  and "no known clients" happen to coincide today, but an explicit deny flag (as kept
  for IPv6) is cheaper than relying on that coincidence during later changes.

## 3. Round-2 resolution audit

| Round-2 item | Status |
| --- | --- |
| 1.1 Exception-safe worker, terminal cleanup | Resolved — per-iteration catch, timeout vs cancellation split, `finally` terminal stop; see 1.3/1.4 for edges inside the new paths |
| 2.1 Hev UDP socket topology in Phase 0 | Resolved — `UdpTopologyReport`, `VerifiedUdpReturnPolicy` absent until evidence |
| 2.2 Outbound-only probes | Resolved — `ProbeKind` set + `INTERNAL_LISTENER_READY`; see 1.4 for config-awareness |
| 2.3 `ProxyService` sole backend owner | Resolved — controller holds only `ProxyServiceClient` |
| 2.4 FGS alive through recovery states | Resolved in docs; see 1.3 for the unmodeled activation context |
| Multiple VPN candidates | Resolved — fail closed with `MultipleVpnCandidates` |
| UDP capacity ceiling | Resolved — `maxUdpAssociations` + effective-capacity note |
| Cleanup reporting | Resolved — `CleanupAccumulator`, no silent `runCatching` |

## 4. Approval boundary

With findings 1.1–1.4 corrected in the documents, the design is internally consistent
and Phase 0 implementation can begin. The Phase 0 exit criteria and release gates from
the previous rounds remain unchanged and still gate everything beyond the spike.

---

## 5. Resolution applied after round 3

The branch documents were rewritten after this review. Resolution status:

| Round-3 item | Resolution |
| --- | --- |
| 1.1 Debt clearing mismatch | `CleanupDebt` now carries listener, service handle, firewall handle, deny, firewall stop, daemon Clean and feature-stop obligations separately. Retry clears fields item by item. A healthy-daemon firewall-stop failure retains and retries the exact firewall handle. |
| 1.2 No retry trigger | Added `ControllerEvent.RetryCleanupDebt(generation)` plus bounded exponential-backoff re-enqueue of the retained latest desired snapshot. Debt can clear with no external flow emission. |
| 1.3 Activation context missing | Added one-time `ActivationGrant`, `ActivationRequired`, grant consumption after successful FGS activation, pre-activation service-call guards and no-op client behavior. Persisted enable state alone cannot start the FGS. |
| 1.4 Typed failures unreachable | Replaced generic all-pass `check()` with typed, configuration-aware `ProbeEvaluation`. `VpnPermissionDenied` is reachable; UDP is required only when enabled; TCP/DNS/UDP/listener failures retain distinct reasons. |
| Idle emergency-close churn | `cleanupApplied` returns immediately when no applied resources exist. Emergency close runs only after a real partial/backend resource existed and normal stop did not prove closure. |
| Duplicate feature stop | Terminal shutdown is the sole owner of `stopFeature`. |
| Exception control-flow debt gate | Replaced with explicit publish/schedule/return while debt remains. |
| Implicit IPv4 deny | Restored explicit `deny_all_ipv4` alongside `deny_all_ipv6` in the proto and firewall contract. |

### Added verification gates

- healthy-daemon firewall-stop debt partial-resolution test;
- quiescent-system retry test;
- activation grant expiration/replay/process-restart tests;
- pre-activation service no-op tests;
- typed probe reachability tests;
- UDP-disabled probe requirement test;
- no-idle-debt test;
- single-owner feature-stop test;
- explicit IPv4 deny tests.

With these document changes, the design is approved to begin **Phase 0 feasibility work only**. This remains no approval for production UI, firewall commands or release code before the Phase 0 exit criteria pass.
