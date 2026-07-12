# Implementation review, round 9 — R8 remediation

Reviewed commit: `b44573ec4c8d2c82babc20baa495bbd8e285fa8a`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND8.md`

Scope: mismatched firewall-start ownership, global cleanup-debt commitment, sanitation-generation races, non-throwing debt conflicts and fast-path provenance validation.

## Verdict

**Changes requested.** The four round-8 changes are present and substantially improve fail-closed ownership: a mismatched start handle is retained before validation, `cleanupApplied()` merges global debt before clearing `applied`, debt-retry sanitation binds its acknowledgement to a captured generation, and the complete-runtime fast path checks session/epoch provenance.

However, the new non-throwing service-handle conflict policy can permanently deadlock cleanup and discard the only actionable observation of the actual service backend. The main reconciliation sanitation gate also still has the generation race that was fixed only in debt retry, and provenance is not revalidated before every firewall-handle IPC.

The PR must remain a draft Phase 0 scaffold.

## Resolution audit

| Round-8 item | Status | Round-9 assessment |
| --- | --- | --- |
| Track mismatched `firewall.start()` acknowledgement | Fixed at controller level | The returned handle is stored in `partial.firewall` before validation. Mismatch invalidates session/epoch markers; cleanup records `daemonCleanPending` without issuing handle-specific IPC. |
| Commit global debt before clearing `applied` | Fixed for non-throwing merge | `cleanupApplied()` now merges local debt into global debt before clearing `applied`; callers no longer perform the merge afterward. |
| Bind retry sanitation to captured daemon generation | Fixed in debt retry | Retry captures the generation before IPC and rejects the acknowledgement when the latest observed generation changed during the call. |
| Validate fast-path provenance | Fixed for the complete-runtime fast path | Fast-path `replace()` requires exact cached session/epoch equality. Other handle IPC paths still need authoritative/current-session protection. |
| Non-throwing firewall conflict | Directionally correct | Conflicting firewall handles are replaced by `daemonCleanPending`, which is a stronger recovery action. |
| Non-throwing service conflict | Not safe | It keeps one arbitrary handle, discards the other actionable handle, and sets `featureStopPending`, but retry ordering prevents `feature_stop` until the retained handle clears. |
| Daemon protocol implementation | Not fixed | Session/epoch/start/sanitation semantics remain Kotlin interfaces only; proto/Rust do not enforce them. |
| Persistent cleanup ownership | Not fixed | `cleanupScope` remains an abstract constructor contract and retry still relies on mutable `serviceActivated` rather than authoritative service state. |

## Blockers

### 1. Service-handle conflict resolution can deadlock forever

`CleanupDebt.mergeUnresolved()` resolves two different service handles by keeping the existing handle and setting `featureStopPending = true`.

The retry path then:

1. calls `stopBackend(retainedHandle)`;
2. retains the handle when the service reports it as stale;
3. may obtain the actual current handle from emergency-close debt;
4. merges again and again keeps the original retained handle;
5. refuses to execute `feature_stop` while `serviceHandle != null` or `listenerPending` is true.

Therefore the full-service teardown promised by the conflict policy is unreachable when the retained handle is stale. Which handle is retained depends on merge order, so cleanup success is nondeterministic.

The conflict also does not force `listenerClosePending = true`, even though two different backend identities mean listener ownership is unknown.

Required fix: represent service conflict as an explicit authoritative-reconciliation state rather than choosing one handle. Safe options include:

- retain both observations in a typed `ServiceHandleConflict` debt and query the service for its authoritative backend handle;
- clear concrete handle ownership, set listener/service state unknown, and permit a service-owned `stopFeature`/full teardown operation that does not require the controller's stale handle;
- add a service operation returning a typed teardown acknowledgement that identifies the backend actually removed.

On successful authoritative full teardown, clear service-handle, listener and feature-service debt together. On failure, retain conflict debt and retry.

Required tests:

- existing debt handle A plus applied/service-reported handle B;
- A stale/B current and A current/B stale;
- conflict with listener marked open and closed;
- conflict after worker termination;
- no conflict can leave `featureStopPending` permanently blocked by an arbitrarily retained handle.

### 2. The normal sanitation gate still has an in-flight generation race

Round 8 fixed the race inside `retryCleanupDebtSafely()`, but the primary sanitation gate still calls `cleanOrDenyBeforeRestart()` using `next.daemonGeneration` and commits the result without checking whether `latestSnapshot` changed during the call.

The collector updates `latestSnapshot` outside `stateMutex`, so this sequence remains possible:

1. reconciliation starts for daemon generation G1;
2. sanitation IPC begins;
3. the collector observes G2 while IPC is suspended;
4. sanitation returns a session/epoch acknowledgement;
5. the controller records it as sanitation for G1 and continues startup even though G2 is now the latest desired daemon generation.

Depending on transport rebinding, the acknowledgement may actually come from G1 or G2. The controller cannot safely infer which from the flow generation alone.

Required fix:

- capture the expected daemon generation/session before primary sanitation;
- reject or defer the result when the latest observed generation changed;
- do not proceed to firewall start/backend startup on a stale snapshot;
- preferably bind the firewall client request to an authoritative daemon connection/session token and validate that token in the response.

Use the same generation-race tests for both primary sanitation and debt-retry sanitation.

### 3. Provenance is checked only on the fast path, not before every handle IPC

The fast path now checks cached `(sessionId, epoch)` before `replace()`, but other calls can still use a handle after the daemon changes while an operation is suspended:

- the final deny-to-allow `firewall.replace()` after backend probes;
- `denyAll()` and `stop()` during cleanup;
- subsequent handle operations that may be added to the client.

The collector can observe a daemon restart while reconciliation holds `stateMutex`, because `latestSnapshot` is updated outside the mutex. Cached sanitation markers can therefore still match an old handle even though the transport now reaches a new daemon.

Controller-side checks should verify the expected observed generation immediately before every handle IPC and convert mismatch to sanitation debt. This reduces exposure, but only a daemon-enforced session/epoch token on every command fully closes the check-to-use race.

### 4. The session/epoch protocol remains interface-only

`SanitationResult(sessionId, epoch)` and `ProxyFirewallHandle(sessionId, epoch, id)` express the intended contract, but the standalone proto and Rust daemon still do not implement:

- sanitation/reset request and acknowledgement;
- authoritative daemon session/boot identity;
- epoch-qualified start response;
- expected session/epoch on replace, deny and stop;
- stale-session/epoch rejection;
- atomic deny installation plus ledger reset;
- integer overflow and non-zero validity rules.

Until these exist, controller provenance checks operate on hypothetical values rather than a security boundary enforced by the root daemon.

## High-severity amendments

### 5. Persistent cleanup ownership and service state remain unproven

`cleanupScope` is still supplied abstractly, with no concrete service/application owner proving its lifetime. `CleanupDebt.serviceWasActivated` remains unused; retry eligibility depends on mutable `serviceActivated`.

A cleanup supervisor needs an authoritative service-state query or service-owned teardown primitive so controller state drift cannot make debt permanently unreachable.

### 6. Non-throwing conflicts can grow failure history without bound

Repeated merges of the same unresolved service conflict append another `CleanupFailure` each time. Backoff retries can therefore grow debt memory and state payloads indefinitely. Deduplicate conflict identity or retain bounded structured conflict history.

### 7. Source comments still describe obsolete provenance rules

`CleanupDebt.kt` still says the controller sets the firewall epoch and that a lower epoch is dominated regardless of daemon generation. The current model requires an authoritative daemon `sessionId` plus epoch. Update the comments so future implementation does not follow the obsolete R6 rule.

### 8. Normalization diagnostics and downstream address selection remain open

Invalid interfaces and clients are silently dropped. `ManagedDownstream` still retains the first valid IPv4 observation, making the endpoint order-dependent when duplicate observations disagree.

## Verified corrections

- commit `b44573ec` changes only `CleanupDebt.kt` and `ProxyOnlyController.kt` relative to the round-8 review head;
- mismatched start acknowledgements are recorded before validation and force daemon-clean debt;
- no handle-specific IPC is attempted for mismatched provenance;
- `cleanupApplied()` merges global debt before clearing `applied`;
- firewall-handle conflicts become sanitation debt instead of throwing;
- debt-retry sanitation rejects acknowledgements when the observed daemon generation changed during the call;
- fast-path `replace()` requires exact cached session/epoch equality;
- review documents through round 8 remain present.

## Build and test status

At review time:

- GitHub's `Dependency Review` workflow failed in the dependency-review action step;
- the main `Test` workflow was still running and had not yet reached the Rust checks or Gradle build/test step;
- no deterministic tests specifically cover service-handle conflict teardown, primary-sanitation generation races or per-command daemon-session validation.

Required evidence remains:

```bash
./gradlew :mobile:compileDebugKotlin
./gradlew :mobile:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

Also add focused deterministic tests for all R1-R9 failure sequences before approval.

## Required next sequence

1. Replace arbitrary service-handle conflict selection with authoritative service reconciliation/full teardown.
2. Apply generation capture/revalidation to the primary sanitation gate.
3. Validate/bind daemon session provenance on every firewall-handle command.
4. Implement sanitation/start/replace/deny/stop acknowledgements and stale-token rejection in proto/Rust.
5. Wire a concrete cleanup supervisor with authoritative service state.
6. Add deterministic conflict/race/provenance tests and attach successful CI/build evidence.
7. Only then continue with Hev/JNI and production firewall integration.

## Approval boundary

Status after round 9: **changes requested**.

The R8 remediation fixes all four specifically reported controller paths, but the new service-conflict policy can make cleanup permanently unreachable, and daemon-generation/session binding is still incomplete outside debt retry and the fast path.