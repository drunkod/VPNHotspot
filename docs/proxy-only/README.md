# Proxy-only mode design

Status: round-3 corrected design; approved to begin **Phase 0 feasibility work only**  
Target repository snapshot: `drunkod/VPNHotspot@9b6354c69cbe42c87c8d3a28add8405c3ae79b8d`  
Target branch: `agent/proxy-only-design`

## Goal

Add an optional **Proxy only** mode. Android system tethering remains the fast direct path, while VPN Hotspot exposes an authenticated SOCKS5 endpoint for traffic that must use the phone VPN.

```text
Unselected laptop applications
  -> FlClash DIRECT
  -> ordinary Android tethering
  -> physical Wi-Fi/cellular network

Selected laptop applications
  -> FlClash PROCESS-NAME rule
  -> SOCKS5 endpoint in VPN Hotspot
  -> outbound socket bound to a validated Android VPN Network
  -> VPN provider
  -> Internet

Selected laptop applications with WARP
  -> FlClash WireGuard/WARP outbound
  -> dialer-proxy: PhoneVPN
  -> SOCKS5 endpoint in VPN Hotspot
  -> phone VPN
  -> Cloudflare WARP
  -> Internet
```

Expected public IP:

- `DIRECT`: physical/carrier IP;
- `PhoneVPN`: phone VPN exit IP;
- `WARP-via-PhoneVPN`: Cloudflare WARP IP.

## Security contract

When Proxy-only reports `Running`:

1. exactly one usable VPN candidate has current `TRANSPORT_VPN` capability;
2. the VPN Hotspot app UID is allowed to use that VPN;
3. every Internet-facing TCP, UDP and DNS socket is bound to that VPN before sending;
4. ingress is allowed only for authenticated, explicitly permitted tethered clients;
5. any critical dependency failure closes the listener and sessions without direct fallback;
6. no new backend or firewall runtime starts while unresolved cleanup debt exists.

`Upstreams.primary` is not proof of VPN use because the existing routing mode permits a user-selected physical upstream. Proxy-only uses VPN-specific candidates and fresh capability validation.

If multiple usable VPN candidates are visible, the MVP fails closed with `MultipleVpnCandidates`. It does not choose by transient network-handle ordering.

## Accepted MVP decisions

1. The UI exposes `VPN_ROUTING` and `PROXY_ONLY`; mixed routing+proxy mode is deferred.
2. Ordinary system Wi-Fi/USB tethering supplies the direct path.
3. `ProxyService` is the sole owner of `ProxyBackend`, native handles, sessions and backend statistics.
4. `ProxyOnlyController` owns desired-state reconciliation and talks to `ProxyServiceClient`; it never invokes the backend directly.
5. The first backend is a pinned, minimally forked HevSocks5Server.
6. Every outbound FD passes a testable Android-network binding hook.
7. SOCKS domain targets use bounded or asynchronous VPN-aware DNS.
8. The root daemon owns firewall, client ACL, counters and deterministic cleanup.
9. Proxy firewall code reuses the existing `IptablesRule` ledger and `firewall_cleanup` machinery.
10. Fail-closed is mandatory; physical fallback is not an MVP option.
11. Standard SOCKS5 `CONNECT` and `UDP ASSOCIATE` are required.
12. MVP is IPv4-only with explicit IPv6 deny rules.
13. Client allow identity is downstream interface + IPv4 + MAC.
14. VPN generation changes fully restart the backend.
15. TPROXY and transparent interception are deferred.

## Foreground activation contract

Persisted `enabled=true` is configuration, not permission to start a foreground service from any context.

`DesiredProxyState` carries an optional one-time `ActivationGrant`:

```text
foreground user enable/resume action
  -> issue ActivationGrant
  -> controller consumes grant exactly once
  -> ProxyService activation succeeds
  -> service remains alive while feature is enabled
```

If the feature is enabled but the service is not active and no valid grant exists, the controller publishes:

```text
ActivationRequired
```

The UI should explain: **Open VPN Hotspot and tap Resume to restart Proxy-only.**

A background process restart must never call `activateFeature()` solely because persisted settings say enabled.

Every service command is guarded by `serviceActivated`. Before activation:

- `enterWaiting`, `stopBackend`, `emergencyCloseListener` and `stopFeature` return a structured no-op report;
- they do not throw;
- they do not imply that a listener existed.

After activation, the foreground service remains alive through:

- `WaitingForTethering`;
- `WaitingForVpn`;
- `VpnPermissionDenied`;
- VPN loss or daemon loss;
- cleanup-degraded retry;
- background dependency recovery.

Waiting and fail-closed states have no active listener and show a persistent blocked/waiting notification. Automatic resurrection after process death is not assumed.

## Typed probe contract

Deny-first startup blocks client ingress, so startup probes are outbound-only:

- app-UID bind probe;
- backend-created outbound TCP probe;
- backend-created outbound UDP probe when UDP is enabled;
- VPN-aware DNS probe;
- internal listener bind/listen readiness signal.

Probe results are typed, not reduced to one Boolean.

Required mappings include:

- app-UID bind `EPERM` -> `VpnPermissionDenied`;
- TCP failure -> `FailClosed(TcpProbeFailed)`;
- DNS failure -> `FailClosed(DnsProbeFailed)`;
- UDP failure -> `FailClosed(UdpProbeFailed)` only when UDP is enabled;
- listener readiness failure -> `FailClosed(ListenerNotReady)`.

External client reachability is tested only after allow rules are committed.

## Cleanup debt contract

Cleanup debt is itemized by unresolved resource, not represented as one generic Boolean.

It may contain:

- listener/backend closure pending;
- a specific service/backend handle pending stop;
- firewall deny pending;
- a specific firewall runtime pending stop;
- daemon Clean/deny reconciliation pending;
- terminal feature-stop pending;
- structured failure history.

Retry order:

```text
close listener / stop known service handle
  -> deny surviving firewall runtime
  -> stop surviving firewall runtime
  -> run daemon Clean/deny when required
  -> stop feature when terminal shutdown requires it
```

Each item is cleared only after that item succeeds. Debt is removed only when every unresolved item is cleared.

A failed `firewall_stop` with a healthy daemon must retain the firewall handle and retry `denyAll`/`stop`; an emergency listener close alone cannot clear that debt.

Unresolved debt schedules its own bounded exponential-backoff retry event. It does not wait for an unrelated VPN, client or settings emission.

No runtime start is allowed while debt remains.

## Explicit deny model

Firewall deny is explicit in the protocol model:

```proto
bool deny_all_ipv4 = ...;
bool deny_all_ipv6 = ...;
```

An empty client list is not used as an implicit synonym for deny-all. This keeps deny-first startup and future ACL behavior unambiguous.

## UDP Phase 0 gate

The pinned Hev source must be inspected to determine:

- whether client-facing relay and Internet-facing UDP sockets are the same FD or separate FDs;
- whether upstream replies arrive on a protected relay-range port;
- how conntrack classifies those replies;
- whether a narrow `ESTABLISHED`/`RELATED` rule is required before terminal reject;
- how returned `BND.ADDR/BND.PORT` maps to the configured range.

The firewall design is not final until packet captures and FD/port correlation prove the exact topology. No broad VPN-interface allow is permitted.

If one relay port is consumed per association:

```text
effective UDP capacity = min(configured association limit, usable relay-port count)
```

Range exhaustion returns a controlled failure and metric; the firewall range never expands dynamically.

## Firewall and daemon lifecycle

IPv4 allow rules match:

```text
input interface + source IPv4 + source MAC + destination TCP port/UDP relay range
```

IPv4 chains end in reject. IPv6 rejects the TCP listener port and full UDP relay range.

iptables rules can survive daemon death. If the long-lived daemon channel breaks, `ProxyService` closes the listener immediately. After daemon recovery, Clean or explicit deny reconciliation completes before any listener restart.

## Documents

- [Round-1 structural review](REVIEW.md)
- [Round-2 resolution audit](REVIEW_ROUND2.md)
- [Round-3 lifecycle review](REVIEW_ROUND3.md)
- [Research decisions](RESEARCH_DECISIONS.md)
- [Architecture](ARCHITECTURE.md)
- [Implementation plan](IMPLEMENTATION_PLAN.md)
- [Code sketches](CODE_SKETCHES.md)
- [Security and test plan](TEST_PLAN.md)
- [FlClash configuration example](FLCLASH_EXAMPLE.md)

## Non-goals for the first version

- transparent interception or TPROXY/REDIRECT;
- a new Android `VpnService`;
- embedded WARP credentials;
- replacing the selected VPN app;
- clientless transparent proxying;
- IPv6 proxy relay;
- mixed `VPN_ROUTING_AND_PROXY` mode;
- LocalOnlyHotspot, Wi-Fi Direct repeater or Bluetooth support;
- automatic physical-network fallback;
- live backend network replacement;
- automatic background resurrection after process death.

## Source references

- VPN Hotspot: https://github.com/drunkod/VPNHotspot
- HevSocks5Server: https://github.com/heiher/hev-socks5-server
- Android `Network`: https://developer.android.com/reference/android/net/Network
- Android NDK multi-network API: https://developer.android.com/ndk/reference/group/networking
- SOCKS5: https://www.rfc-editor.org/rfc/rfc1928
- Username/password authentication: https://www.rfc-editor.org/rfc/rfc1929
- FlClash: https://github.com/chen08209/FlClash
- Mihomo SOCKS5 outbound: https://wiki.metacubex.one/en/config/proxies/socks/
- Mihomo WireGuard outbound: https://wiki.metacubex.one/en/config/proxies/wg/
- Mihomo dialer proxy: https://wiki.metacubex.one/en/config/proxies/dialer-proxy/

## Approval boundary

After the round-3 corrections above, the design is approved to begin **Phase 0 feasibility work only**. UI, production firewall integration and release code remain blocked until the Phase 0 exit criteria pass.
