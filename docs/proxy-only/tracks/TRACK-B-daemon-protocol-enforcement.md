# Track B — daemon protocol enforcement (proto + Rust)

**Status:** complete and verified. See the
[implementation result](TRACK-B-IMPLEMENTATION-RESULT.md) and
[round-13 resolution audit](../REVIEW_IMPLEMENTATION_ROUND13.md).

**Verified clean source head:** `e2492a5e3838013b089600f7b9631f9f6be01cee`

**Goal:** make session/epoch/generation an **enforced boundary in the root daemon**,
not a Kotlin-only contract. The completed implementation extends the daemon protocol,
enforces authoritative tokens under one serialized state lock, installs explicit
fail-closed firewall rules, persists unique session identity, maps acknowledgements in
Kotlin and rejects generation disagreement before runtime start.

## Delivered files

```text
mobile/src/main/proto/proxy_firewall.proto
mobile/src/main/proto/daemon.proto
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/mod.rs
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/session.rs
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/ledger.rs
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall_kernel.rs
mobile/src/main/rust/vpnhotspotd/src/control/proxy_firewall.rs
mobile/src/main/java/be/mygod/vpnhotspot/proxy/DaemonProxyFirewallClient.kt
```

## Implemented invariants

### Protocol identity and acknowledgements

- The daemon issues a crash-persistent, non-zero `session_id`, a sanitation `epoch` and
  a transport/boot `generation`.
- Start, replace, deny and stop carry the expected `(session_id, epoch)`.
- Every acknowledgement returns the daemon's authoritative identity.
- Kotlin constructs sanitation and runtime handles only from acknowledgements.

### Atomic validation and mutation

The daemon's proxy-firewall state is held behind one async mutex. The guard remains held
across the complete command future, so token validation, ledger validation and kernel
mutation are one serialized check-and-use transaction. Stale-token tests assert zero
kernel calls.

### Sanitation and generation

Sanitation executes deny containment before clearing the runtime ledger or advancing the
epoch. Failed containment does not advance identity.

`SanitationResult` carries the acknowledgement-issued daemon generation. Controller
primary reconciliation and cleanup-debt retry require it to equal the currently observed
daemon generation. A mismatch retains sanitation debt and prevents start, ensuring a
future concrete health/RPC composition cannot silently combine divergent clocks.

Transport `IOException` during sanitation returns `null`, clears any cached token and
follows the controller's `daemonCleanPending` fail-closed retry path. Cancellation and
caller configuration errors still propagate.

### Runtime and firewall validation

- Only one proxy-firewall runtime may be active.
- Runtime handles are non-zero and monotonically allocated.
- Replacement generations must strictly increase.
- Deny-first configuration requires both IPv4 and IPv6 deny flags and no allowed clients.
- IPv6 allow mode is rejected.
- IPv4 allow rules match downstream interface + client IPv4 + MAC and terminate in reject.
- IPv6 listener and UDP relay ports always terminate in reject.

### External cleanup and persistence

Daemon-wide routing cleanup is passed into `record_external_clean()` while the proxy state
is exclusively borrowed. Ledger clear and epoch advance happen only after kernel cleanup
succeeds. Tests cover both successful and failed ordering.

The session store uses `flock`, read/checked-increment, file fsync, atomic rename and parent
directory fsync. Coverage includes serial allocation, concurrent threads, eight independent
processes and stale temporary-file cleanup under the lock.

## Verification

The restored normal workflow passed on the verified clean source head:

- `cargo check --locked --all-targets`;
- `cargo test --locked --lib`;
- `cargo clippy --locked --all-targets -- -D warnings`;
- `cargo audit`;
- `./gradlew assembleDebug check --no-daemon`;
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon`;
- Dependency Review with `fail-on-severity: moderate`.

## Remaining integration boundary

Track B does not implement the user-facing foreground service or Hev/JNI backend. Track C
must provide the real service-owned cleanup supervisor and concrete RPC/health composition;
that composition is required to use the acknowledgement identity as its authoritative
daemon-generation source. Tracks D and E remain open as listed in the status tracker.
