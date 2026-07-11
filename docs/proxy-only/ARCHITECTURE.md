# Proxy-only architecture

## 1. Existing components and the upstream distinction

The implementation extends the current architecture instead of introducing a second full routing stack.

### `Upstreams.vpn` versus `Upstreams.primary`

`Upstreams.kt` exposes a flow that specifically requests `NetworkCapabilities.TRANSPORT_VPN`. It also exposes `Upstreams.primary`, which defaults to that VPN flow but can be replaced by the user's `service.upstream` interface-regex preference.

That distinction is security-critical:

- existing routing mode may intentionally route through a user-selected physical interface;
- proxy-only promises that the SOCKS egress is a VPN path;
- therefore proxy-only must not trust `Upstreams.primary` without validation.

The controller shall use one of these equivalent approaches:

1. consume `Upstreams.vpn` directly; or
2. consume a selected upstream and reject it unless fresh `ConnectivityManager.getNetworkCapabilities(network)` includes `TRANSPORT_VPN`.

The validation is repeated immediately before native startup and before publishing `Running`. A missing capability, missing network or permission error is fail-closed.

The existing `Routing.kt` path already collects `Upstreams.primary` and places its `networkHandle` into `SessionConfig`. That remains valid for routing mode because the selected upstream is user policy, not a VPN-security assertion.

### System tethering lifecycle

`TetheringService` observes Android tethered interfaces and currently creates `RoutingManager` instances for downstreams managed by VPN forwarding.

Proxy-only introduces a separate downstream concern:

```text
The interface remains under ordinary Android tethering,
but it is eligible to reach the app-process SOCKS listener.
```

Android keeps responsibility for DHCP, system NAT and the direct path. VPN Hotspot observes interface/client lifecycle only to reconcile listener exposure and ACLs.

### Root daemon conventions

The current daemon owns firewall/routing reconciliation, neighbour identity, traffic counters and deterministic cleanup. Proxy firewall code must reuse the existing mutation model:

- `routing/iptables.rs::IptablesRule` for rule identity and idempotent `-I`/`-D` operations;
- `delete_repeated()` for duplicate-safe cleanup;
- `routing/firewall_cleanup.rs::clean()` for deterministic removal of app-owned jumps/chains;
- existing `IptablesTarget::Ipv4`/`Ipv6` selection.

`SessionConfig` remains unchanged. The proxy firewall has an independent long-lived command/runtime because it has different ownership and failure semantics. This also avoids adding more responsibility to one of the daemon's most connected abstractions.

## 2. Component model

```text
┌──────────────────────── Android app process ──────────────────────────┐
│                                                                       │
│  Tethering state / UI                                                 │
│          │                                                            │
│          ▼                                                            │
│  ProxyOnlyController                                                  │
│    ├── observes settings                                              │
│    ├── observes VPN-only candidate                                    │
│    ├── validates TRANSPORT_VPN and app-UID usability                  │
│    ├── observes tethered interfaces + neighbour identities            │
│    ├── serializes desired states through one worker                   │
│    └── treats firewall-channel loss as immediate stop                 │
│          │                                                            │
│          ├───────────────┐                                            │
│          ▼               ▼                                            │
│  ProxyService       ProxyFirewallClient                               │
│    ├── FGS lifecycle      │ long-lived daemon command                 │
│    ├── ProxyBackend       │ deny/allow/replace/stop                   │
│    ├── native listener    │                                           │
│    └── status/stats       │                                           │
│          │               │                                            │
│          ▼               │                                            │
│  HevProxyBackend         │                                            │
│    ├── CONNECT           │                                            │
│    ├── UDP ASSOCIATE     │                                            │
│    ├── auth              │                                            │
│    ├── VPN-aware DNS     │                                            │
│    └── per-FD hook       │                                            │
└──────────┬───────────────┼────────────────────────────────────────────┘
           │               │
           │ listener      ▼
           │       ┌──────────────── root daemon ──────────────────────┐
           │       │ ProxyFirewallRuntime                             │
           │       │  ├── IptablesRule ledger                        │
           │       │  ├── IPv4 iface+IP+MAC allow rules              │
           │       │  ├── IPv4 default reject                        │
           │       │  ├── IPv6 deny for listener/range               │
           │       │  ├── counters                                   │
           │       │  └── firewall_cleanup integration               │
           │       └──────────────────────────────────────────────────┘
           ▼
 validated Android VPN Network
```

## 3. Product mode and internal downstream model

The MVP UI has a global mode:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

Existing users migrate to `VPN_ROUTING`.

Internally, each observed interface is represented independently:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

This keeps the data model extensible without exposing mixed per-interface controls in the MVP. `VPN_ROUTING_AND_PROXY` is deferred until interaction tests prove chain ordering and cleanup on the same downstream.

## 4. State machine

```text
Disabled
  └─ enable ─> WaitingForTethering

WaitingForTethering
  ├─ tethering available ─> WaitingForVpn
  └─ disable ─> Disabled

WaitingForVpn
  ├─ validated VPN available ─> Starting
  ├─ non-VPN selected ─> FailClosed(NonVpnUpstream)
  ├─ VPN inaccessible to app UID ─> FailClosed(VpnPermissionDenied)
  └─ disable ─> Disabled

Starting
  ├─ deny rules + backend + probe + allows committed ─> Running
  ├─ desired state changes ─> finish rollback, then reconcile newest snapshot
  ├─ daemon channel lost ─> FailClosed(DaemonUnavailable)
  └─ error ─> Error

Running
  ├─ VPN Network identity changes ─> Stopping -> Starting
  ├─ VPN lost/non-VPN ─> FailClosed
  ├─ daemon channel lost ─> close listener -> FailClosed
  ├─ downstream/client set changes ─> replace firewall/ACL only
  ├─ downstream set becomes empty ─> Stopping -> WaitingForTethering
  └─ disable ─> Stopping -> Disabled
```

The MVP restarts the backend on every VPN generation change. A live `replaceNetwork()` path is deferred behind the backend abstraction.

## 5. Serialized reconciliation

Flow collection only publishes immutable desired snapshots into a `Channel.CONFLATED`. One dedicated worker performs complete apply/rollback transactions. New snapshots do not cancel an in-flight transaction.

Commit and rollback sections run in `NonCancellable` context. The worker records a partially applied resource immediately after creation so cleanup can always find it.

The runtime key is exactly:

```text
TCP listener port
+ UDP relay range
+ credentials version
+ validated VPN Network handle
+ sorted downstream interface/address set
+ backend feature flags
```

Client order, blocked-client changes and irrelevant `LinkProperties` churn are not part of the runtime key. They trigger replacement of firewall/native ACL state, not listener restart.

VPN generation is derived in the serialized worker by comparing `Network` identity/handle with the last committed snapshot. No mutation occurs inside a `combine` transform.

## 6. VPN network selection and binding contract

A candidate is acceptable only when all checks pass:

1. `Network` is still present;
2. capabilities include `TRANSPORT_VPN`;
3. a probe socket can be bound by the VPN Hotspot app UID;
4. the probe cannot fall back to the process default route;
5. VPN-specific DNS succeeds or returns a controlled failure.

Per-app VPN policy can cause `android_setsocknetwork()` to return `EPERM` when VPN Hotspot is excluded. This is a user-visible configuration error, not a reason to use a physical fallback.

Core invariant:

> Every Internet-facing socket is bound to the validated VPN `Network` after `socket()` and before `connect()`, `sendto()` or any other packet-producing operation.

TCP:

```text
socket()
  -> prepare hook / android_setsocknetwork()
  -> connect()
  -> relay
```

UDP:

```text
socket()
  -> prepare hook / android_setsocknetwork()
  -> connect()/sendto()
  -> relay
```

All retry, happy-eyeballs, IPv4/IPv6 fallback and DNS-created socket paths must pass the same hook. In fail-closed mode a hook error closes the FD.

## 7. DNS architecture

Domain-form SOCKS requests must resolve through the validated VPN network.

Potential primitives include network-aware native resolver APIs or a Java/Kotlin `Network.getAllByName()` bridge. Both common synchronous approaches can block. Hev worker threads must not run an unbounded blocking resolver call.

Phase 0 must measure behaviour with a blackholed resolver and choose one of:

- a bounded dedicated resolver thread pool with strict timeout/cancellation; or
- an asynchronous Android resolver path such as `android_res_nsend` where supported by the chosen native boundary.

The process-default resolver is forbidden in fail-closed mode.

## 8. VPN generation changes

A `Network` handle is not permanent. On identity/handle change:

1. replace firewall allows with deny state;
2. stop accepting clients;
3. close all TCP sessions and UDP associations;
4. stop the backend instance;
5. validate the new VPN and app-UID binding;
6. start a new backend instance;
7. run TCP/UDP/DNS probes;
8. commit current client allow rules;
9. publish `Running`.

No session survives across generations in the MVP.

## 9. Listener, UDP and address-family policy

### TCP

The SOCKS control listener uses the configured TCP port, initially `10808` unless changed by the user.

### UDP

SOCKS5 `UDP ASSOCIATE` may return an ephemeral relay port. The Hev pin must be configured to allocate only inside an explicit range. Phase 0 determines the exact supported configuration and reply behaviour.

The app and daemon exchange:

```text
udp_port_range_start
udp_port_range_end
```

A single `udp_port` field is not sufficient unless the pinned Hev implementation proves that all associations share one fixed port.

### IPv6 MVP policy

The first release is IPv4-only:

- bind native listener/relay sockets as IPv4-only;
- do not publish IPv6 endpoints;
- install `ip6tables` reject rules for the TCP port and full UDP relay range on all interfaces;
- verify that no dual-stack wildcard socket accepts IPv4-mapped or native IPv6 traffic unexpectedly.

Full IPv6 relay is a later feature requiring mirrored ACLs, DNS, counters and tests.

## 10. Firewall and ACL model

The proxy must never become an open proxy on upstream Wi-Fi, cellular, VPN, loopback or unrelated local interfaces.

IPv4 desired policy:

```text
INPUT to TCP port or UDP relay range:
  accept ESTABLISHED/RELATED where protocol semantics require it
  for each allowed client:
    match downstream input interface
    match source IPv4
    match source MAC
    count under MAC-facing identity
    accept
  reject
```

The MAC match is mandatory wherever the downstream exposes L2 identity. IP-only matching permits DHCP/static-IP identity reuse and is not an MVP fallback. An interface without reliable MAC identity is unsupported until a safe policy is designed.

IPv6 desired policy:

```text
INPUT to TCP port or UDP relay range:
  reject
```

The proxy runtime reuses `IptablesRule` and maintains an applied ledger. Proxy jump rules and chains are added to `firewall_cleanup::clean()` so Clean removes them even after process/daemon failure.

## 11. Daemon liveness contract

iptables state survives daemon death. Existing allow rules may remain after `vpnhotspotd` exits.

Therefore:

- the app owns a long-lived firewall call/channel;
- channel completion or daemon disconnect immediately triggers native listener shutdown;
- active sessions are closed before any restart attempt;
- after daemon recovery, deterministic Clean or explicit deny reconciliation runs first;
- the listener is started only after the new firewall runtime is live.

A dead daemon plus a closed listener is safe even if stale allow rules remain temporarily. A dead daemon plus a live listener is forbidden.

## 12. Accounting

Two layers are retained:

1. kernel counters on listener ingress, keyed publicly by MAC/downstream;
2. backend protocol counters for TCP/UDP payload and active sessions.

Firewall counters include SOCKS framing and are not labelled as application payload.

## 13. Service ownership and foreground-service gate

- `TetheringService` publishes active system tethering interfaces;
- `ProxyOnlyController` owns validation and serialized desired-state reconciliation;
- `ProxyService` owns foreground/native lifecycle;
- `ProxyBackend` hides Hev-specific implementation details;
- `vpnhotspotd` owns firewall, ACL and kernel counters.

The exact foreground-service type and store-policy eligibility are a Phase 2 release gate, not a late documentation question. The implementation must verify the repository's current target SDK and distribution requirements before merging the service declaration.

## 14. Transaction ordering

Startup:

1. obtain current tethered interfaces and MAC/IP identities;
2. obtain and validate a VPN-only `Network`;
3. verify app-UID binding policy with a probe;
4. create a root firewall runtime in deny state for TCP and UDP range, IPv4 and IPv6;
5. record the firewall runtime as partially applied;
6. start the IPv4-only backend;
7. run bound TCP, UDP and DNS probes;
8. replace deny state with iface+IP+MAC allow rules;
9. publish `Running`.

Shutdown or any critical dependency loss:

1. request deny state when the daemon is alive;
2. stop accepting clients immediately;
3. close native sessions and listener;
4. stop the firewall runtime when possible;
5. run deterministic cleanup on later daemon recovery if needed;
6. publish the final fail-closed/waiting/disabled state.

This ordering prevents both an open-listener window and silent physical-network fallback.