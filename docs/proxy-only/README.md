# Proxy-only mode design

Status: design proposal for implementation review  
Target repository snapshot: `drunkod/VPNHotspot@9b6354c69cbe42c87c8d3a28add8405c3ae79b8d`  
Target branch: `agent/proxy-only-design`

## Goal

Add an optional **Proxy only** mode to VPN Hotspot. In this mode Android system tethering remains the normal, direct Internet path, while VPN Hotspot exposes an authenticated SOCKS5 endpoint whose outbound sockets are pinned to the selected Android VPN `Network`.

This enables a laptop to decide which applications use the phone VPN:

```text
Unselected laptop applications
  -> FlClash DIRECT
  -> ordinary Android tethering
  -> physical Wi-Fi/cellular network

Selected laptop applications
  -> FlClash PROCESS-NAME rule
  -> SOCKS5 server inside VPN Hotspot
  -> outbound socket pinned to Android VPN Network
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

- normal carrier/physical-network IP for `DIRECT` traffic;
- phone VPN exit IP for `PhoneVPN` traffic;
- Cloudflare WARP IP for `WARP-via-PhoneVPN` traffic.

## Accepted product requirements

1. Add a `Proxy only` operating mode.
2. Use ordinary Android system tethering as the direct path.
3. Run the SOCKS5 data plane inside the VPN Hotspot application process.
4. Use HevSocks5Server for the first prototype.
5. Bind every outbound TCP and UDP socket to the active Android VPN network handle.
6. Keep firewall, client ACL and accounting responsibilities in the root daemon.
7. Default to fail-closed when the VPN disappears.
8. Support SOCKS5 `CONNECT` and `UDP ASSOCIATE`.
9. Select laptop applications in FlClash with `PROCESS-NAME` rules.
10. Do not implement transparent proxying or TPROXY in the first version.

## Important distinction from the existing mode

The current routing mode creates a `Routing` session for each managed downstream interface and forwards tethered traffic through the VPN upstream. `Upstreams.primary` defaults to a network with `TRANSPORT_VPN`, and `Routing` sends the selected network handle and interface list to the root daemon.

`Proxy only` must **not** create the existing full-forwarding session for the downstream interface. If it does, `MATCH,DIRECT` on the laptop is still carried through the phone VPN and the feature no longer provides a fast direct path.

The new mode therefore separates two concerns:

- Android owns ordinary tethering and direct forwarding;
- VPN Hotspot owns only the SOCKS5 listener and the firewall rules that expose it safely to tethered clients.

## Why HevSocks5Server needs an integration fork

HevSocks5Server already provides:

- IPv4 and IPv6 support;
- SOCKS5 `CONNECT`;
- SOCKS5 `UDP ASSOCIATE`;
- username/password authentication;
- Android NDK build support;
- source-address, interface and socket-mark options;
- an embeddable start/stop C API;
- MIT licensing.

However, `bind-interface` and a static socket mark are not equivalent to binding a socket to an Android `Network`. The prototype must add a small, reviewable hook that runs after `socket()` and before `connect()` or the first UDP send:

```c
int android_setsocknetwork(uint64_t network_handle, int socket_fd);
```

No outbound socket may be used unless that call succeeds when fail-closed is enabled.

## Documents

- [Research decisions](RESEARCH_DECISIONS.md)
- [Architecture](ARCHITECTURE.md)
- [Implementation plan](IMPLEMENTATION_PLAN.md)
- [Code sketches](CODE_SKETCHES.md)
- [Security and test plan](TEST_PLAN.md)
- [FlClash configuration example](FLCLASH_EXAMPLE.md)

## Non-goals for the first version

- transparent interception;
- TPROXY/REDIRECT of arbitrary client traffic;
- a new Android `VpnService`;
- embedding WARP credentials in the Android application;
- replacing the user-selected VPN application;
- proxying tethered traffic automatically without client configuration;
- supporting LocalOnlyHotspot and Wi-Fi Direct repeater before system Wi-Fi/USB tethering is stable;
- automatic fallback to the physical network after VPN loss.

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

## Review decision requested

Before implementation begins, reviewers should approve these decisions:

- the app-process SOCKS5 data plane is preferred over running it as UID 0;
- a maintained HevSocks5Server fork is acceptable for the prototype;
- system Wi-Fi and USB tethering are the initial supported downstreams;
- fail-closed is mandatory by default;
- accurate per-client proxy accounting may require a Hev callback in addition to firewall counters.
