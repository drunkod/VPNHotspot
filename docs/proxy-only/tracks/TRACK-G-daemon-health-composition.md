# Track G — acknowledgement-backed daemon health composition

Status: **complete and verified**

Verified clean source head: `81d902c60ec05b068c86cb4a51e8f341ed01007a`

## Goal

Carry the Track B generation contract into production composition: the desired-state
`daemonHealthy`/`daemonGeneration` values and the firewall client must be driven by the same
daemon acknowledgement identity. A connection counter, timestamp or independently inferred
generation must never become the controller clock.

## Delivered implementation

### Authoritative state model

`ProxyDaemonState` represents exactly two valid states:

- unavailable, with no session or generation identity;
- healthy, with a complete non-zero `(sessionId, generation)` acknowledgement identity.

Partial and zero identities are rejected.

### RPC tracking boundary

`TrackingProxyFirewallRpc` decorates the future concrete root-daemon transport:

- successful and stale-token acknowledgements publish their authoritative identity;
- stale acknowledgements are observed because they identify the newer daemon boot;
- `INVALID`, `IO_ERROR` and unrecognized acknowledgements do not publish a transient usable
  state;
- transport `IOException` clears health and generation immediately.

### Deny-first bootstrap

The controller intentionally refuses sanitation until a daemon generation is observable, so
an acknowledgement-only tracker needs an explicit bootstrap before the controller worker starts.
`ProxyDaemonComposition.bootstrap()` uses the existing deny-first sanitation command:

1. the daemon installs containment;
2. a successful acknowledgement supplies the boot session and generation;
3. the desired-state adapter publishes that identity;
4. the controller then performs its own reviewed sanitation gate.

The duplicate pre-runtime sanitation is idempotent and never reuses or manufactures a token.
A failed bootstrap remains unavailable.

### Owned and serialized composition

`ProxyDaemonComposition` owns one private tracker, one `DaemonProxyFirewallClient`, and one
operation mutex. It exposes:

1. a `ProxyFirewallClient` wrapper used by `ProxyOnlyController`;
2. `bootstrap()` for initial connection and root-daemon reconnect;
3. `transportDisconnected()` for immediate health invalidation; and
4. `desiredStates()` for acknowledgement-backed desired-state composition.

Bootstrap and every controller firewall operation share the same mutex. Concurrent reconnect
bootstrap cannot overtake controller sanitation or overwrite the client's latest token out of
acknowledgement order.

`withAcknowledgedDaemonState()` overwrites any independently supplied daemon health and
generation values. Raw collector values cannot leak through to the controller.

## Regression coverage

`ProxyDaemonStateIntegrationTest` drives the real Kotlin firewall client stack over a fake
transport and verifies:

1. an independently supplied generation is suppressed before bootstrap;
2. deny-first bootstrap publishes its acknowledged session and generation;
3. controller sanitation and reconnect bootstrap are serialized;
4. a restarted daemon acknowledgement replaces both session and generation;
5. failed sanitation never publishes healthy state;
6. transport disconnect clears health and generation, with or without another RPC; and
7. partial and zero identities are rejected.

## Verification

The normal least-privilege workflows passed on the exact source head:

- `cargo check --locked --all-targets`;
- `cargo test --locked --lib`;
- `cargo clippy --locked --all-targets -- -D warnings`;
- `cargo audit`;
- `./gradlew assembleDebug check --no-daemon`;
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon`;
- Dependency Review with `fail-on-severity: moderate`.

## Remaining boundary

Track G does not implement the root-process request/reply transport itself, the Android
foreground service, the native backend, or physical-device restart evidence. The remaining
device-level test must restart the actual daemon through that transport and prove the controller
tears down the old runtime before starting under the new acknowledgement identity.
