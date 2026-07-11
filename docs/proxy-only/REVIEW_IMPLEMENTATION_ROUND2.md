# Implementation review, round 2 — R1 fix commit

Reviewed commit: `753e2886b8ad8d89d7eb0843a40f1690b7bb33b3`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND1.md`

Scope: the single commit that claims to fix findings #1–#13 in the Kotlin scaffold and native hook header.

## Verdict

**Changes requested.** The commit fixes several findings correctly, but the scaffold is not buildable as committed and three fail-closed/lifecycle blockers remain. No CI or workflow run exists for the reviewed head, and a local Gradle build could not be run in the review environment because that environment could not resolve GitHub.

The branch must remain a draft Phase 0 development branch.

## Resolution audit

| R1 finding | Status | Round-2 assessment |
| --- | --- | --- |
| #1 probe failure fallthrough | Fixed | Probe evaluation is mapped to `ProbeStartDecision`; every failure returns before firewall allow/`Running`. |
| #2 firewall false treated as success | Fixed in interface/controller | `denyAll` and `stop` return `CleanupReport`, and cleanup retains debt on failures. Concrete implementation and tests still do not exist. |
| #3 emergency debt discarded | Partially fixed | `CleanupOutcome` now carries debt, but debt merged during retry can be overwritten later in the same retry transaction. |
| #4 terminal stop strands service debt | **Not fixed** | Retry may call `stopFeature` while `serviceHandle`/listener debt is still unresolved, then set `serviceActivated=false` while retaining the handle debt. |
| #5 per-step cleanup timeout | Partially fixed | Accumulator and retry operations have timeouts, but parent cancellation is swallowed and several cleanup calls remain outside the timed helper. |
| #6 parent cancellation masked | Partially fixed | Normal reconciliation is cancellable, but cleanup catches still convert parent cancellation to ordinary failure. |
| #7 reporter failure interrupts cleanup | Fixed | Reporter calls are contained by non-throwing wrappers. |
| #8 loopback endpoint | **Partially fixed** | First downstream IPv4 is used, but the code still publishes `Running(127.0.0.1)` when no address exists and guesses one endpoint for multiple downstreams. |
| #9 wall-clock generation | Partially fixed / not integrated | An `AtomicLong` replaces wall clock, but call sites do not pass it and the counter is not scoped/recovered across an app-process restart with a surviving daemon. |
| #10 plaintext credentials | Partially fixed / not integrated | Credentials left observable desired state and `toString` is redacted, but no credential provider is wired and `ProxyCredentials` remains a data class with generated `copy`/component methods. |
| #11 native NULL-state fail-open | Fixed in header | Production helper rejects NULL/missing state. Call sites and host tests still do not exist. |
| #12 normalization | Partially fixed | Lists are sorted, but string MAC/IP/interface values remain unvalidated; duplicate interfaces/clients/addresses are not canonicalized or merged. |
| #13 duplicate VPN observations | Fixed for candidate count | `distinctBy(networkHandle)` prevents false `Multiple`; interface properties should still be refreshed/merged rather than taking an arbitrary first observation. |

## Blockers

### 1. The Kotlin scaffold does not compile after the API changes

`DesiredProxyState.firewallConfig` now requires an `AtomicLong generationCounter`, but the controller still calls it with only `denyAll` in the fast path, startup deny path and final allow path.

`DesiredProxyState.backendConfig` now requires `ProxyCredentials`, but the controller still calls it with only the VPN upstream. The controller constructor also has no credential provider or credential handle from which to obtain the missing argument.

Required fix:

- inject a narrow `ProxyCredentialProvider` or secret handle into the controller/service boundary;
- fetch credentials only immediately before backend start;
- pass `firewallGeneration` to every firewall-config construction;
- add `compileDebugKotlin`/`assembleDebug` evidence before claiming the scaffold builds.

Required tests:

- credential lookup failure never starts the backend and remains listener-free;
- every firewall start/replace receives a strictly increasing generation;
- no secret value appears in desired-state or state-sink objects.

### 2. Terminal cleanup can still strand service-handle debt

`terminalStopSafely` correctly creates `featureStopPending` when service/listener debt exists. However, the retry loop attempts `backend_stop`, then emergency close, and then executes `stopFeature` whenever `featureStopPending && serviceActivated`.

It does **not** require `serviceHandle == null && !listenerPending` before `stopFeature`.

Failure sequence:

1. first `backend_stop` retry fails;
2. emergency listener close succeeds;
3. `stopFeature` retries the backend stop internally and succeeds;
4. controller sets `serviceActivated=false` and clears `featureStopPending`;
5. the local `serviceHandle` variable is still non-null;
6. updated cleanup debt retains `SERVICE_HANDLE`, but future retries cannot call the service because `serviceActivated=false`.

This reintroduces R1 finding #4.

Required fix:

- only call `stopFeature` after controller-owned service/listener obligations are resolved; **or**
- make `stopFeature` return a typed outcome that atomically proves which backend handle was destroyed and clears the matching debt before setting `serviceActivated=false`.

Required tests:

- backend stop fails, emergency close succeeds, feature stop would succeed: feature stop must not run until service-handle debt is cleared;
- feature stop success atomically clears the exact matching handle debt;
- no state can contain `serviceHandlePending != null` with `serviceActivated == false` and no alternative cleanup mechanism.

### 3. Parent cancellation is still swallowed in cleanup

`CleanupAccumulator.stepSucceeded` catches `TimeoutCancellationException`, then catches `Throwable`. A parent `CancellationException` therefore falls into the second catch and becomes a cleanup failure instead of being re-thrown.

The local `attempt` helper in `retryCleanupDebtSafely` has the same structure. This matters because debt retry is also called directly from normal reconciliation before the explicit retry event path.

Required fix:

```kotlin
catch (timeout: TimeoutCancellationException) {
    // classify the step timeout
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    // classify ordinary failure
}
```

Apply the same ordering to every cleanup helper.

Also place explicit timeouts around cleanup operations that bypass the accumulator, including emergency listener close and terminal feature stop. Non-cancellable finalization must remain bounded.

Required tests:

- parent cancellation during each cleanup step exits the worker and reaches terminal finalization;
- step timeout creates debt without cancelling the parent;
- emergency-close and feature-stop calls cannot hang terminal shutdown indefinitely.

## High-severity findings

### 4. Emergency debt can be overwritten during retry

`retryCleanupDebtSafely` calls `mergeDebt(outcome.debt)` after emergency listener close. At the end of the same method, it assigns `cleanupDebt = updated...`, where `updated` was derived from the pre-merge debt snapshot.

This can discard resources or generation/failure information added by the emergency outcome. It is especially unsafe if the original debt has listener uncertainty but no service handle and the service outcome supplies a concrete retained handle.

Required fix: use one local cleanup-debt accumulator and commit global debt once at the end, or re-read and merge the current global debt before final assignment. Do not mutate global debt mid-transaction and overwrite it later.

### 5. Stale service handles are reported as successful cleanup

`ProxyService.stopBackend` returns `CleanupReport.staleHandle(id)` for a mismatched handle. `staleHandle` contains only context and no failure, so cleanup treats the operation as successful and can clear service/listener debt without stopping the actual backend.

This violates the documented post-split rule that stale/mismatched handles are unresolved failures.

Required fix: return a critical typed failure for a mismatched live handle, retain both the expected and observed identity in redacted diagnostics, and require explicit service reconciliation or full service teardown.

Required tests:

- stale handle never clears `SERVICE_HANDLE` or `LISTENER` debt;
- backend with a different live handle remains fail closed;
- exact matching handle stop remains idempotent.

### 6. Endpoint selection can still publish an unreachable `Running` state

Using the first downstream address is better than always using loopback, but falling back to `127.0.0.1` still advertises an endpoint that no tethered client can reach. Choosing the first downstream also makes the result dependent on list ordering and ignores other active tethering interfaces.

Required fix: model one validated endpoint per downstream, or fail closed with an explicit `NoReachableDownstreamAddress` state until an address is known. Never publish `Running` with loopback as a client endpoint.

### 7. Firewall generation is not restart-safe

A controller-local `AtomicLong` is monotonic only for the lifetime of that controller instance. If the app process restarts while the root daemon survives, the counter returns to zero and can generate values lower than the daemon has already observed.

Required fix: scope generation to the daemon handshake/generation. Either have the daemon issue the epoch and accept per-epoch sequence numbers, or reset its proxy ledger during the mandatory sanitation handshake and return an acknowledged starting sequence.

### 8. Credential hardening is incomplete

`ProxyCredentials.toString()` is redacted, but it remains a data class. Generated `copy`, `component1`, `component2`, equality and hash behavior are unnecessary for a secret container. More importantly, no provider/handle is wired into the controller, which is also the source of the compile failure.

Required fix: use a non-data secret-bearing class or opaque credential handle, make lifetime/zeroization expectations explicit, and keep the secret out of long-lived state and error objects.

### 9. Normalization remains string-based and incomplete

The downstream normalization currently copies each downstream without changing its address and only sorts by interface name. Client IP strings are sorted lexically, but values are not parsed, canonicalized, validated or deduplicated. Duplicate MAC entries are not merged.

Required fix: introduce typed MAC/IPv4 values, normalize case/format, reject invalid values, merge duplicate clients/interfaces and sort canonical byte representations.

### 10. VPN deduplication should merge fresh properties

Deduplicating by network handle fixes the false-multiple count. However, `distinctBy` keeps whichever observation appears first and can retain stale or incomplete interface names. Selection should fresh-read `LinkProperties` for the chosen `Network`, or group duplicate observations and merge validated interface names deterministically.

## Items verified as corrected

- Probe failure no longer falls through to firewall allow or `Running`.
- Firewall deny/stop operations now expose cleanup failures through `CleanupReport`.
- Reporter callbacks are contained.
- Native production hook rejects NULL state and missing binding data.
- Duplicate observations of one VPN no longer directly create `MultipleVpnCandidates`.

## Build and test status

- GitHub reports no combined status checks for the reviewed head.
- No pull-request workflow runs exist for the reviewed head.
- No controller/native/Rust tests were added in this commit.
- A local Gradle build was not possible in the review environment because it could not resolve `github.com`; therefore build status is **unverified**, independently of the source-level signature mismatches described above.

## Required next sequence

1. Fix the compile-time API mismatches and add a credential-provider boundary.
2. Fix terminal-stop ordering and stale-handle semantics.
3. Re-throw parent cancellation and bound every non-cancellable cleanup operation.
4. Replace mid-retry global debt mutation with a single itemized local transaction.
5. Add deterministic unit tests covering all R1 and R2 failure sequences.
6. Run and record `compileDebugKotlin`, unit tests and `assembleDebug`.
7. Only after these gates pass, continue with Hev/JNI and root-firewall integration.

## Approval boundary

Status after round 2: **changes requested**.

The design direction remains valid, but the latest commit is not ready to be treated as a completed remediation of findings #1–#13. The branch should remain draft until the blockers above are fixed and build/test evidence is attached.
