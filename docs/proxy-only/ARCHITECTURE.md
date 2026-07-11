# Proxy-only architecture

## 1. Purpose

Proxy-only keeps Android system tethering as the fast direct path and exposes an authenticated SOCKS5 endpoint for selected client applications.

```text
Unselected laptop traffic
  -> FlClash DIRECT
  -> ordinary Android tethering
  -> physical network

Selected laptop traffic
  -> FlClash PhoneVPN
  -> SOCKS5 listener in VPN Hotspot
  -> socket bound to validated Android VPN Network
  -> VPN exit

Selected traffic with WARP
  -> FlClash WARP outbound
  -> dialer-proxy: PhoneVPN
  -> phone VPN transport
  -> Cloudflare WARP exit
```

The feature does not create another Android `VpnService` and does not transparently intercept arbitrary tethered traffic.

## 2. Security boundaries

### VPN-only selection

`Upstreams.primary` is not proof of VPN transport because existing routing mode permits a physical interface override.

Proxy-only must:

1. enumerate or consume VPN-specific candidates;
2. read fresh `NetworkCapabilities`;
3. require `TRANSPORT_VPN`;
4. verify the VPN Hotspot app UID can bind to the candidate;
5. select only when exactly one usable candidate exists;
6. reject zero, multiple, stale, physical or app-inaccessible candidates.

The MVP does not choose between multiple VPNs by sorting transient network handles.

### System tethering

Android owns DHCP, NAT and the direct path. A pure Proxy-only downstream must not create the existing full VPN-forwarding `RoutingManager`; otherwise client `DIRECT` traffic would still use the phone VPN.

### Root daemon

The root daemon owns listener exposure, ACLs, counters and deterministic cleanup. The proxy firewall must reuse:

- `routing/iptables.rs::IptablesRule` and `IptablesChain`;
- the existing applied mutation ledger;
- `delete_repeated()` semantics;
- `routing/firewall_cleanup.rs::clean()`;
- `IptablesTarget::Ipv4` and `Ipv6`.

The proxy firewall has an independent long-lived command/runtime. `SessionConfig` is not extended.

## 3. Ownership model

```text
Tethering/UI/settings flows
        |
        v
ProxyOnlyController
  - validates and normalizes desired state
  - owns serialized reconciliation
  - talks to ProxyServiceClient
  - owns ProxyFirewallClient
        |                         |
        v                         v
ProxyService                 vpnhotspotd
  - foreground lifecycle       - proxy firewall runtime
  - sole ProxyBackend owner    - iface+IPv4+MAC ACL
  - native handles             - IPv4 reject / IPv6 deny
  - listener/sessions          - counters
  - backend statistics         - deterministic Clean
  - emergency close
        |
        v
HevProxyBackend
  - CONNECT
  - UDP ASSOCIATE
  - VPN-aware DNS
  - per-FD network hook
        |
        v
validated Android VPN Network
```

The controller must never hold a `ProxyBackend` or native handle directly.

## 4. Product mode and downstream model

MVP UI:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

Internal representation remains per downstream:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Mixed routing+proxy mode is deferred until chain-ordering and cleanup interaction tests exist.

## 5. Foreground-service lifecycle

The first FGS start must originate from a user action allowed by current Android rules.

A disabled desired state:

- never calls service activation;
- closes any existing backend;
- stops the FGS.

After user-approved enable, `ProxyService` remains alive through:

- `WaitingForTethering`;
- `WaitingForVpn`;
- `MultipleVpnCandidates`;
- `VpnPermissionDenied`;
- VPN-loss fail-closed;
- daemon-loss fail-closed;
- cleanup-degraded recovery;
- background recovery while the UI is not visible.

These states retain the notification but have no listener or active sessions. Dependency recovery uses the existing service and does not attempt a new background FGS start.

The service stops only when the user disables the feature, recovery is explicitly abandoned, or the OS terminates the process. Automatic resurrection after process death is not assumed.

## 6. State machine

```text
Disabled
  -> user enable / approved FGS start
ServiceActive.WaitingForTethering
  -> tethering appears
ServiceActive.WaitingForVpn
  -> exactly one usable VPN
ServiceActive.StartingBackend
  -> deny + backend + outbound probes + allow
ServiceActive.Running

Any dependency or transaction failure
  -> close listener
  -> ServiceActive.FailClosed or CleanupDegraded

CleanupDegraded
  -> retry emergency close / daemon Clean / firewall stop
  -> only after debt clears may startup resume

User disable
  -> terminal cleanup
  -> stop FGS
  -> Disabled
```

## 7. Serialized reconciliation

Immutable normalized snapshots are sent to one `Channel.CONFLATED`. One worker applies them serially.

Contract:

1. No `collectLatest` around resource transactions.
2. New snapshots never cancel an in-flight commit/rollback.
3. Transaction timeout is handled as a fail-closed iteration error.
4. Parent `CancellationException` is rethrown.
5. Every unexpected exception enters contained fail-closed recovery.
6. Recovery itself has an exception boundary.
7. Publication and reporter failures cannot kill the resource worker.
8. Cleanup attempts all steps and aggregates failures.
9. Worker `finally` runs terminal cleanup in `NonCancellable` context.

### Activation ordering

The worker checks `settings.enabled` before service activation. Waiting/fail-closed transitions call `ProxyService.enterWaiting(state)` rather than only publishing controller state.

### Runtime key

Backend restart key:

```text
TCP port
+ UDP enabled/range
+ credentials version
+ validated VPN Network handle
+ sorted downstream interface/IPv4 set
+ backend configuration version
```

Client ordering and block-list changes perform policy/ACL replacement only. VPN handle change performs full backend restart.

## 8. Cleanup and cleanup debt

Normal cleanup attempts, independently:

1. root firewall deny when daemon is alive;
2. service-owned backend stop;
3. emergency listener close when normal stop failed or ownership is uncertain;
4. firewall runtime stop;
5. waiting transition or terminal FGS stop;
6. error/state publication.

Each step has a timeout and structured error context. A failed deny must not skip listener closure; a failed backend stop must not skip firewall stop.

If any safety-relevant step fails, create cleanup debt containing unresolved service/firewall handles and required recovery actions.

While debt exists:

- listener remains closed;
- no backend startup is permitted;
- emergency close is retried;
- daemon recovery runs Clean or explicit deny reconciliation;
- UI/notification shows cleanup-degraded state;
- successful cleanup clears debt before normal reconciliation.

Discarding handles after a failed stop is forbidden.

## 9. Network binding and probes

Core invariant:

> Every Internet-facing socket is bound to the validated VPN after `socket()` and before `connect()`, `sendto()` or another packet-producing operation.

```text
TCP: socket -> android_setsocknetwork -> connect -> relay
UDP: socket -> android_setsocknetwork -> connect/sendto -> relay
```

All retry, resolver and address-fallback paths use the same hook. Hook failure closes the FD.

### Outbound-only startup probes

Before ingress allow rules:

- app-UID bind probe;
- backend-created outbound TCP probe;
- backend-created outbound UDP probe;
- VPN-aware DNS probe;
- internal bind/listen readiness signal.

The pre-allow probe never connects to the SOCKS endpoint through a tethered interface. External reachability is tested after allow commit.

## 10. DNS

Domain targets resolve through the validated VPN only.

Phase 0 selects:

- bounded dedicated network-aware resolver workers; or
- asynchronous Android network-aware resolver integration.

Requirements:

- strict timeout;
- bounded queue/concurrency;
- no process-default fallback;
- VPN generation invalidates results;
- DNS blackhole does not stall unrelated sessions.

## 11. UDP topology and capacity

The firewall cannot assume client-facing relay and Internet-facing UDP use different FDs.

Phase 0 correlates:

- control TCP FD;
- returned `BND.ADDR/BND.PORT`;
- relay FD/port;
- upstream FD/port;
- shared/separate role;
- prepare-hook calls;
- ingress interfaces for client packets and remote replies;
- conntrack state.

Any return-traffic rule remains provisional until packet capture and conntrack evidence prove exact narrow semantics. Broad VPN-interface allow is prohibited.

The relay range never expands dynamically. If one port is consumed per association:

```text
effectiveUdpCapacity = min(configuredAssociationLimit, relayRangeSize)
```

Exhaustion returns a controlled failure and metric.

## 12. Address-family policy

MVP is IPv4-only:

- listener and relay sockets bind IPv4 only;
- no IPv6 endpoint is published;
- ip6tables rejects TCP port and complete UDP range;
- native IPv6, dual-stack wildcard and IPv4-mapped behavior are tested.

Full IPv6 relay is deferred.

## 13. Firewall and ACL policy

IPv4 client allow:

```text
input interface
+ source IPv4
+ source MAC
+ destination TCP port or UDP relay range
```

IPv4 ends in reject. Interfaces without reliable MAC identity are unsupported.

IPv6 policy rejects the TCP port and complete UDP range.

## 14. Daemon liveness

iptables state survives daemon death. Therefore:

- long-lived firewall command/channel is the liveness signal;
- unexpected completion triggers immediate service emergency close;
- FGS remains alive in daemon-unavailable fail-closed state;
- cleanup debt records possible stale kernel state;
- daemon recovery runs deterministic Clean or explicit deny;
- only after debt clears may backend startup resume.

A dead daemon plus closed listener is safe. A dead daemon plus live listener is forbidden.

## 15. Transaction ordering

Startup:

1. confirm feature enabled and user-started FGS active;
2. confirm no cleanup debt;
3. obtain downstream identity;
4. select exactly one usable VPN;
5. run app-UID bind validation;
6. start root firewall runtime in deny state;
7. record firewall handle immediately;
8. start backend through `ProxyService`;
9. record service handle immediately;
10. run outbound-only probes;
11. commit verified allow rules;
12. publish `Running` safely.

Critical failure:

1. attempt deny when possible;
2. stop backend/listener;
3. emergency close if necessary;
4. stop firewall runtime;
5. record cleanup debt for unresolved steps;
6. enter listener-free waiting/fail-closed FGS state;
7. publish/report safely.
