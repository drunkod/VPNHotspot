# Track B — daemon protocol enforcement (proto + Rust)

**Goal:** make session/epoch/generation an **enforced boundary in the root daemon**,
not a Kotlin-only contract. Today `SanitationResult(sessionId, epoch)` and
`ProxyFirewallHandle(sessionId, epoch, id)` exist only in Kotlin; the standalone
`proxy_firewall.proto` carries messages but **no acknowledgements, no session identity,
no stale-token rejection**, and there is **no Rust implementation at all** (only
`nat66/tproxy.rs` exists under `rust/vpnhotspotd/src/`).

This is the largest remaining Phase 0 item and the security boundary every other track
assumes. The controller can reduce exposure with generation checks (done in R10–R12),
but "authoritative stale-token rejection in the root daemon remains mandatory to close
the check-to-use race" (R10 blocker #3).

**Blocker refs:** R10 blocker #4, R11 §Remaining structural blockers #1, R12 remaining
blocker #1.

**Files touched:**

```text
mobile/src/main/proto/proxy_firewall.proto                     (extend)
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/mod.rs     (new)
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/session.rs (new)
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/ledger.rs  (new)
mobile/src/main/rust/vpnhotspotd/src/lib.rs                    (wire module + control dispatch)
mobile/src/main/rust/vpnhotspotd/src/firewall.rs               (integrate proxy chains into clean())
```

---

## Step B1 — extend the proto with commands, session identity and acks

The current proto has only `ProxyFirewallConfig`. Add an explicit command envelope so
every command carries the `(session_id, epoch)` it targets, and every response echoes
the daemon's authoritative identity.

```proto
// Appended to proxy_firewall.proto

// ---------------------------------------------------------------------------
// Daemon session identity — issued at boot, changes every process restart,
// never repeats within a device lifetime (monotonic, crash-persistent).
// ---------------------------------------------------------------------------
message DaemonIdentity {
  // Monotonic, crash-persistent boot session counter. Kotlin sanitizedSessionId
  // must equal this to consider any handle current.
  uint64 session_id = 1;
  // Current sanitation epoch within the session (reset by Sanitize).
  uint64 epoch = 2;
  // Monotonic generation counter (transport-level; maps to daemonGeneration).
  uint64 generation = 3;
}

// Every mutating command targets a specific (session_id, epoch). The daemon
// REJECTS any command whose session_id/epoch does not match its current identity.
message ProxyFirewallCommand {
  oneof kind {
    SanitizeRequest sanitize = 1;      // clean + deny-all + reset ledger, bump epoch
    StartRequest    start    = 2;
    ReplaceRequest  replace  = 3;
    DenyRequest     deny     = 4;
    StopRequest     stop     = 5;
  }
}

message SanitizeRequest {
  // Reason for audit; sanitation is unconditional (always valid).
  string reason = 1;
}

message StartRequest {
  ProxyFirewallConfig config = 1;
  // Command is only accepted if these match the daemon's current identity.
  uint64 expected_session_id = 2;
  uint64 expected_epoch = 3;
}
message ReplaceRequest {
  uint64 handle_id = 1;
  ProxyFirewallConfig config = 2;
  uint64 expected_session_id = 3;
  uint64 expected_epoch = 4;
}
message DenyRequest {
  uint64 handle_id = 1;
  uint64 expected_session_id = 2;
  uint64 expected_epoch = 3;
}
message StopRequest {
  uint64 handle_id = 1;
  uint64 expected_session_id = 2;
  uint64 expected_epoch = 3;
}

// Uniform acknowledgement. success=false + STALE means the caller's token was
// rejected; the controller must re-sanitize (never retry the same handle).
message ProxyFirewallAck {
  enum Status {
    OK = 0;
    STALE_SESSION = 1;   // expected_session_id != current
    STALE_EPOCH = 2;     // session ok, epoch behind current
    INVALID = 3;         // malformed / out-of-range
    IO_ERROR = 4;        // iptables/kernel failure — cleanup debt, retry allowed
  }
  Status status = 1;
  DaemonIdentity identity = 2;   // ALWAYS the daemon's authoritative current identity
  uint64 handle_id = 3;          // for start: the newly issued runtime id
  string detail = 4;
}
```

Key rule: **the daemon's `ProxyFirewallAck.identity` is authoritative.** Kotlin's
`SanitationResult`/`ProxyFirewallHandle` are populated only from acks — never
manufactured locally (already enforced in `ProxyModels.kt` kdoc).

---

## Step B2 — Rust session state

```rust
// proxy_firewall/session.rs
use std::sync::atomic::{AtomicU64, Ordering};

/// Authoritative daemon identity. session_id is loaded from crash-persistent
/// storage at boot and incremented once, so it never repeats across restarts.
pub struct ProxySession {
    session_id: u64,
    epoch: AtomicU64,
    generation: AtomicU64,
}

impl ProxySession {
    /// Load-and-bump the persisted session counter exactly once per boot.
    pub fn boot(store: &dyn SessionStore) -> std::io::Result<Self> {
        let session_id = store.next_session_id()?; // fsync'd monotonic counter
        Ok(Self {
            session_id,
            epoch: AtomicU64::new(0),
            generation: AtomicU64::new(0),
        })
    }

    pub fn identity(&self) -> Identity {
        Identity {
            session_id: self.session_id,
            epoch: self.epoch.load(Ordering::SeqCst),
            generation: self.generation.load(Ordering::SeqCst),
        }
    }

    /// Sanitize is the only operation that advances the epoch.
    pub fn bump_epoch(&self) -> u64 {
        self.epoch.fetch_add(1, Ordering::SeqCst) + 1
    }

    /// Reject stale tokens BEFORE any kernel mutation (check-and-use under one lock).
    pub fn validate(&self, expected_session: u64, expected_epoch: u64) -> AckStatus {
        if expected_session != self.session_id { return AckStatus::StaleSession; }
        let cur = self.epoch.load(Ordering::SeqCst);
        if expected_epoch != cur { return AckStatus::StaleEpoch; }
        AckStatus::Ok
    }
}
```

`SessionStore::next_session_id()` must be crash-persistent (write + fsync a counter
file, or reuse the daemon's existing persistent-state mechanism). A reused session_id
after a crash would let a pre-crash handle match a new session — the exact hole the
Kotlin side cannot close.

---

## Step B3 — command dispatch with atomic check-and-mutate

The whole point is that validation and mutation happen under **one lock**, so a
generation change cannot slip between check and use.

```rust
// proxy_firewall/mod.rs
pub struct ProxyFirewall {
    session: ProxySession,
    ledger: Mutex<AppliedLedger>,   // reuses IptablesRule ledger machinery
}

impl ProxyFirewall {
    pub fn handle(&self, cmd: ProxyFirewallCommand) -> ProxyFirewallAck {
        let _guard = self.ledger.lock().unwrap(); // single critical section
        match cmd.kind {
            Kind::Sanitize(_) => self.do_sanitize(),           // always valid
            Kind::Start(r)    => self.guarded(r.expected_session_id, r.expected_epoch,
                                              || self.do_start(&r.config)),
            Kind::Replace(r)  => self.guarded(r.expected_session_id, r.expected_epoch,
                                              || self.do_replace(r.handle_id, &r.config)),
            Kind::Deny(r)     => self.guarded(r.expected_session_id, r.expected_epoch,
                                              || self.do_deny(r.handle_id)),
            Kind::Stop(r)     => self.guarded(r.expected_session_id, r.expected_epoch,
                                              || self.do_stop(r.handle_id)),
        }
    }

    fn guarded(&self, sess: u64, epoch: u64,
               mutate: impl FnOnce() -> Result<u64, IoError>) -> ProxyFirewallAck {
        match self.session.validate(sess, epoch) {
            AckStatus::Ok => match mutate() {
                Ok(handle_id) => self.ack(AckStatus::Ok, handle_id, ""),
                Err(e) => self.ack(AckStatus::IoError, 0, &e.to_string()),
            },
            // STALE: mutate() is NEVER called → no kernel change on a stale token.
            stale => self.ack(stale, 0, "rejected: stale session/epoch"),
        }
    }

    fn do_sanitize(&self) -> ProxyFirewallAck {
        // 1. install deny_all_ipv4 + deny_all_ipv6 atomically
        // 2. delete_repeated() all prior proxy chains via the applied ledger
        // 3. bump epoch AFTER kernel state is deny-all
        firewall_cleanup_proxy(&self.ledger);
        let epoch = self.session.bump_epoch();
        self.ack(AckStatus::Ok, /*handle*/0, &format!("sanitized epoch={epoch}"))
    }
}
```

Deny-all is installed **before** the epoch bump so there is never a window where the
new epoch is live but rules are permissive.

---

## Step B4 — integrate into `firewall_cleanup::clean()`

Per IMPLEMENTATION_PLAN Phase 6 rules: reuse `IptablesRule`/`IptablesChain`, use the
applied ledger and `delete_repeated()`, and make proxy chains part of the daemon's
existing cleanup path so a daemon-level clean also removes proxy rules.

```rust
// firewall.rs — extend the existing clean()
pub fn clean(ctx: &FirewallContext) {
    // ... existing routing/nat cleanup ...
    proxy_firewall::firewall_cleanup_proxy(&ctx.proxy_ledger); // NEW
}
```

IPv4 allow rule shape (interface + IP + MAC), IPv4 chain ends in REJECT, IPv6 rejects
the whole listener/UDP range, **no broad VPN-interface allow** — exactly the proto
comment rules. `udp_return_policy` stays absent until Phase 0.5 evidence exists.

---

## Step B5 — Kotlin client wiring (thin)

`ProxyFirewallClient` in `ProxyServiceClient.kt` sends `expected_session_id` /
`expected_epoch` on every mutating call and maps ack status:

```kotlin
override suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport {
    val ack = channel.send(deny(handle.id, handle.sessionId, handle.epoch))
    return when (ack.status) {
        OK -> CleanupReport.empty()
        STALE_SESSION, STALE_EPOCH ->
            CleanupReport.failure("deny_stale", StaleTokenException(ack.identity))
        INVALID -> CleanupReport.failure("deny_invalid", IllegalStateException(ack.detail))
        IO_ERROR -> CleanupReport.failure("deny_io", IOException(ack.detail))
    }
}
```

A `STALE_*` ack must **not** clear firewall debt — it proves the handle is dead and
re-sanitation is required. Only `OK` resolves.

---

## Step B6 — Rust tests (daemon-side, authoritative)

```rust
#[test]
fn stale_session_command_makes_no_kernel_change() {
    let fw = ProxyFirewall::boot_for_test(session_id = 2);
    let fake_iptables = fw.spy_iptables();
    let ack = fw.handle(start_req(config, expected_session = 1 /*old*/, expected_epoch = 0));
    assert_eq!(ack.status, StaleSession);
    assert!(fake_iptables.mutations().is_empty(), "no rules touched on stale token");
}

#[test]
fn epoch_behind_current_is_rejected() { /* expected_epoch = 0, current = 1 → StaleEpoch */ }

#[test]
fn sanitize_installs_deny_before_bumping_epoch() {
    // assert deny_all present in ledger BEFORE epoch increment is observable
}

#[test]
fn session_id_never_repeats_across_boot() {
    let s1 = ProxySession::boot(&store).unwrap().identity().session_id;
    let s2 = ProxySession::boot(&store).unwrap().identity().session_id;
    assert!(s2 > s1);
}
```

## Acceptance criteria

- Every mutating command carries and is validated against `(session_id, epoch)`.
- A stale command produces zero kernel mutations (spy assertion) and a `STALE_*` ack.
- `session_id` is crash-persistent and strictly increasing across reboots.
- Proxy chains are removed by the daemon's own `clean()`.
- Kotlin maps `STALE_*` to a non-resolving failure that forces re-sanitation.
- Rust daemon tests + `./gradlew check` green.

## Dependencies / notes

- Coordinate with **Track A**: once B5 lands, the fake firewall client in Track A
  should also model `STALE_*` acks so the controller's re-sanitation path is tested
  end-to-end.
- This track unblocks the real `ProxyService` + backend work (Phases 1/4), which are
  out of scope here but blocked until the boundary is enforced.
