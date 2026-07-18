# Proxy-only architecture

## 1. Purpose

Proxy-only keeps Android system tethering as the fast direct path and exposes an authenticated SOCKS5 endpoint for selected client applications.

```text
DIRECT client traffic
  -> Android system tethering
  -> physical network

Selected client traffic
  -> SOCKS5 listener in VPN Hotspot
  -> sockets bound to validated Android VPN Network
  -> VPN provider
```

Proxy-only does not create a second Android `VpnService`, does not transparently intercept client traffic and does not create the existing full VPN-routing session for a pure Proxy-only downstream.

## 2. Security boundaries

### VPN-only selection

`Upstreams.primary` is configurable and may represent a physical network. Proxy-only therefore:

1. enumerates VPN-specific candidates;
2. reads fresh `NetworkCapabilities`;
3. requires `TRANSPORT_VPN`;
4. verifies the VPN Hotspot app UID can bind to the network;
5. accepts exactly one usable candidate;
6. rejects zero, multiple, stale, physical or unusable candidates.

The existing routing mode may continue to honor a user-selected physical upstream. Proxy-only makes a stronger VPN-only claim and cannot inherit that behavior.

### System tethering

Android owns DHCP, NAT and the direct path. VPN Hotspot observes tethered interfaces and neighbors only to expose the proxy safely.

A pure Proxy-only downstream does not create `RoutingManager`; otherwise `MATCH,DIRECT` traffic on the laptop would still use the phone VPN.

### Root daemon

The root daemon owns:

- listener exposure;
- deny-first policy;
- interface + IPv4 + MAC ACLs;
- IPv6 denial;
- kernel counters;
- deterministic firewall cleanup.

The implementation reuses:

- `routing/iptables.rs::IptablesRule`;
- `IptablesChain`;
- idempotent insertion/deletion;
- `delete_repeated()`;
- `routing/firewall_cleanup.rs::clean()`;
- `IptablesTarget::Ipv4` and `Ipv6`.

The proxy firewall has its own long-lived command/runtime. `SessionConfig` remains unchanged.

## 3. Ownership model

```text
┌──────────────────────── Android app process ────────────────────────┐
│                                                                     │
│  settings/UI/tethering/VPN flows                                    │
│              │                                                      │
│              ▼                                                      │
│  ProxyOnlyController                                                │
│    ├── owns desired-state/retry event loop                           │
│    ├── validates VPN candidates                                     │
│    ├── consumes ActivationGrant                                     │
│    ├── owns ProxyFirewallClient                                     │
│    └── calls ProxyServiceClient                                     │
│              │                              │                       │
│              ▼                              ▼                       │
│  ProxyService                         vpnhotspotd                    │
│    ├── foreground lifecycle            ProxyFirewallRuntime         │
│    ├── sole ProxyBackend owner          ├── IptablesRule ledger     │
│    ├── native handles                   ├── ACL/counters             │
│    ├── listener/sessions                └── deterministic cleanup    │
│    ├── emergency close                                              │
│    └── backend stats                                                 │
│              │                                                      │
│              ▼                                                      │
│  HevProxyBackend                                                    │
│    ├── CONNECT                                                      │
│    ├── UDP ASSOCIATE                                                │
│    ├── authentication                                               │
│    ├── VPN-aware DNS                                                │
│    └── per-FD network hook                                          │
└──────────────┼──────────────────────────────────────────────────────┘
               ▼
       validated Android VPN Network
```

`ProxyOnlyController` never owns a backend/native handle. `ProxyService` is the only backend owner.

## 4. Product and downstream model

MVP UI:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

Internal model:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Mixed routing+proxy mode remains deferred until rule-ordering and cleanup interaction tests exist.

## 5. Activation model

### Configuration versus activation permission

Persisted `enabled=true` means the user wants the feature enabled. It does not authorize an arbitrary background foreground-service start.

```kotlin
data class ActivationGrant(
    val id: UUID,
    val issuedAtElapsedRealtime: Long,
    val source: ActivationSource,
)
```

A grant is issued only by an Android-permitted foreground user action, such as tapping Enable or Resume while the app is visible.

`DesiredProxyState` contains:

```kotlin
val activationGrant: ActivationGrant?
```

The controller consumes the grant only after `ProxyService` activation succeeds. A consumed or expired grant cannot be reused.

### Activation-required state

When settings are enabled, the service is inactive and no valid grant exists:

```text
ActivationRequired
```

The controller does not call `activateFeature()`. The UI asks the user to open the app and resume.

### Service calls before activation

All controller calls are guarded by `serviceActivated`. The client contract also makes pre-activation stop/wait/emergency calls idempotent no-ops returning structured reports rather than throwing.

## 6. Foreground-service lifecycle

The service starts only with a valid activation grant in an allowed user context.

Once activated, it remains alive while the feature is enabled through:

- `WaitingForTethering`;
- `WaitingForVpn`;
- `VpnPermissionDenied`;
- `MultipleVpnCandidates`;
- VPN loss;
- daemon loss;
- cleanup-degraded retry;
- background dependency recovery.

Waiting/fail-closed states have no listener or active sessions. The service keeps a blocked/waiting notification.

The service stops only when:

- the user disables Proxy-only;
- the product explicitly abandons recovery;
- the OS terminates the process.

Automatic resurrection after process death is not assumed.

## 7. State machine

```text
Disabled
  └─ foreground user enable + ActivationGrant
       -> ServiceStarting
       -> ServiceActive.WaitingForTethering

Enabled but service inactive and no grant
  -> ActivationRequired

ServiceActive.WaitingForTethering
  ├─ tethering available -> WaitingForVpn
  └─ user disable -> terminal stop -> Disabled

ServiceActive.WaitingForVpn
  ├─ exactly one usable VPN -> StartingBackend
  ├─ no VPN -> remain WaitingForVpn
  ├─ multiple VPNs -> MultipleVpnCandidates
  ├─ app UID denied -> VpnPermissionDenied
  └─ user disable -> Disabled

ServiceActive.StartingBackend
  ├─ deny + backend + typed probes + allow -> Running
  ├─ typed probe failure -> typed waiting/fail-closed state
  ├─ unexpected exception -> recover fail closed
  └─ cleanup failure -> CleanupDegraded

ServiceActive.Running
  ├─ client/ACL change -> replace policy only
  ├─ VPN generation change -> deny -> stop -> restart
  ├─ VPN/daemon loss -> listener close -> FailClosed
  ├─ tethering lost -> stop backend -> WaitingForTethering
  ├─ cleanup failure -> CleanupDegraded
  └─ user disable -> terminal stop -> Disabled

ServiceActive.CleanupDegraded
  ├─ internal backoff retry -> retry unresolved items
  ├─ all debt resolved -> reconcile last desired snapshot
  └─ user disable -> continue cleanup + terminal feature stop
```

`CleanupDegraded -> retry` is driven by an internal retry event, not only external flow emissions.

## 8. Controller event loop

The controller consumes:

```kotlin
sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}
```

One `Channel.CONFLATED` feeds one worker. The latest normalized snapshot is retained separately for retry.

Properties:

1. no `collectLatest` around resource operations;
2. one transaction at a time;
3. timeout is treated as an iteration failure;
4. parent cancellation is rethrown;
5. every iteration has an exception boundary;
6. `finally` performs terminal cleanup in `NonCancellable` context;
7. publication and reporting failures do not kill the worker.

## 9. Cleanup debt model

Debt is a set of unresolved resources/actions:

```kotlin
data class CleanupDebt(
    val listenerClosePending: Boolean,
    val serviceHandlePending: ProxyServiceHandle?,
    val firewallHandlePending: ProxyFirewallHandle?,
    val firewallDenyPending: Boolean,
    val firewallStopPending: Boolean,
    val daemonCleanPending: Boolean,
    val featureStopPending: Boolean,
    val failures: List<CleanupFailure>,
    val generation: Long,
    val attempt: Int,
)
```

### Creation

Cleanup records an item as unresolved only when:

- that resource existed; and
- the corresponding cleanup step failed or could not be attempted safely.

An idle waiting snapshot with `applied == null` does not call emergency close and cannot fabricate debt.

### Retry order

For each retry:

1. if a known service handle exists, retry `stopBackend`;
2. if listener closure remains uncertain and service is active, call emergency close;
3. if a firewall handle exists and daemon is healthy, retry `denyAll` when pending;
4. retry `firewall.stop` when pending;
5. run daemon Clean/deny when pending;
6. retry terminal `stopFeature` only when requested;
7. clear each field only after that item succeeds.

A healthy-daemon `firewall.stop` failure retains the handle and both deny/stop obligations until they succeed. Listener closure alone cannot clear firewall debt.

### Retry trigger

Unresolved debt schedules one retry job:

```text
initial delay -> exponential backoff -> bounded maximum -> jitter
```

The retry posts `RetryCleanupDebt(generation)` into the conflated controller channel. Stale generations are ignored. A successful retry resets backoff.

This lets transient failures recover with no external settings/network/client change.

### Restart gate

The controller uses explicit control flow:

```text
if cleanup debt remains:
  publish CleanupDegraded
  schedule retry
  return
```

It does not use `check(cleanupDebt == null)` as control flow.

## 10. Cleanup ordering

When daemon is healthy:

1. request explicit IPv4/IPv6 deny;
2. close listener and sessions through `ProxyService`;
3. stop firewall runtime;
4. aggregate failures into itemized debt;
5. schedule debt retry if needed.

When daemon is unavailable:

1. close listener and sessions immediately;
2. retain firewall handle/action debt;
3. mark daemon Clean/deny pending;
4. remain foreground fail closed;
5. retry after daemon recovery.

A failed deny never skips listener closure. A failed backend stop never skips firewall cleanup. Feature stop has one owner: terminal shutdown.

## 11. Typed probe model

Startup returns typed outcomes:

```kotlin
sealed interface ProbeEvaluation {
    data object Success : ProbeEvaluation
    data object VpnPermissionDenied : ProbeEvaluation
    data class TcpFailed(val cause: ProbeFailure) : ProbeEvaluation
    data class UdpFailed(val cause: ProbeFailure) : ProbeEvaluation
    data class DnsFailed(val cause: ProbeFailure) : ProbeEvaluation
    data class ListenerNotReady(val cause: ProbeFailure) : ProbeEvaluation
}
```

Required probes are configuration-aware:

```text
always: APP_UID_BIND, OUTBOUND_TCP, VPN_DNS, INTERNAL_LISTENER_READY
when udpEnabled: OUTBOUND_UDP
```

`APP_UID_BIND` permission failure maps to `VpnPermissionDenied`, not generic internal failure.

Typed expected failures are handled as state transitions. Unexpected exceptions use the generic fail-closed recovery path.

## 12. Network binding

Every Internet-facing socket follows:

```text
socket()
  -> android_setsocknetwork(validated VPN handle, fd)
  -> connect()/sendto()/resolver operation
```

All retries, fallback paths and resolver-created sockets use the same hook. Hook failure closes the FD.

## 13. DNS

Domain-form requests use the validated VPN network only.

Phase 0 chooses:

- bounded network-aware resolver workers; or
- asynchronous Android network-aware resolution.

Requirements:

- strict timeout;
- bounded queue/concurrency;
- no process-default fallback;
- VPN-generation invalidation;
- DNS blackhole does not stall unrelated sessions.

## 14. UDP topology and capacity

Phase 0 correlates:

- control TCP connection;
- relay and upstream FDs;
- returned `BND.ADDR/BND.PORT`;
- local ports;
- network-binding hook calls;
- packet ingress/egress interfaces;
- conntrack state of remote replies.

No broad VPN-interface allow is permitted. Any return rule must be narrow and evidence-backed.

If one port is consumed per association:

```text
effectiveUdpCapacity = min(configuredAssociationLimit, relayRangeSize)
```

The range never expands dynamically.

## 15. Firewall model

Protocol state is explicit:

```proto
message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool deny_all_ipv4 = 7;
  bool deny_all_ipv6 = 8;
  optional VerifiedUdpReturnPolicy udp_return_policy = 9;
}
```

An empty ACL is not treated as an implicit deny-all flag.

IPv4 policy:

```text
for TCP listener and UDP relay range:
  allow only verified UDP return traffic when required
  allow each iface + source IPv4 + source MAC client
  reject
```

IPv6 policy:

```text
reject TCP listener and complete UDP range
```

Interfaces without reliable MAC identity remain unsupported in MVP.

## 16. Daemon liveness

iptables state survives daemon death.

Therefore:

- long-lived firewall call/channel is the liveness signal;
- channel loss triggers immediate service emergency close;
- firewall handle and Clean obligations become debt;
- service remains foreground but listener-free;
- daemon recovery completes Clean/deny before restart.

A dead daemon plus a closed listener is safe. A dead daemon plus a live listener is forbidden.

## 17. Transaction ordering

Startup:

1. verify settings enabled;
2. require active service or consume a valid activation grant;
3. resolve cleanup debt first;
4. obtain tethered interface and MAC/IP identities;
5. select exactly one usable VPN;
6. start explicit deny firewall runtime;
7. record firewall handle immediately;
8. start backend through `ProxyService`;
9. record service handle immediately;
10. run typed, config-aware outbound probes;
11. handle typed failure without generic `check()`;
12. replace deny with verified allow rules;
13. publish `Running` safely.

Shutdown or critical failure:

1. explicit deny when possible;
2. close listener/sessions;
3. stop firewall runtime;
4. record itemized debt;
5. schedule retry;
6. remain listener-free and fail closed;
7. terminal feature stop only when user disables or worker terminates.
