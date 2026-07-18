# Implementation review, round 1 — proxy-only scaffold

Reviewed branch: `agent/proxy-only-design` at `c314ba58334db17131b03b1de6ad89c5baaec699`.

Scope: the nine commits that added Kotlin models/controller/service contracts, the native network-hook header, UDP evidence models and `proxy_firewall.proto`.

## Verdict

The commits are useful **Phase 0 scaffolding**, but they do not complete Steps 1–9 as an executable feature. The branch must remain draft and must not be described as production-ready or as a completed Phase 0 spike.

There are four fail-closed blockers in the new controller/service code, several high-severity lifecycle and security problems, and major missing integration work. Fix the blockers and add focused tests before wiring a real Hev backend or root firewall runtime.

## Blockers

### 1. `VpnPermissionDenied` falls through to `Running`

In `ProxyOnlyController.reconcile`, every failed probe branch returns except `ProbeEvaluation.VpnPermissionDenied`.

Current behavior:

```kotlin
ProbeEvaluation.VpnPermissionDenied ->
    transitionAfterExpectedProbeFailure(ProxyOnlyState.VpnPermissionDenied)
```

After cleanup and publication, execution continues to:

```kotlin
firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false))
partial.complete = true
publishSafely(next.runningState())
```

This can publish `Running` and attempt to install allow rules after proving the app cannot use the VPN.

Required fix: make every non-success `ProbeEvaluation` branch return. Prefer a helper returning a sealed startup decision rather than relying on repeated manual `return` statements.

Required test: `APP_UID_BIND -> PermissionDenied` must end listener-free in `VpnPermissionDenied`, must never call firewall allow replacement, and must never publish `Running`.

### 2. Firewall `false` results are treated as successful cleanup

`ProxyFirewallClient.denyAll` and `stop` return `Boolean`, but controller cleanup blocks discard the result and always return `CleanupReport.empty()`:

```kotlin
firewall.denyAll(handle)
CleanupReport.empty()
```

and:

```kotlin
firewall.stop(handle)
CleanupReport.empty()
```

Therefore `false` clears deny/runtime debt and permits restart over stale rules.

Required fix: convert every Boolean operation with `asCleanupReport()` or change the interface to return `CleanupReport` directly. The same rule applies in initial cleanup and debt retry.

Required tests:

- `denyAll == false` creates `FIREWALL_DENY` debt;
- `stop == false` retains the firewall handle and `FIREWALL_RUNTIME` debt;
- neither result permits backend restart.

### 3. Emergency cleanup debt is silently discarded

`CleanupReport.debt` is currently a constant getter returning `null`:

```kotlin
val CleanupReport.debt: CleanupDebt? get() = null
```

`recoverSafely` calls `mergeDebt(emergency.debt)`, so service-reported emergency failures cannot create cleanup debt. The controller can lose the fact that listener/backend cleanup is unresolved.

Required fix: remove the fake extension. Either:

1. make service operations return a typed result containing both report and unresolved handles/resources; or
2. construct controller debt explicitly from the known applied/service state after each failed service operation.

Do not infer resource resolution from a generic report without preserving the relevant handle.

### 4. Terminal stop can strand service-handle debt

`terminalStopSafely` first calls `cleanupApplied`. If backend stop fails, that creates `serviceHandlePending`. It then calls `service.stopFeature`, which may retry and successfully stop the backend and service. The controller sets `serviceActivated = false`, but the pre-existing service-handle debt remains. Future retries are guarded by `serviceActivated`, so the debt may never clear.

Required fix: terminal shutdown must use one transaction/result that reports exactly which backend/listener/service resources remain. Do not separately stop the feature after creating backend debt unless a successful feature stop atomically clears the matching service/listener debt.

Required tests:

- backend stop fails once, feature stop succeeds;
- backend stop fails and feature stop fails;
- service-handle debt is never retained after the service proves the same handle destroyed;
- unresolved service debt is never left with `serviceActivated == false` and no alternative cleanup path.

## High-severity findings

### 5. Cleanup steps do not have their documented per-step timeout

`CleanupAccumulator.stepSucceeded` claims to apply a timeout but calls `block()` directly. A stuck service or firewall call can consume the whole transaction timeout. Recovery then calls cleanup again from `NonCancellable` context without a per-step timeout and can hang indefinitely.

Required fix: inject a cleanup-step timeout and apply `withTimeout` inside the accumulator. Re-throw parent cancellation; classify only the step timeout as cleanup failure.

### 6. Parent cancellation is masked during the whole transaction

`runIteration` wraps `withTimeout` inside `withContext(NonCancellable)`. Parent cancellation cannot interrupt reconciliation until the transaction completes or times out, so the `CancellationException` branch does not provide the documented prompt cancellation behavior.

Required fix: run normal reconciliation in the parent job with a timeout. Reserve `NonCancellable` for bounded cleanup/finalization only.

### 7. Reporter failure can abort cleanup after `applied` was cleared

`cleanupApplied` sets `applied = null` before cleanup. If a cleanup block throws, `CleanupAccumulator` calls `reporter.report` without containing reporter failure. A throwing reporter can abort debt construction after the applied handles have been removed from controller state.

Required fix: reporting inside cleanup must be non-throwing. Construct debt from local handles before any observer callback can escape.

### 8. Advertised endpoint is loopback

`runningState()` publishes `host = "127.0.0.1"`. A tethered laptop cannot reach the phone through loopback.

Required fix: advertise a validated address on each supported tethering downstream, or publish interface-specific endpoints. Do not guess one global address when multiple downstreams exist.

### 9. Firewall generation uses wall-clock milliseconds

`firewallConfig()` uses `System.currentTimeMillis()` while the proto declares a monotonically increasing generation used to reject stale replacements. Wall clock can move backwards and multiple updates can share one millisecond.

Required fix: controller-owned monotonic generation, scoped to daemon generation/runtime. Never use wall-clock time as protocol ordering.

### 10. Credentials are exposed by generated `toString`, equality and copies

`ProxyOnlySettings` and `ProxyBackendConfig` are data classes containing plaintext username and password. Accidental logs, crash breadcrumbs or state dumps will expose credentials.

Required fix: store credentials in a dedicated secret-bearing type with redacted `toString`, avoid including secret values in observable desired state, and pass them only at backend start through a narrowly scoped provider/handle. Add redaction tests.

### 11. Native hook has a fail-open null-state path

`vpnhotspot_prepare_outbound_socket` returns success when `state == NULL`, even though Proxy-only requires fail-closed binding. A missed state propagation can silently create a physical-network socket.

Required fix: production hook entry points must reject `NULL`, zero handle and null callback. If tests need a pass-through mode, make it explicit and unavailable in production fail-closed configuration.

### 12. Runtime-key normalization is incomplete

Only `allowedClients` are sorted. `downstreams`, client IPv4 lists and downstream addresses are not canonicalized, while `RuntimeKey` depends on downstream list order. Semantically identical snapshots can restart the backend.

Required fix: canonicalize interface names, addresses and clients; use typed IP/MAC values instead of unvalidated strings.

### 13. Duplicate observations of the same VPN can become `Multiple`

`ProxyVpnSelector` does not deduplicate by `Network`/network handle. If the same VPN is represented by more than one upstream observation, the selector fails with `MultipleVpnCandidates`.

Required fix: deduplicate current candidates by stable `Network` identity before applying zero/one/multiple policy, while still rejecting genuinely distinct VPN networks.

## Integration completeness blockers

The latest nine commits add interfaces and placeholders, not a running proxy feature:

- `ProxyService` is abstract and is not a concrete manifest-registered foreground service;
- no `ProxyServiceClient` transport/binding implementation exists;
- no Hev source pin, fork, CMake/NDK build, JNI implementation or backend exists;
- the network hook is only a header and has no call sites;
- no settings/UI or tethering integration produces `DesiredProxyState`;
- no root-daemon proxy-firewall command/runtime exists;
- Rust `build.rs` still compiles only `daemon.proto`, so `proxy_firewall.proto` is not part of daemon IPC;
- no `IptablesRule` proxy module or `firewall_cleanup` integration exists;
- no unit, host-native, instrumentation or Rust tests were added.

This is acceptable only if the commits are labeled as scaffolding and the original Phase 0 gates remain open.

## Required next sequence

1. Fix blockers 1–4 and add deterministic controller tests with fake service/firewall implementations.
2. Fix timeout/cancellation/reporting semantics and test fault injection.
3. Replace plaintext secret models and string network identities.
4. Add a concrete service/client lifecycle test without Hev.
5. Pin and audit Hev; implement the native hook with host tests before Android integration.
6. Add daemon protocol messages to the existing `daemon.proto` envelope or explicitly update both Wire and Rust generation paths.
7. Implement the independent root firewall runtime using the existing `IptablesRule` ledger and cleanup machinery.
8. Only then connect system tethering/UI and perform physical-device Phase 0 tests.

## Approval boundary

Status after this review: **changes requested**.

The branch may continue as a Phase 0 development branch, but the new code should not be merged as a completed implementation. The fail-closed blockers must be fixed first; build and test evidence is required before any claim that Steps 1–9 are complete.
