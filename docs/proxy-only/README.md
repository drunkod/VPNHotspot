# Proxy-only mode design

Status: revised design proposal after structural and code-graph review  
Target repository snapshot: `drunkod/VPNHotspot@9b6354c69cbe42c87c8d3a28add8405c3ae79b8d`  
Target branch: `agent/proxy-only-design`

## Goal

Add an optional **Proxy only** mode to VPN Hotspot. Android system tethering remains the normal direct Internet path, while VPN Hotspot exposes an authenticated SOCKS5 endpoint. Only traffic explicitly sent to that endpoint is carried through the phone VPN.

```text
Unselected laptop applications
  -> FlClash DIRECT
  -> ordinary Android tethering
  -> physical Wi-Fi/cellular network

Selected laptop applications
  -> FlClash PROCESS-NAME rule
  -> SOCKS5 server inside VPN Hotspot
  -> outbound socket bound to a validated Android VPN Network
  -> VPN provider
  -> Internet

Selected laptop applications with WARP
  -> FlClash WireGuard/WARP outbound
  -> dialer-proxy: PhoneVPN
  -> SOCKS5 server inside VPN Hotspot
  -> phone VPN
  -> Cloudflare WARP
  -> Internet
```

The final public IP is therefore:

- physical/carrier IP for `DIRECT` traffic;
- phone VPN exit IP for `PhoneVPN` traffic;
- Cloudflare WARP IP for `WARP-via-PhoneVPN` traffic.

## Security contract

Proxy-only makes a stronger promise than the existing routing mode:

> When the proxy reports `Running`, every Internet-facing TCP, UDP and DNS operation is using a network whose capabilities include `TRANSPORT_VPN` and whose use is permitted for the VPN Hotspot app UID.

The current routing path intentionally allows the user to override `Upstreams.primary` with an interface regex. `Routing.kt` consumes that selected upstream and forwards its `networkHandle` to the daemon without requiring it to be a VPN. That is valid for routing mode because the user explicitly selected the upstream.

Proxy-only must not inherit that permissive behaviour. It must either consume `Upstreams.vpn` directly or validate `NetworkCapabilities.TRANSPORT_VPN` immediately before startup. A physical `wlan0`/cellular network is not an acceptable proxy upstream, even if it is configured as `service.upstream`.

## Accepted MVP requirements

1. Add a global `Proxy only` operating mode while keeping a per-downstream internal model.
2. Use ordinary Android system tethering as the direct path.
3. Run the SOCKS5 data plane inside the VPN Hotspot application process.
4. Use a pinned, minimally forked HevSocks5Server for the feasibility prototype.
5. Bind every outbound TCP and UDP socket to a validated Android VPN network handle.
6. Resolve SOCKS domain targets through the same VPN without blocking Hev workers indefinitely.
7. Keep firewall, client ACL, counters and deterministic cleanup in the root daemon.
8. Reuse the daemon's existing `IptablesRule` mutation ledger and `firewall_cleanup` conventions.
9. Default to fail-closed; no automatic physical-network fallback is included in the MVP.
10. Support SOCKS5 `CONNECT` and standard `UDP ASSOCIATE`.
11. Select laptop applications in FlClash with `PROCESS-NAME` rules.
12. Do not implement transparent proxying or TPROXY in the first version.
13. Bind the MVP listener to IPv4 only and install explicit IPv6 deny rules for all proxy ports.
14. Treat root-daemon loss as a stop event: close the native listener immediately.
15. Restart the native backend on every VPN network-generation change; live network replacement is deferred.

## Operating modes

MVP UI exposes two modes:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

The implementation keeps independent per-downstream flags underneath so a later release can add mixed modes without migrating stored state:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

`VPN_ROUTING_AND_PROXY` is deliberately deferred until rule-ordering and interaction tests exist. It is not required for the selective-app use case.

## Important distinction from existing routing

The current routing mode creates a `Routing` session for a managed downstream and forwards tethered traffic through the selected primary upstream. `Proxy only` must **not** create that forwarding session. Otherwise `MATCH,DIRECT` on the laptop still travels through the phone VPN and the fast direct path is lost.

The new mode separates responsibilities:

- Android owns system tethering, DHCP, NAT and direct forwarding;
- the app process owns the authenticated SOCKS5 listener and relay lifecycle;
- the root daemon owns safe listener exposure, ACLs, counters and cleanup.

## Hev integration boundary

HevSocks5Server already provides `CONNECT`, `UDP ASSOCIATE`, authentication, Android NDK support and an embeddable API. Its `bind-interface` and static mark settings are not equivalent to binding each socket to an Android `Network`.

The fork must provide a testable hook after `socket()` and before `connect()` or first UDP send:

```c
int vpnhotspot_prepare_outbound_socket(int fd, const network_state_t *state);
```

The Android implementation calls `android_setsocknetwork()`. A host-CI shim records hook calls and simulates failures, allowing every retry/fallback socket path to be tested on Linux. In fail-closed mode, no outbound FD may continue after hook failure.

Phase 0 must also determine Hev's UDP relay-port behaviour. The firewall contract uses a configured UDP relay range rather than assuming the SOCKS TCP port is also the UDP port.

## Firewall identity and lifecycle

Allow rules match all available identity dimensions:

```text
downstream interface + source IPv4 + source MAC + destination proxy port/range
```

IP-only access is considered spoofable and is not an MVP fallback. IPv6 access is denied explicitly because the first listener is IPv4-only.

The new proxy firewall remains separate from `SessionConfig`. This avoids growing an already central routing abstraction and lets proxy policy have its own long-lived command lifecycle. Its implementation must reuse `routing/iptables.rs::IptablesRule`, idempotent insertion/deletion and `routing/firewall_cleanup.rs::clean()` rather than introduce a parallel rule representation.

Kernel rules survive daemon death. Therefore daemon loss does **not** imply that access automatically becomes denied. The app must monitor the long-lived firewall call/channel and close the native listener immediately if it breaks. On daemon recovery, Clean/deny reconciliation must complete before the listener can reopen.

## Documents

- [Structural review and graph grounding](REVIEW.md)
- [Research decisions](RESEARCH_DECISIONS.md)
- [Architecture](ARCHITECTURE.md)
- [Implementation plan](IMPLEMENTATION_PLAN.md)
- [Code sketches](CODE_SKETCHES.md)
- [Security and test plan](TEST_PLAN.md)
- [FlClash configuration example](FLCLASH_EXAMPLE.md)

## Non-goals for the first version

- transparent interception or TPROXY/REDIRECT;
- a new Android `VpnService`;
- embedding WARP credentials in the Android application;
- replacing the user-selected VPN application;
- proxying traffic without client-side configuration;
- IPv6 proxy relay;
- `VPN_ROUTING_AND_PROXY`;
- LocalOnlyHotspot and Wi-Fi Direct repeater support;
- automatic fallback to the physical network;
- hot-swapping a live native instance to a new Android `Network`.

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

## Decisions required before implementation

Reviewers should approve:

- VPN-only capability validation independent of `Upstreams.primary` overrides;
- the app-process data plane and small maintained Hev fork;
- IPv4-only listener plus IPv6 deny policy for MVP;
- fixed/configured UDP relay range;
- iface + IP + MAC ACL matching;
- independent proxy-firewall command lifecycle reusing `IptablesRule` machinery;
- restart-on-network-generation-change;
- fail-closed daemon-loss handling;
- the selected Android foreground-service type and distribution-policy eligibility;
- the Phase 0 per-app VPN allow/exclude compatibility matrix.