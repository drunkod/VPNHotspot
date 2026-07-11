# Proxy-only architecture

## 1. Existing components and security boundaries

Proxy-only extends the current application; it does not create a second Android `VpnService` or transparent routing stack.

### VPN-only selection

`Upstreams.primary` is not a security boundary. It defaults to the VPN flow but can be overridden to a physical interface for the existing routing mode.

Proxy-only must:

1. enumerate or consume VPN-specific candidates;
2. read fresh `NetworkCapabilities`;
3. require `TRANSPORT_VPN`;
4. verify the VPN Hotspot app UID can bind a socket to the candidate;
5. reject physical, stale or unusable candidates.

If exactly one usable VPN candidate exists, it may be selected. If none exist, enter `WaitingForVpn`/fail-closed. If more than one usable candidate exists, enter `MultipleVpnCandidates`; the MVP does not choose by transient network-handle ordering.

The current routing path may continue to honor a user-selected physical `Upstreams.primary`. Proxy-only makes a stronger VPN-only promise and cannot inherit that permissive behavior.

### System tethering

Android system tethering owns DHCP, NAT and the direct Internet path. Proxy-only observes tethered interfaces and client identity only to expose the SOCKS listener safely.

A pure Proxy-only downstream must not create the existing full VPN-forwarding `RoutingManager`, otherwise laptop `DIRECT` traffic would still pass through the phone VPN.

### Root daemon conventions

The daemon already owns firewall mutation, neighbour identity, counters and deterministic cleanup. The proxy firewall must reuse:

- `routing/iptables.rs::IptablesRule`;
- idempotent insertion/deletion and `delete_repeated()`;
- `routing/firewall_cleanup.rs::clean()`;
- `IptablesTarget::Ipv4` and `Ipv6`.

The proxy firewall has an independent long-lived command/runtime. `SessionConfig` is not extended.

## 2. Ownership model

```text
┌──────────────────────── Android app process ──────────────────────────┐
│                                                                       │
│  Tethering/UI/settings flows                                          │
│             │                                                         │
│             ▼                                                         │
│  ProxyOnlyController                                                  │
│    ├── builds immutable desired states                                │
│    ├── validates VPN candidates                                       │
│    ├── serializes reconciliation                                      │
│    ├── owns ProxyFirewallClient                                       │
│    └── talks to ProxyServiceClient                                    │
│             │                           │                             │
│             │ service commands          │ long-lived daemon call      │
│             ▼                           ▼                             │
│  ProxyService                    ProxyFirewallRuntime                  │
│    ├── foreground lifecycle       (root daemon)                        │
│    ├── sole ProxyBackend owner    ├── IptablesRule ledger             │
│    ├── native instance handles    ├── iface+IPv4+MAC ACL              │
│    ├── listener/sessions          ├── IPv4 reject / IPv6 deny         │
│    ├── backend stats              ├── counters                        │
│    └── emergency listener close   └── deterministic Clean             │
│             │                                                         │
│             ▼                                                         │
│  HevProxyBackend                                                      │
│    ├── CONNECT                                                        │
│    ├── UDP ASSOCIATE                                                  │
│    ├── authentication                                                 │
│    ├── VPN-aware DNS                                                  │
│    └── per-FD network hook                                            │
│             │                                                         │
└─────────────┼─────────────────────────────────────────────────────────┘
              ▼
      validated Android VPN Network
```

`ProxyOnlyController` must not hold a `ProxyBackend` directly. `ProxyService` is the single owner of native lifecycle so it can close the listener even if controller reconciliation or state publication fails.

## 3. Product mode and downstream model

MVP UI:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

Internal state remains per downstream:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

`VPN_ROUTING_AND_PROXY` is deferred until chain-ordering and cleanup interaction tests exist.

## 4. Foreground-service lifecycle

The service start must originate from a user action allowed by current Android foreground-service rules.

Once Proxy-only is enabled, `ProxyService` remains alive through:

- `WaitingForTethering`;
- `WaitingForVpn`;
- `FailClosed(VpnLost)`;
- `FailClosed(DaemonUnavailable)`;
- recovery while the UI is backgrounded.

Waiting/fail-closed states keep the FGS and notification but have no active listener or sessions. This avoids attempting a new background FGS start when VPN or daemon service returns on Android 12+.

The service stops only when:

- the user disables Proxy-only;
- the product explicitly abandons recovery;
- the OS terminates the process.

Automatic resurrection after process death is not assumed. Boot/background restoration requires a separate policy-compliant design.

## 5. State machine

```text
Disabled
  └─ user enable / valid FGS start ─> ServiceActive.WaitingForTethering

ServiceActive.WaitingForTethering
  ├─ tethering available ─> WaitingForVpn
  └─ user disable ─> Disabled

ServiceActive.WaitingForVpn
  ├─ exactly one usable VPN ─> Starting
  ├─ multiple usable VPNs ─> FailClosed(MultipleVpnCandidates)
  ├─ app UID excluded ─> FailClosed(VpnPermissionDenied)
  └─ user disable ─> Disabled

ServiceActive.Starting
  ├─ deny + backend + outbound probes + allow ─> Running
  ├─ transaction exception ─> recoverFailClosed()
  └─ daemon/VPN loss ─> FailClosed

ServiceActive.Running
  ├─ ACL/client change ─> replace policy only
  ├─ VPN generation change ─> deny -> stop backend -> restart
  ├─ daemon loss ─> emergency listener close -> FailClosed
  ├─ tethering lost ─> stop backend -> WaitingForTethering
  └─ user disable ─> terminal stop -> Disabled

ServiceActive.FailClosed
  ├─ dependency restored ─> reconcile without restarting FGS
  └─ user disable ─> terminal stop -> Disabled
```

## 6. Exception-safe reconciliation

Immutable desired snapshots are sent to `Channel.CONFLATED`. One worker applies them serially.

Required worker contract:

1. New snapshots never cancel an in-flight transaction.
2. Every iteration executes in `NonCancellable` context for resource commit/rollback.
3. Every iteration has a `try/catch` boundary.
4. Unexpected exceptions invoke fail-closed recovery.
5. `publish` is isolated so observer failure cannot kill the resource worker.
6. Cleanup attempts every step and aggregates failures.
7. Worker `finally` invokes terminal `stopApplied()` in `NonCancellable` context.

Fast-path operations (`firewall.replace`, service ACL replacement, state publication) are not trusted to be exception-free.

### Cleanup ordering

When daemon is healthy:

1. request deny;
2. close listener and sessions through `ProxyService`;
3. stop firewall runtime;
4. report all failures.

When daemon is unavailable:

1. close listener and sessions immediately through `ProxyService`;
2. attempt firewall stop if possible;
3. mark cleanup required for daemon recovery;
4. report all failures.

A failed deny must not skip listener closure. A failed backend stop must not skip firewall cleanup. Silent `runCatching` is not acceptable.

## 7. Runtime key and replacement policy

Backend restart key:

```text
TCP port
+ UDP enabled/range
+ credentials version
+ validated VPN Network handle
+ sorted downstream interface/IPv4 set
+ backend configuration version
```

Client ordering, block-list changes and irrelevant `LinkProperties` churn are excluded. They perform policy/ACL replacement only.

VPN generation changes use full backend restart. Live `replaceNetwork()` is deferred.

## 8. Network binding and probe contract

Core invariant:

> Every Internet-facing socket is bound to the selected VPN after `socket()` and before `connect()`, `sendto()` or any packet-producing operation.

TCP:

```text
socket -> android_setsocknetwork -> connect -> relay
```

UDP:

```text
socket -> android_setsocknetwork -> connect/sendto -> relay
```

All retries, resolver sockets and address-family fallback paths use the same hook. Hook failure closes the FD.

### Outbound-only startup probes

Deny-first firewall state blocks client ingress, so startup probes are:

- app-UID bind probe;
- backend-created outbound TCP probe;
- backend-created outbound UDP probe;
- VPN-aware DNS probe;
- internal bind/listen readiness signal.

The probe must not connect to the SOCKS endpoint through a tethered interface before allows are installed. External listener reachability is tested only after allow commit.

## 9. DNS architecture

Domain-form requests use the validated VPN network only.

Phase 0 chooses between:

- bounded dedicated resolver workers using a network-aware API; or
- asynchronous Android resolver integration.

Requirements:

- strict timeout and cancellation;
- bounded queue and concurrency;
- no process-default DNS fallback;
- VPN generation loss cancels/invalidates results;
- DNS blackhole does not stall unrelated sessions.

## 10. UDP topology and capacity gate

The firewall policy cannot assume the client relay socket and Internet-facing socket are distinct.

Phase 0 must correlate:

- Hev FD creation and prepare-hook calls;
- control TCP connection;
- returned SOCKS `BND.ADDR/BND.PORT`;
- client-facing UDP relay FD/port;
- upstream-facing UDP FD/port;
- ingress interface for client packets and remote replies;
- conntrack state for remote replies.

If remote replies arrive at a relay-range port through the VPN interface, rule ordering must explicitly allow only verified `ESTABLISHED/RELATED` reply traffic before terminal reject. No broad VPN-interface allow is permitted.

The configured relay range never expands dynamically. If one port is consumed per association:

```text
effectiveUdpCapacity = min(configuredAssociationLimit, relayRangeSize)
```

Range exhaustion returns a controlled failure and metric.

## 11. Address-family policy

MVP is IPv4-only:

- listener and relay sockets are IPv4-only;
- no IPv6 endpoint is published;
- ip6tables rejects the TCP port and complete UDP range;
- dual-stack wildcard and IPv4-mapped behavior are tested.

Full IPv6 proxying is deferred.

## 12. Firewall and ACL model

IPv4 input policy:

```text
for TCP listener port and UDP relay range:
  allow only verified return traffic required by the discovered UDP topology
  for each permitted client:
    match input interface
    match source IPv4
    match source MAC
    count by MAC/downstream
    accept
  reject
```

The return-traffic rule is not approved until Phase 0 proves socket topology and conntrack behavior.

IPv6 policy:

```text
for TCP listener port and UDP relay range:
  reject
```

Interfaces without reliable MAC identity are unsupported in the MVP.

## 13. Daemon liveness

iptables state survives daemon death. Therefore:

- the long-lived firewall call/channel is a liveness signal;
- channel loss triggers immediate `ProxyService` emergency listener closure;
- the FGS remains alive in `FailClosed(DaemonUnavailable)`;
- daemon recovery runs Clean or explicit deny reconciliation;
- only then may a backend listener be started again.

A dead daemon plus closed listener is safe. A dead daemon plus live listener is forbidden.

## 14. Accounting

Two layers:

1. root firewall counters for listener ingress, keyed by MAC/downstream;
2. backend counters for TCP/UDP payload, active sessions, hook failures, resolver queue and UDP range exhaustion.

Firewall bytes include SOCKS framing and are not labelled application payload.

## 15. Transaction ordering

Startup:

1. ensure the user-started FGS is alive;
2. obtain tethered interfaces and MAC/IP identity;
3. select exactly one current usable VPN;
4. run app-UID bind validation;
5. start root firewall runtime in deny state for IPv4 and IPv6;
6. record partial firewall state;
7. start IPv4 backend through `ProxyService`;
8. record partial backend state;
9. run outbound TCP, UDP and DNS probes plus internal listener readiness;
10. replace deny with verified allow rules;
11. publish `Running` safely.

Critical failure or shutdown:

1. attempt deny when daemon is alive;
2. close backend/listener through `ProxyService` regardless of deny result;
3. stop firewall runtime regardless of backend-stop result;
4. aggregate/report cleanup failures;
5. remain in an active waiting/fail-closed FGS state unless user disabled;
6. on worker/scope termination, run final `stopApplied()`.
