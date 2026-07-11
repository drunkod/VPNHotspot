# Proxy-only architecture

## 1. Existing components to reuse

The design should extend the current architecture rather than build a second networking stack.

### VPN upstream discovery

`mobile/src/main/java/be/mygod/vpnhotspot/net/monitor/Upstreams.kt` already:

- requests networks with `NetworkCapabilities.TRANSPORT_VPN`;
- exposes the selected VPN as `Upstreams.primary`;
- exposes the normal Internet path as `Upstreams.fallback`;
- supplies both `android.net.Network` and `LinkProperties`;
- reacts when the selected network is replaced or lost.

The proxy controller should consume `Upstreams.primary` directly. It must not rediscover the VPN with `activeNetwork`, because the Android default network may itself change when a VPN is active.

### Tethering lifecycle

`mobile/src/main/java/be/mygod/vpnhotspot/TetheringService.kt` monitors Android tethered interfaces and currently creates a `RoutingManager` for managed downstreams.

Proxy-only mode needs a parallel downstream state:

```text
Unmanaged by VPN forwarding, but eligible to reach the proxy listener.
```

The system tethering service keeps responsibility for DHCP, NAT and the direct path. VPN Hotspot only receives interface lifecycle events so it can install or remove proxy firewall rules.

### Root daemon

The current `vpnhotspotd` already owns:

- session-scoped routing mutation lifecycles;
- firewall and route reconciliation;
- neighbour monitoring;
- client MAC/IP identity;
- traffic counter reporting;
- deterministic cleanup;
- network-specific TCP and UDP socket helpers for daemon-owned functions.

The proxy data plane should not move into this daemon for the prototype. The root daemon should only control kernel policy around the app-process listener and expose counters/ACL state.

## 2. Proposed component model

```text
┌──────────────────────── Android app process ────────────────────────┐
│                                                                     │
│  Tethering UI                                                       │
│      │                                                              │
│      ▼                                                              │
│  ProxyOnlyController                                                │
│      ├── observes Upstreams.primary                                 │
│      ├── observes active system-tethering interfaces                │
│      ├── validates settings                                         │
│      ├── starts/stops ProxyService                                  │
│      └── requests root firewall reconciliation                      │
│                                                                     │
│  ProxyService                                                       │
│      ├── foreground lifecycle                                       │
│      ├── loads libhevsocks5server.so                                │
│      ├── passes VPN network handle to native bridge                 │
│      ├── publishes endpoint/status/counters                         │
│      └── closes all sessions when the VPN generation changes        │
│                                                                     │
│  Hev JNI bridge                                                     │
│      ├── start(config, networkHandle)                                │
│      ├── updateNetwork(networkHandle, generation)                    │
│      ├── stop()                                                      │
│      └── callbacks: connection/accounting/errors                     │
│                                                                     │
│  HevSocks5Server fork                                               │
│      ├── TCP CONNECT                                                │
│      ├── UDP ASSOCIATE                                              │
│      ├── username/password                                          │
│      └── outbound socket hook -> android_setsocknetwork()            │
│                                                                     │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ listener on downstream address/port
                               ▼
┌──────────────────────── root daemon ────────────────────────────────┐
│ ProxyFirewallRuntime                                                │
│   ├── allow only active tethering interfaces                        │
│   ├── allow only known/unblocked clients                            │
│   ├── reject upstream/VPN/public-interface access                   │
│   ├── maintain ingress/egress counters                              │
│   └── deterministic cleanup                                         │
└─────────────────────────────────────────────────────────────────────┘
```

## 3. Operating modes

Introduce an explicit enum rather than several interacting booleans:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
    VPN_ROUTING_AND_PROXY,
}
```

### `VPN_ROUTING`

Existing behaviour. Managed downstream traffic is forwarded through the VPN.

### `PROXY_ONLY`

- Android system tethering remains active.
- No full VPN routing session is created for the downstream.
- Proxy listener is exposed only on selected downstream interfaces.
- Proxy outbound sockets are pinned to `Upstreams.primary.network`.
- Direct laptop traffic continues through Android's standard tethering route.

### `VPN_ROUTING_AND_PROXY`

Existing full routing remains active and the SOCKS5 endpoint is also available. This mode is useful for compatibility testing, but it does not provide a direct fast path for clients.

## 4. State machine

```text
Disabled
  └─ enable ─> WaitingForTethering

WaitingForTethering
  ├─ downstream available, no VPN ─> WaitingForVpn
  └─ disable ─> Disabled

WaitingForVpn
  ├─ VPN available ─> Starting
  ├─ downstream lost ─> WaitingForTethering
  └─ disable ─> Disabled

Starting
  ├─ native listener + firewall committed ─> Running
  ├─ VPN changed ─> Stopping
  └─ error ─> Error

Running
  ├─ VPN generation changed ─> Stopping -> Starting
  ├─ VPN lost ─> FailClosed
  ├─ downstream lost ─> Stopping
  └─ disable ─> Stopping

FailClosed
  ├─ VPN restored ─> Starting
  ├─ downstream lost ─> WaitingForTethering
  └─ disable ─> Disabled
```

Suggested Kotlin model:

```kotlin
sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data object Starting : ProxyOnlyState
    data class Running(
        val endpoints: List<ProxyEndpoint>,
        val vpnNetworkHandle: Long,
        val vpnInterfaces: List<String>,
        val activeTcpConnections: Int,
        val activeUdpAssociations: Int,
    ) : ProxyOnlyState
    data class FailClosed(val reason: String) : ProxyOnlyState
    data class Error(val message: String, val cause: Throwable?) : ProxyOnlyState
    data object Stopping : ProxyOnlyState
}
```

## 5. Network binding contract

The core invariant is:

> Every Internet-facing socket created for a SOCKS request is bound to the exact VPN `Network` before it sends traffic.

For TCP:

```text
socket()
  -> android_setsocknetwork(networkHandle, fd)
  -> connect()
  -> relay
```

For UDP:

```text
socket()
  -> android_setsocknetwork(networkHandle, fd)
  -> connect()/sendto()
  -> relay
```

For domain requests, DNS must use the selected VPN network. Acceptable implementations are:

- call `android_getaddrinfofornetwork()` in native code; or
- resolve through a Java/Kotlin callback that calls `Network.getAllByName()`.

Using the process-default resolver is not acceptable in fail-closed mode.

## 6. VPN generation changes

A `Network` handle is not a permanent identifier. A reconnect may create a new handle even when the visible VPN interface name is unchanged.

The controller should maintain a monotonically increasing generation:

```kotlin
data class ProxyUpstream(
    val network: Network,
    val handle: Long,
    val generation: Long,
    val interfaces: List<String>,
)
```

On any generation change:

1. stop accepting new proxy requests;
2. close all current TCP sessions and UDP associations;
3. update the native network handle;
4. restart the listener or worker set;
5. commit firewall state only after native startup succeeds.

Do not allow existing associations to silently continue on an old or missing `Network`.

## 7. Firewall and ACL model

The proxy must never become an open proxy on Wi-Fi, cellular, VPN or loopback interfaces.

Desired policy:

```text
INPUT to proxy port:
  accept established/related
  accept active downstream interface + known allowed client IP/MAC
  reject all other interfaces
```

The exact chain naming should follow existing daemon conventions. The design should be expressed as desired mutations and reconciled/cleaned in the same way as current routing state.

The daemon receives:

- listener TCP port;
- listener UDP port or UDP port range;
- active downstream interfaces;
- client MAC/IP mappings;
- blocked-client set;
- proxy generation.

A client added to the block list must lose both future and active proxy access. Active TCP/UDP sessions should also be closed by the app process through an ACL update callback.

## 8. Accounting model

Two accounting layers are recommended:

1. **Kernel ingress counters** in the root daemon, keyed by downstream and client identity. These prove how many bytes entered or left the proxy endpoint.
2. **Native relay counters** from the Hev fork, keyed by client source address/session, for TCP/UDP payload and active-session metrics.

The initial prototype may ship with kernel counters plus aggregate native counters, but it must document that firewall byte counts include SOCKS protocol overhead.

Suggested daemon enum additions:

```proto
enum DaemonTrafficSource {
  // existing values...
  DAEMON_TRAFFIC_SOURCE_PROXY_TCP = 5;
  DAEMON_TRAFFIC_SOURCE_PROXY_UDP = 6;
}
```

## 9. Service ownership

Preferred ownership:

- `TetheringService`: knows active system tethering interfaces;
- `ProxyOnlyController`: combines settings, tethering and VPN upstream flows;
- `ProxyService`: owns foreground/native data-plane lifecycle;
- `vpnhotspotd`: owns firewall/ACL/counter kernel state.

Avoid starting one proxy instance per downstream. A single listener runtime can bind to wildcard addresses while the root firewall controls reachability, or it can bind one socket per downstream address. The second option is safer but requires dynamic listener reconciliation.

For the first prototype, bind explicitly to discovered downstream IPv4 addresses when stable. Fall back to wildcard binding only with deny-by-default root firewall rules installed first.

## 10. Failure ordering

Startup must be transactional from the user's perspective:

1. resolve active downstream addresses;
2. verify a VPN `Network` exists;
3. install a temporary deny rule for the target port;
4. start native SOCKS5 listener;
5. verify outbound network binding with a probe socket;
6. install final per-client allow rules;
7. publish `Running` state.

Shutdown ordering:

1. replace allow rules with deny rules;
2. stop accepting new clients;
3. close all native sessions;
4. remove listener;
5. remove firewall state;
6. publish the final state.

This ordering prevents a brief unprotected listener window.
