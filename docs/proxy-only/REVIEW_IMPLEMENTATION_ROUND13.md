# Proxy-only implementation review — round 13

Status: **all Track B follow-up findings resolved and verified**

Verified clean source head: `e2492a5e3838013b089600f7b9631f9f6be01cee`

This round audits the post-Track-B review covering sanitation transport failure,
authoritative daemon generation, deny-first validation, external-clean ordering and
missing daemon/session-store regression coverage.

## Resolution audit

### 1. Sanitation transport failure follows the null/debt contract — resolved

`DaemonProxyFirewallClient.cleanOrDenyBeforeRestart()` clears any previously cached
sanitation token before issuing the RPC. A transport `IOException` now returns `null`
rather than unwinding past the controller's sanitation gate. Cancellation and caller
configuration errors still propagate.

Regression coverage makes the fake transport throw after one successful sanitation and
asserts that:

- the second sanitation returns `null`;
- the previous token cannot be used by `start()`;
- no additional start RPC is issued.

### 2. Daemon generation is no longer discarded — resolved

`SanitationResult` now carries `(sessionId, epoch, daemonGeneration)` from one daemon
acknowledgement. The adapter validates non-zero authoritative identity and requires the
start acknowledgement to match all three sanitation values.

The controller commits sanitation only when the acknowledgement-issued generation equals
the currently observed `DesiredProxyState.daemonGeneration`. The same comparison is
performed during cleanup-debt retry. A mismatch retains `daemonCleanPending`, records a
typed failure and prevents a firewall start.

The concrete service/health composition remains future integration, but it can no longer
silently combine two divergent generation clocks: disagreement fails closed.

### 3. Deny-first start rejects allowed clients — resolved

Daemon validation now rejects every deny-first configuration containing any
`allowed_clients`. The new Rust test proves the request is `INVALID` and causes zero
kernel calls.

### 4. External clean ordering is enforced by the API — resolved

`record_external_clean()` is now asynchronous and accepts the daemon-wide kernel cleanup
operation as a closure. While the proxy state remains exclusively borrowed it executes:

1. routing/kernel cleanup;
2. applied-ledger clear;
3. sanitation epoch advance.

A failed cleanup returns before either ledger or epoch changes. Tests cover both failure
preservation and successful epoch/ledger transition.

### 5. Coverage and persistence hardening — resolved

Added Rust tests for:

- a second start while a runtime is active;
- deny converting an allowed runtime to explicit IPv4/IPv6 deny with no clients;
- stop followed by an unknown-handle rejection;
- successful and failed daemon-wide external cleanup;
- concurrent `FileSessionStore` allocation from eight independent processes;
- stale temporary-counter cleanup under the session-store lock.

The session counter retains `flock`, file `sync_all`, atomic rename and parent-directory
fsync. Temporary files now use a recognizable `counter.tmp-*` name and are removed while
the store lock is held.

## Verification

The restored normal CI workflow passed on the exact clean source head:

- `cargo check --locked --all-targets`;
- `cargo test --locked --lib`;
- `cargo clippy --locked --all-targets -- -D warnings`;
- `cargo audit`;
- `./gradlew assembleDebug check --no-daemon`;
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon`;
- Dependency Review with `fail-on-severity: moderate`.

Temporary patch workflows, scripts and elevated workflow permissions used during the
multi-file remediation were removed before this verification.

## Verdict

Track B's daemon protocol boundary and its reviewed follow-up invariants are complete.
The PR remains draft because Tracks C–E, the actual foreground-service/RPC composition,
Hev/JNI backend integration and physical-device verification are still outstanding.
