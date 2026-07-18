# Track G — acknowledgement-backed daemon health composition

Status: **reviewed, remediated, and source-CI verified; rooted-device restart evidence pending**

Verified executable source head: `a18473547e4818e3d37053b884d44042be3c0ab0`

Documentation and device-runbook commits may follow without changing the application source.

## Goal

Carry the Track B generation contract into production composition: the desired-state
`daemonHealthy`/`daemonGeneration` values and the firewall client must be driven by the same daemon
acknowledgement identity. A connection counter, timestamp, connection attempt, or independently
inferred generation must never become the controller clock.

## Delivered implementation

### Authoritative state model

`ProxyDaemonState` represents exactly two valid states:

- unavailable, with no session or generation identity;
- healthy, with a complete non-zero `(sessionId, generation)` acknowledgement identity.

Partial and zero identities are rejected.

### RPC tracking boundary

`TrackingProxyFirewallRpc` decorates the concrete root-daemon request/reply transport:

- successful and stale-token acknowledgements publish their authoritative identity;
- stale acknowledgements are observed because they identify the current daemon boot;
- `INVALID`, `IO_ERROR`, and unrecognized acknowledgements do not publish a transient usable state;
- transport `IOException` clears health and generation;
- an authoritative acknowledgement must contain a complete non-zero daemon identity; and
- every request is fenced to the transport epoch in which it was issued.

An explicit disconnect advances the transport epoch immediately. A reply that was already in flight
on the old epoch is rejected rather than restoring `healthy=true` after disconnect.

`RootProxyFirewallRpc` also preserves coroutine cancellation. A controller timeout or parent
cancellation is rethrown as `CancellationException`; it is never rewritten as an `IOException` or
misclassified as daemon transport loss.

### Deny-first bootstrap

The controller refuses sanitation until a daemon generation is observable, so an
acknowledgement-only tracker needs an explicit bootstrap before the controller worker can admit a
runtime. `ProxyDaemonComposition.bootstrap()` uses the existing deny-first sanitation command:

1. the daemon installs containment;
2. a successful acknowledgement supplies the boot session and generation;
3. the desired-state adapter publishes that identity;
4. the controller performs its own reviewed sanitation gate; and
5. only then may backend probes and exact allow installation proceed.

The duplicate pre-runtime sanitation is idempotent and never reuses or manufactures a token. A
failed or malformed bootstrap remains unavailable.

### Owned and serialized composition

`ProxyDaemonComposition` owns one private tracker, one `DaemonProxyFirewallClient`, and one operation
mutex. It exposes:

1. a `ProxyFirewallClient` wrapper used by `ProxyOnlyController`;
2. `bootstrap()` for initial connection and root-daemon reconnect;
3. `transportDisconnected()` for immediate health invalidation; and
4. `desiredStates()` for acknowledgement-backed desired-state composition.

Bootstrap and every controller firewall operation share the same mutex. Concurrent reconnect
bootstrap cannot overtake controller sanitation or overwrite the client's latest sanitation token
out of acknowledgement order. The independent transport-epoch fence covers the separate race in
which a disconnect signal arrives while one serialized RPC is still suspended.

`withAcknowledgedDaemonState()` overwrites any independently supplied daemon health and generation
values. Raw collector values cannot leak through to the controller.

## Review findings and remediation

### Finding G-1 — late acknowledgement after explicit disconnect

Previous behavior:

1. an RPC was issued and suspended in the transport;
2. `transportDisconnected()` published `Unavailable`;
3. the old RPC returned an acknowledgement afterward; and
4. `TrackingProxyFirewallRpc` could publish the old identity and make the daemon appear healthy
   again.

This was fail-open at the composition boundary because a disconnected transport could regain health
without a request on the new transport.

Remediation:

- `ProxyDaemonStateTracker.beginRequest()` captures a transport-epoch fence;
- explicit disconnect advances the epoch and clears state;
- acknowledgement publication succeeds only when its fence still matches; and
- a late acknowledgement becomes an `IOException`, so the calling firewall operation fails closed.

### Finding G-2 — malformed authoritative acknowledgement retained older health

Previous behavior:

- `OK`, `STALE_SESSION`, or `STALE_EPOCH` without identity did not publish anything;
- a zero identity threw while constructing state; and
- in either case an older healthy identity could remain observable.

Remediation:

- authoritative statuses now require identity presence, non-zero session ID, and non-zero daemon
  generation;
- a malformed authoritative acknowledgement is a protocol `IOException`; and
- the matching transport epoch is invalidated before the failure reaches the caller.

### Finding G-3 — cancellation was rewritten as transport loss

Previous behavior:

- `RootProxyFirewallRpc` caught generic `Exception`;
- Kotlin `CancellationException` was wrapped in `IOException`; and
- controller cancellation could therefore clear daemon health and enter transport-failure recovery
  rather than preserving structured cancellation.

Remediation:

- `CancellationException` is caught first and rethrown unchanged;
- actual `IOException` retains its identity; and
- only non-I/O, non-cancellation transport failures are wrapped as `IOException`.

No additional Track G code defect was found in serialized bootstrap/controller operation ordering,
stale-token identity handling, desired-state replacement, or root-daemon epoch mutation ordering.

## Regression coverage

`ProxyDaemonStateIntegrationTest` and `RootProxyFirewallRpcTest` drive the real Kotlin composition
stack over fake transports and verify:

1. an independently supplied generation is suppressed before bootstrap;
2. deny-first bootstrap publishes its acknowledged session and generation;
3. controller sanitation and reconnect bootstrap are serialized;
4. a restarted daemon acknowledgement replaces both session and generation;
5. failed sanitation never publishes healthy state;
6. transport failure clears health and generation;
7. explicit disconnect clears state without another RPC;
8. an acknowledgement released after explicit disconnect cannot restore health;
9. missing and zero authoritative identities invalidate previously healthy state;
10. partial or zero `ProxyDaemonState` identities are rejected; and
11. coroutine cancellation propagates without transport wrapping.

## MVP composition boundary

Daemon-wide routing Clean removes proxy chains and advances the authoritative proxy epoch under the
root daemon's exclusive proxy-state lock. Mixed VPN-routing plus Proxy-only operation is not an MVP
mode. If a future mixed mode permits routing Clean while a proxy listener remains active, it must add
a proactive app-side invalidation/closure handshake or preserve deny containment atomically; relying
only on the next stale-token acknowledgement would be insufficient.

The first rooted-device run therefore records daemon process/session state, listener reachability,
firewall snapshots, and recovery ordering. See [`../DEVICE_VALIDATION.md`](../DEVICE_VALIDATION.md),
especially DV-09.

## Verification

The normal `Test` workflow passed on executable source head
`a18473547e4818e3d37053b884d44042be3c0ab0`:

- `cargo check --locked --all-targets`;
- `cargo test --locked --lib`;
- `cargo clippy --locked --all-targets -- -D warnings`;
- `cargo audit`;
- `./gradlew assembleDebug check --no-daemon`, including the new Track G regressions;
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon`; and
- APK/report artifact upload.

Dependency Review is unchanged by these source edits and is rerun on the final documentation head
with `fail-on-severity: moderate`.

## Remaining evidence boundary

Track G's code-level acknowledgement, protocol, and cancellation races are covered by deterministic
JVM tests. Release still requires a real rooted-phone test that kills the active `vpnhotspotd`, proves
the old listener/runtime becomes unreachable, observes a new daemon identity and deny-first
sanitation, and shows that TCP/UDP resume only after the new acknowledgement-backed runtime reaches
`Running`.
