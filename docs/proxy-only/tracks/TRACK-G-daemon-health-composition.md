# Track G — acknowledgement-backed daemon health composition

Status: **implemented; verification pending on current PR head**

## Goal

Carry the Track B generation contract into production composition: the desired-state
`daemonHealthy`/`daemonGeneration` values and the firewall RPC client must be driven by the
same daemon acknowledgement identity. A separate connection counter, timestamp or locally
inferred generation must never become the controller clock.

## Delivered implementation

### Authoritative state model

`ProxyDaemonState` represents exactly two valid states:

- unavailable, with no session or generation identity;
- healthy, with a complete non-zero `(sessionId, generation)` acknowledgement identity.

Partial and zero identities are rejected.

### RPC tracking boundary

`TrackingProxyFirewallRpc` decorates the concrete root-daemon transport:

- every typed acknowledgement carrying an identity refreshes `ProxyDaemonStateTracker`;
- stale-session acknowledgements are observed because they identify the newer daemon boot;
- transport `IOException` clears health and generation immediately;
- protocol or kernel errors do not falsely mark the responding daemon process unavailable.

### Owned composition

`ProxyDaemonComposition` owns one private tracker and constructs both:

1. the `DaemonProxyFirewallClient` through the tracking RPC decorator; and
2. the desired-state flow adapter used before `ProxyOnlyController.start()`.

This prevents application wiring from using one generation source for reconciliation and a
different source for firewall acknowledgement validation.

`withAcknowledgedDaemonState()` overwrites any independently supplied daemon health and
generation values. Raw collector values cannot leak through to the controller.

## Regression coverage

`ProxyDaemonStateIntegrationTest` drives the real Kotlin firewall client stack over a fake
transport and verifies:

1. an independently supplied generation is suppressed while no acknowledgement exists;
2. a sanitation acknowledgement publishes its session and generation to desired state;
3. a second acknowledgement from a restarted daemon replaces both values;
4. transport disconnect clears health and generation;
5. partial and zero identities are rejected.

The remaining device-level test must restart the actual root daemon through the production
transport and assert the controller tears down the old runtime before starting under the new
acknowledgement identity.

## Remaining boundary

Track G does not implement the root-process request/reply transport itself, the Android
foreground service, the native backend, or physical-device restart evidence. It closes the
clock-composition seam those components must use.
