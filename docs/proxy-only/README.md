# Proxy-only mode design

Status: round-2 corrected design, approved to begin Phase 0 only  
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
5. any critical dependency failure closes the listener and sessions without direct fallback.

`Upstreams.primary` is not proof of VPN use because the existing routing mode permits a user-selected physical upstream. Proxy-only uses a VPN-specific candidate and fresh capability validation.

If multiple usable VPN candidates are visible, the MVP fails closed with `MultipleVpnCandidates`. It does not choose by transient network-handle ordering.

## Accepted MVP decisions

1. The UI exposes `VPN_ROUTING` and `PROXY_ONLY`; mixed routing+proxy mode is deferred.
2. Ordinary system Wi-Fi/USB tethering supplies the direct path.
3. `ProxyService` is the sole owner of `ProxyBackend`, native handles and backend statistics.
4. `ProxyOnlyController` owns desired-state reconciliation and talks to `ProxyServiceClient`; it never invokes the backend directly.
5. The first backend is a pinned, minimally forked HevSocks5Server.
6. Every outbound FD passes a testable Android-network binding hook.
7. SOCKS domain targets use bounded/asynchronous VPN-aware DNS.
8. Root daemon owns firewall, client ACL, counters and deterministic cleanup.
9. Proxy firewall reuses the existing `IptablesRule` ledger and `firewall_cleanup` machinery.
10. Fail-closed is mandatory; physical fallback is not an MVP option.
11. Standard SOCKS5 `CONNECT` and `UDP ASSOCIATE` are required.
12. MVP is IPv4-only with explicit IPv6 deny rules.
13. Client allow identity is downstream interface + IPv4 + MAC.
14. VPN generation changes fully restart the backend.
15. TPROXY/transparent interception is deferred.

## Foreground-service lifecycle

The service is started only from a user action that is valid under current Android foreground-service rules. Once Proxy-only is enabled and the service is running, it remains alive through:

- `WaitingForTethering`;
- `WaitingForVpn`;
- VPN loss / `FailClosed`;
- root-daemon loss / `FailClosed`;
- background recovery when VPN or daemon service returns.

In waiting/fail-closed states the service has **no active listener** and shows a persistent blocked/waiting notification. Keeping the service alive avoids an Android 12+ background restart that may throw `ForegroundServiceStartNotAllowedException`.

The service stops when the user disables Proxy-only, recovery is explicitly abandoned, or the OS terminates the process. Automatic resurrection after process death is not assumed.

## Exception-safe reconciliation

One `Channel.CONFLATED` feeds one worker. Resource transactions are non-cancellable, but that alone is insufficient. The worker also requires:

- a catch boundary around every reconciliation iteration;
- fail-closed recovery after fast-path, publish or rollback exceptions;
- cleanup that attempts deny, backend/listener stop and firewall stop independently;
- aggregated, structured cleanup error reporting instead of silent `runCatching`;
- a `finally` block that calls terminal `stopApplied()` in `NonCancellable` context.

No exception may kill the worker while leaving a live listener unreconciled.

## Probe contract

Deny-first startup intentionally blocks client ingress. Therefore startup probes are outbound-only:

- app-UID bind probe;
- backend-created outbound TCP probe;
- backend-created outbound UDP probe;
- VPN-aware DNS probe;
- internal listener bind/listen readiness signal.

A downstream client reachability probe runs only after per-client allow rules are committed.

## UDP Phase 0 gate

The pinned Hev source must be inspected to determine:

- whether client-facing relay and Internet-facing UDP sockets are the same FD or separate FDs;
- whether upstream replies arrive on a protected relay-range port;
- how conntrack classifies those replies;
- whether `ESTABLISHED/RELATED` is required before terminal reject;
- how returned `BND.ADDR/BND.PORT` maps to the configured range.

The firewall design is not final until packet captures and FD/port correlation prove the exact topology. No broad VPN-interface allow is permitted.

If one relay port is consumed per association, the range is also a global capacity ceiling:

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

This design is approved to begin **Phase 0 feasibility work only**. UI, production firewall integration and release code remain blocked until the Phase 0 exit criteria pass.
