# Implementation review, round 4 — R3 remediation

Reviewed commit: `b18c621a67b652a81a6d8f29eb572416e429eb3b`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND3.md`

Scope: the commit claiming to resolve the six round-3 blockers through merge-based normalization, nested-timeout correction, daemon-acknowledged firewall sequencing, local cleanup transactions and a separate cleanup scope.

## Verdict

**Changes requested.** The commit fixes the named-argument/import errors, improves nested-timeout ownership, makes `cleanupApplied()` locally assemble its result, adds a daemon-acknowledgement interface and rethrows credential-fetch cancellation. However, it introduces a critical cleanup regression, does not actually provide terminal cleanup ownership, and leaves daemon-restart and identity-validation gaps.

The branch must remain a draft Phase 0 development branch.

## Resolution audit

| Round-3 item | Status | Round-4 assessment |
| --- | --- | --- |
| B1 Kotlin named args/import | Fixed at source level | The three `firewallConfig` calls name `generationCounter`, and `CancellationException` is imported. Build evidence is still absent. |
| B2 nested timeout ownership | Partially fixed, with regression | `withTimeoutOrNull` avoids confusing the inner timeout with the outer timeout and debt retry is no longer fully `NonCancellable`. But ordinary exceptions are no longer caught by `CleanupAccumulator.stepSucceeded` or the retry `attempt` helper, so cleanup aborts instead of recording debt and continuing. |
| B3 daemon-acknowledged generation | Partially fixed at interface level | `cleanOrDenyBeforeRestart()` now returns `Long?`, but no daemon/proto implementation or acknowledgement test exists. Old firewall handles are still reused across daemon-generation changes. |
| B4 atomic cleanup outcome | Fixed only for `cleanupApplied()` | `cleanupApplied()` no longer mutates global debt. Debt retry still commits `updated` globally and then calls `mergeDebt()` for emergency debt, so retry remains a split transaction. |
| B5 persistent terminal cleanup owner | **Not fixed** | Retry timers launch in `cleanupScope`, but they only send an event to the controller's `events` channel. After the worker exits, no consumer remains, so terminal cleanup debt is not retried. The default `cleanupScope = scope` also preserves the original failure mode. |
| B6 credential-fetch cancellation | Fixed | `CancellationException` is rethrown before ordinary credential-provider failures are mapped. |
| Merge-based normalization | Partially fixed | Duplicate groups are merged, but address parsing can perform DNS, invalid MACs are retained, empty client bindings survive, and downstreams still retain only one address. |
| Review audit preservation | Regressed, restored separately | `b18c621a` deleted `REVIEW_IMPLEMENTATION_ROUND3.md`; the file was restored in the follow-up review commit. |

## Blockers

### 1. Ordinary cleanup exceptions now discard tracked resources

`CleanupAccumulator.stepSucceeded()` now does:

```kotlin
val r = withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { block() }
```

but has no `try/catch` for ordinary non-cancellation exceptions. The local retry `attempt()` helper has the same behavior.

This is a fail-closed regression because `cleanupApplied()` clears `applied` before running any cleanup I/O:

```kotlin
val current = applied ?: return ...
applied = null
```

Failure sequence:

1. a listener/firewall runtime is active;
2. `cleanupApplied()` copies the handles into `current` and sets `applied = null`;
3. `firewall.denyAll`, `service.stopBackend`, or `firewall.stop` throws an ordinary exception;
4. `stepSucceeded()` lets the exception escape;
5. `cleanupApplied()` never constructs or returns debt;
6. recovery calls `cleanupApplied()` again, but `applied` is already null;
7. live native/firewall resources can remain with no tracked handle or cleanup debt.

Required fix:

```kotlin
suspend fun stepSucceeded(...): Boolean = try {
    val report = withTimeoutOrNull(STEP_TIMEOUT) { block() }
        ?: return recordTimeout(...)
    record(report)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    recordFailure(...)
    false
}
```

Apply the same non-cancellation exception handling to:

- debt retry `attempt()`;
- daemon sanitation during debt retry;
- feature-stop retry.

Cleanup must attempt every independent step and construct itemized debt even when one step throws.

Required tests:

- each cleanup operation throws before returning a report;
- later independent cleanup steps are still attempted;
- every copied handle is represented either as resolved or in returned debt;
- no path clears `applied` and loses all handle ownership.

### 2. `cleanupScope` does not provide a surviving cleanup executor

`scheduleDebtRetry()` launches the timer in `cleanupScope`, but the timer only performs:

```kotlin
events.send(RetryCleanupDebt(...))
```

The `events` channel is consumed only by the controller worker loop. When `terminalStopSafely()` runs from that worker's `finally`, the worker exits immediately afterward. A later timer can send successfully into the open conflated channel, but no coroutine consumes the event.

Therefore a separate timer scope does not solve terminal cleanup ownership.

Required fix: move cleanup debt and retry execution to a component that survives the controller worker, for example:

- a persistent service-owned `CleanupSupervisor` with its own consumer loop; or
- execute the retry transaction directly in the persistent cleanup scope using serialized ownership, without routing through the dead worker channel.

The default `cleanupScope = scope` should be removed for production construction; a same-lifetime scope cannot satisfy the contract.

Required tests:

- worker cancellation with backend-stop failure;
- worker cancellation with firewall-stop failure;
- retry happens after worker completion;
- debt eventually clears or remains observable under a surviving owner;
- no retry event is sent to an unconsumed channel.

### 3. Daemon restart can reuse stale firewall handles

On daemon generation change, the controller sanitizes the daemon and installs a new generation base. It does not invalidate or restart the current `AppliedProxyState`.

`RuntimeKey` does not include daemon generation or firewall epoch. If the VPN/settings/downstreams are otherwise unchanged, the controller can enter the fast path and call `firewall.replace()` using a `ProxyFirewallHandle` created by the previous daemon generation.

Cleanup debt has the same provenance problem: `ProxyFirewallHandle` is not tagged with daemon generation/epoch, so retry may submit an old handle to a new daemon.

Required fix:

- tag firewall handles and debt with daemon generation/epoch;
- treat any generation change as invalidating existing firewall handles;
- force deny-first firewall runtime recreation before publishing `Running`;
- define daemon sanitation dominance for old-generation firewall debt.

Required tests:

- daemon restarts while backend/listener remains alive;
- old firewall handle is never passed to the new daemon;
- listener remains denied until a new runtime is created and acknowledged;
- old-generation debt is resolved by daemon sanitation rather than stale handle calls.

### 4. Firewall epoch contract is still only declarative

Changing `cleanOrDenyBeforeRestart()` from `Boolean` to `Long?` improves the interface, but the standalone proxy-firewall proto and Rust daemon are not integrated and no implementation proves that the returned value is:

- issued by the daemon;
- strictly newer than every prior accepted generation;
- atomically coupled to ledger reset and deny installation;
- resistant to delayed commands from a previous app process.

The contract should specify whether the returned value is the last accepted base or the first usable sequence. The controller currently calls `incrementAndGet()`, so the first sent generation is `epoch + 1`.

Required evidence before this item is considered closed:

- daemon/proto implementation;
- stale-command rejection tests across app-process restart;
- acknowledged epoch/reset tests;
- overflow and ordering policy.

## High-severity findings

### 5. Address validation performs name resolution

`isRoutableIpv4()` and `normalizeIpv4()` call `InetAddress.getByName()` on untrusted strings. This API may resolve hostnames rather than parsing an IPv4 literal. Normalization can therefore:

- perform DNS on the controller collector thread;
- block or vary according to network state;
- accept a hostname that resolves to IPv4 as a downstream address;
- produce nondeterministic runtime keys.

Use a strict IPv4-literal parser with exactly four decimal octets. Do not invoke DNS.

### 6. Invalid MAC addresses remain in ACL input

`canonicalizeMac()` returns a trimmed uppercase fallback for malformed input. Invalid, broadcast or multicast MAC values can therefore survive normalization and be passed to firewall configuration.

Required fix:

- parse exactly six octets;
- reject malformed, multicast and broadcast addresses;
- canonicalize valid values from bytes;
- fail closed or drop the entire invalid client record with a visible diagnostic.

### 7. Clients with no valid IPv4 binding survive normalization

After invalid addresses are filtered, an `AllowedClient` with an empty `ipv4Addresses` list is retained. The plan requires interface + MAC + IPv4 enforcement. An empty list must not be ambiguously interpreted as a wildcard.

Reject or omit such clients, and test the concrete firewall semantics.

### 8. Downstream merge still retains only one IPv4 address

The merge groups observations but stores only the first accepted address because `ManagedDownstream` still has a single nullable `ipv4Address`. This is not a complete union and can create ordering-dependent behavior when an interface legitimately has more than one relevant IPv4 address.

Either model a canonical list/set of addresses or explicitly prove and enforce a one-address invariant at the production adapter.

### 9. `NoReachableDownstreamAddress` remains unreachable

The reason exists in the model, but zero validated endpoints publish `WaitingForTethering`. Either remove the unused reason or publish a typed fail-closed/waiting state that distinguishes:

- no tethering interface;
- tethering interface present but no safe reachable address.

### 10. Emergency failure diagnostics can be duplicated

`cleanupApplied()` appends emergency report failures to `acc.failures`, while the returned emergency debt already carries the same failures. `mergeUnresolved()` concatenates failure lists, producing duplicate entries.

Deduplicate by cleanup step/resource identity or ensure each failure is added once.

### 11. Debt retry remains a split global transaction

`retryCleanupDebtSafely()` assigns `cleanupDebt = updated...` and then merges emergency debt through `mergeDebt()`. This avoids the previous overwrite but is still two global commits and resets generation/attempt metadata during the second merge.

Build one local combined debt and commit it once.

### 12. Successful daemon-clean retry does not mark the generation sanitized

When debt retry receives an epoch, it resets `firewallGeneration` and clears `daemonCleanPending`, but does not update `sanitizedDaemonGeneration`. The following reconciliation sanitizes the same daemon generation again.

This causes redundant ledger resets and complicates epoch reasoning. Record the acknowledged daemon generation together with the epoch.

## Verified corrections

- named Kotlin arguments are corrected;
- the missing cancellation import is present;
- credential-fetch cancellation is rethrown;
- outer transaction cancellation is no longer deliberately caught as a step timeout;
- full debt retry is no longer wrapped in `NonCancellable`;
- `cleanupApplied()` itself no longer mutates global cleanup debt;
- duplicate client/downstream observations are grouped rather than simply discarded;
- loopback/unspecified/multicast/link-local IPv4 values are filtered after parsing;
- the firewall interface now expresses a daemon acknowledgement value.

## Build and test status

- GitHub reports no status checks for `b18c621a67b652a81a6d8f29eb572416e429eb3b`.
- No pull-request workflow runs exist for the reviewed commit.
- No deterministic controller, normalization, native-hook or Rust tests were added in the remediation commit.
- Source inspection found no remaining named-argument/import error, but Kotlin compilation and Android assembly remain unverified.

Required evidence:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

## Required next sequence

1. Restore attempt-all cleanup behavior while preserving cancellation propagation.
2. Move terminal debt to a real surviving cleanup executor/owner.
3. Invalidate old firewall handles across daemon generations and define sanitation dominance.
4. Implement and test the daemon-issued epoch protocol in proto/Rust.
5. Replace DNS-based address parsing and fallback MAC normalization with strict typed parsers.
6. Make debt retry a single local transaction and eliminate duplicate failures.
7. Add deterministic unit tests for all R1–R4 failure sequences.
8. Run and attach compilation, unit-test and assembly evidence.
9. Only then continue with Hev/JNI and production firewall integration.

## Approval boundary

Status after round 4: **changes requested**.

The design direction remains valid, but `b18c621a` does not close all round-3 findings and introduces a critical lost-handle cleanup regression. It must not be treated as a completed or build-verified Phase 0 implementation.
