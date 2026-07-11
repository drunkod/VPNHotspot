# Implementation review, round 5 — R4 remediation

Reviewed commit: `09ccd4e42e1da60cbfcfdd5fcb9f94449706593c`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND4.md`

Scope: the commit claiming to close all round-4 findings through attempt-all cleanup, direct cleanup-scope retries, daemon generation in the runtime key, strict IPv4/MAC parsing, typed no-address state and single-commit debt retry.

## Verdict

**Changes requested.** The commit genuinely restores attempt-all cleanup, removes DNS from IPv4 parsing, rejects several unsafe MAC/address forms, directly executes cleanup retries in a surviving scope, publishes `NoReachableDownstreamAddress`, and commits retry debt once. However, daemon-restart provenance is still unsafe, the epoch protocol is not implemented in daemon IPC/Rust, and the direct cleanup executor can still die permanently on an uncaught state/invariant failure.

The branch remains a draft Phase 0 scaffold.

## Resolution audit

| Round-4 item | Status | Round-5 assessment |
| --- | --- | --- |
| #1 attempt-all cleanup | Fixed in helper paths | `CleanupAccumulator.stepSucceeded()` and retry `attempt()` catch ordinary exceptions, retain debt and continue independent steps. Deterministic tests are still absent. |
| #2 surviving cleanup executor | Partially fixed | Retry now executes directly in `cleanupScope` under `stateMutex`, rather than sending to a dead worker channel. No production owner/wiring or lifecycle test proves that the supplied scope actually survives the worker/service transition. |
| #3 daemon restart / stale handles | **Not fixed** | `RuntimeKey.daemonGeneration` forces a key mismatch, but cleanup then calls `denyAll()`/`stop()` with the old daemon's firewall handle. Existing cleanup debt is also retried before the sanitation gate. |
| #4 daemon epoch protocol | **Not fixed** | The Kotlin interface returns `Long?`, but `proxy_firewall.proto` has no sanitation request/ack response and the Rust build still compiles only `daemon.proto`. |
| #5 strict IPv4 parsing | Fixed for literals | DNS-based parsing is removed. The parser is deterministic and rejects loopback, unspecified, multicast/reserved and link-local ranges. |
| #6 strict MAC parsing | Partially fixed | Malformed, multicast and broadcast values are dropped. All-zero MAC is still accepted, and invalid records are dropped silently without a visible diagnostic. |
| #7 empty client bindings | Fixed | Clients with no valid IPv4 binding are removed. |
| #8 downstream address union/invariant | Not proven | The model still stores one nullable IPv4 and takes the first valid observation. The claimed one-address invariant is documented but not enforced by a production adapter or test. |
| #9 no-address state | Fixed | Interfaces present with no valid endpoint now publish `FailClosed(NoReachableDownstreamAddress)`. |
| #10 emergency failure duplication | Fixed in `cleanupApplied()` | Emergency report failures are no longer added to the primary accumulator. |
| #11 retry single commit | Fixed for normal completion | Retry constructs one local combined debt and assigns `cleanupDebt` once. An invariant exception during merge still kills the retry job before commit. |
| #12 sanitized generation update | Fixed | Successful daemon-clean retry updates `sanitizedDaemonGeneration`. |
| audit preservation | Regressed again | `09ccd4e4` deleted both round-3 and round-4 review files. They were restored by follow-up review commits. |

## Blockers

### 1. Daemon generation changes still submit stale firewall handles

Adding `daemonGeneration` to `RuntimeKey` prevents the fast path from treating the old and new daemon as the same runtime. It does **not** make old handles valid in the new daemon.

Current sequence:

1. daemon generation changes;
2. controller calls `cleanOrDenyBeforeRestart()` on the new daemon and receives an epoch;
3. runtime key differs;
4. `cleanupApplied(..., daemonAvailable = true)` runs;
5. it calls `firewall.denyAll(oldHandle)` and `firewall.stop(oldHandle)` against the new daemon;
6. failures create debt containing the stale old handle;
7. startup is blocked while retry keeps submitting that stale handle.

There is an earlier variant: reconciliation retries existing cleanup debt **before** it reaches the daemon sanitation gate. Old-generation debt can therefore be sent to the new daemon first.

Required fix:

- add daemon generation/epoch provenance to `ProxyFirewallHandle`, `AppliedProxyState` and firewall cleanup debt;
- run generation-change sanitation before retrying firewall debt;
- after successful sanitation, treat firewall handles/debt from older generations as dominated/resolved without calling them on the new daemon;
- preserve service/listener debt separately;
- create a new deny-first runtime before publishing `Running`.

Required tests:

- daemon restarts with a complete active runtime;
- daemon restarts with pending firewall deny/stop debt;
- no old handle is sent to the new daemon;
- sanitation resolves old-generation firewall debt while retaining unrelated service debt;
- a new deny-first runtime is required before allow rules.

### 2. The epoch acknowledgement remains declarative only

The standalone `proxy_firewall.proto` contains a configuration message with one `generation` field, but no sanitation/reset request or acknowledgement response. It is not compiled by the Rust daemon build, which still compiles only `daemon.proto`.

Therefore no implementation proves that `cleanOrDenyBeforeRestart()`:

- atomically installs deny-all and resets the ledger;
- returns a daemon-issued epoch;
- rejects delayed commands from a prior app process;
- handles overflow and ordering correctly.

Required fix/evidence:

- integrate proxy-firewall commands into the existing daemon envelope;
- add sanitation request and acknowledged epoch response;
- implement ledger reset/rejection in Rust using the existing `IptablesRule` machinery;
- test stale command rejection, process restart, daemon restart and overflow boundaries.

### 3. The cleanup retry coroutine lacks a top-level failure boundary

Individual I/O operations are contained, but the direct cleanup coroutine catches only `CancellationException` around the whole transaction. Other failures can still terminate the persistent retry without rescheduling.

A concrete path is `CleanupDebt.mergeUnresolved()`, which uses `require()` for conflicting live handles. If a stale controller handle and service-reported current handle differ, the merge throws. The cleanup job exits, `cleanupDebt` remains unresolved and no next retry is scheduled.

Required fix:

- add a top-level `try/catch` around the serialized retry transaction;
- rethrow cancellation;
- on ordinary failure, preserve the pre-transaction debt, append a typed supervisor failure and schedule bounded backoff;
- replace `require()`-driven cleanup conflicts with an explicit fail-closed conflict/debt state or full service/daemon reconciliation path.

Required tests:

- conflicting service handles during emergency close;
- conflicting firewall provenance;
- state publication failure;
- invariant/merge exception;
- each failure remains observable and retries continue.

### 4. Persistent cleanup ownership is still only a constructor contract

Requiring a separate `cleanupScope` is better than defaulting it to the worker scope, but there is no concrete production construction proving that the scope:

- belongs to a foreground/service or application lifecycle that outlives the controller worker;
- remains active after worker cancellation;
- is cancelled during final application/service teardown;
- does not leak the controller indefinitely.

`CleanupDebt.serviceWasActivated` is recorded but not consumed by retry logic; retries still depend on the controller's mutable `serviceActivated` field.

Required fix/evidence:

- provide the concrete owner (preferably service-owned cleanup supervisor);
- define start/stop lifecycle and persistence expectations;
- test worker cancellation, service process survival, final service teardown and no coroutine leak.

## High-severity amendments

### 5. Downstream one-address behavior remains order-dependent

`ManagedDownstream` still stores one address and normalization picks the first valid observation. The comment says the production adapter must enforce one DHCP-server address, but no adapter or validation enforces it.

Either use a canonical address set/list or reject conflicting multiple valid addresses with a typed diagnostic. Add tests for observation-order reversal.

### 6. Identity validation is incomplete

- all-zero MAC (`00:00:00:00:00:00`) remains accepted;
- invalid MAC/client records are silently dropped, so the user receives no visible reason why a client is not allowed;
- empty or malformed interface names are retained and can reach firewall configuration.

Add strict interface-name validation, reject zero MAC, and expose normalization diagnostics without leaking secrets.

### 7. Retry scheduling self-cancels the current retry job

When a retry finds unresolved debt, it calls `scheduleDebtRetry()` while still running as `retryJob`. That method cancels `retryJob`—the current coroutine—then launches a replacement. This can work because the replacement is launched before the cancellation is observed, but it is fragile and obscures ownership.

Prefer clearing current-job ownership before the transaction or scheduling the next retry from `finally` without cancelling the executing job. Add a deterministic repeated-retry test.

### 8. Review history was deleted again

The remediation commit removed `REVIEW_IMPLEMENTATION_ROUND3.md` and `REVIEW_IMPLEMENTATION_ROUND4.md`. Both were restored in follow-up documentation commits. Future implementation commits must not delete review history.

## Verified corrections

- ordinary cleanup exceptions are converted into debt and independent cleanup continues;
- inner step timeout versus parent cancellation semantics are preserved;
- cleanup retry executes directly instead of routing through an unconsumed channel;
- strict IPv4-literal parsing performs no DNS;
- malformed/multicast/broadcast MAC records are removed;
- clients without IP bindings are removed;
- no-address and no-interface states are distinguished;
- emergency failure duplication is removed in `cleanupApplied()`;
- retry debt is committed once during normal completion;
- successful daemon clean records the sanitized generation.

## Build and test status

- GitHub reports no status checks for `09ccd4e42e1da60cbfcfdd5fcb9f94449706593c`.
- No pull-request workflow runs exist for the reviewed commit.
- No deterministic controller, normalization, daemon/proto, native-hook or Rust tests were added.
- Source inspection found no obvious named-argument/import regression in the changed files, but Kotlin compilation and Android assembly remain unverified.

Required evidence:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

## Required next sequence

1. Implement daemon-generation provenance and sanitation dominance for applied state and cleanup debt.
2. Integrate the epoch/reset protocol into daemon proto/Rust and test stale-command rejection.
3. Add a top-level persistent cleanup-supervisor failure boundary.
4. Wire a concrete cleanup lifecycle owner and test worker/service shutdown behavior.
5. Enforce or replace the downstream one-address invariant and complete identity diagnostics.
6. Add deterministic tests for all R1–R5 failure sequences.
7. Run and attach Kotlin compilation, unit-test and debug assembly evidence.
8. Only then continue with Hev/JNI and production firewall work.

## Approval boundary

Status after round 5: **changes requested**.

The remediation improves the scaffold substantially, but commit `09ccd4e4` does not close daemon-restart safety or persistent cleanup ownership and must not be treated as a completed or build-verified Phase 0 implementation.
