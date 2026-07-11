# Research decisions

This document converts the supplied research notes into explicit engineering decisions for VPN Hotspot.

## Context correction

A large part of general Android proxy/VPN literature assumes the application itself owns a `VpnService`, reads packets from a TUN interface and sends them into a SOCKS server through tun2socks or a userspace TCP/IP stack.

VPN Hotspot is different:

- it does not need to create a second TUN interface for Proxy-only mode;
- it observes an already active Android VPN network;
- ordinary tethering can remain the direct path;
- the new proxy is an explicit endpoint used only by selected client applications;
- socket binding must target the existing VPN `Network`, not bypass it with `VpnService.protect()`.

The routing-loop discussion from TUN-based designs is still useful as a warning: an outbound proxy socket must never be allowed to select an unintended route. For this project, the desired route is the active VPN network, so `android_setsocknetwork()`/`Network.bindSocket()` is the correct primitive.

## Candidate comparison

| Candidate | Language | TCP CONNECT | UDP ASSOCIATE | Android network binding integration | Prototype fit | Main concern |
| --- | --- | ---: | ---: | --- | --- | --- |
| HevSocks5Server | C | Yes | Yes | Requires a small fork/hook | Selected by project plan | Must audit every socket path |
| Kotlin custom/dataproxy-derived | Kotlin | Yes | Research indicates yes | Natural `Network.bindSocket()` APIs | Best fallback for fastest Android-native development | GC/allocation overhead and third-party code audit |
| fast-socks5 | Rust | Yes | Supported by library direction; verify pinned release | Custom socket factory can call NDK binding | Strong long-term alternative | More integration work and possible second native runtime boundary |
| socks5-rs/other CLI servers | Rust | Usually | Varies | Often require invasive refactoring | Lower priority | Monolithic connection creation |
| sing-box/shadowsocks stacks | Go/Rust | Yes | Yes | Possible | Rejected for MVP | Excessive scope, size and licensing/maintenance complexity |
| TPROXY transparent proxy | Kernel + userspace | N/A | N/A | Kernel/firmware dependent | Explicitly deferred | Complex policy routing and not needed for per-process FlClash rules |

## Decision 1 — explicit SOCKS5, not transparent proxying

Selected:

```text
FlClash process rule -> explicit SOCKS5 endpoint
```

Rejected for MVP:

```text
iptables TPROXY/REDIRECT -> transparent proxy
```

Reasons:

- FlClash already performs per-application selection;
- SOCKS5 is portable across system Wi-Fi and USB tethering;
- UDP behaviour is explicit through `UDP ASSOCIATE`;
- no dependency on device-specific TPROXY kernel support;
- easier fail-closed testing;
- lower risk of disturbing existing routing rules.

## Decision 2 — HevSocks5Server for the prototype

HevSocks5Server is selected because it already provides the required protocol surface and Android NDK build path:

- `CONNECT`;
- `UDP ASSOCIATE`;
- IPv4/IPv6;
- username/password authentication;
- configurable listener and timeouts;
- embeddable C API;
- MIT license.

This is a prototype decision, not permission to vendor an unmodified library. The stock options `bind-interface` and `mark` do not prove binding to an Android VPN `Network` handle.

Required fork invariant:

```text
Every outbound FD -> android_setsocknetwork(activeVpnHandle, fd) -> then connect/send
```

## Decision 3 — data plane in app process

The plan requires the SOCKS5 data plane to run inside the VPN Hotspot application process, loaded as a native shared library.

Reasons:

- matches the requested product behaviour;
- keeps service lifecycle and user-visible status in Android application code;
- avoids exposing the proxy as an independent root process;
- makes credentials and generated configuration app-private;
- permits a later Kotlin implementation without changing the high-level controller contract.

The root daemon remains responsible for privileged policy, not payload forwarding.

## Decision 4 — root daemon owns firewall, ACL and accounting policy

The daemon is the correct owner for:

- downstream-interface allow rules;
- deny-by-default exposure;
- blocked-client enforcement;
- per-client kernel counters;
- deterministic cleanup;
- reconciliation after interface/client changes.

The app/native proxy should additionally report protocol-level counters because firewall counters include SOCKS framing and cannot always attribute remote payload precisely.

## Decision 5 — exact VPN network binding, not fallback

The proxy controller uses `Upstreams.primary.network` and its `networkHandle`.

When no VPN exists:

```text
fail_closed=true -> reject/close proxy traffic
```

It must not silently use `Upstreams.fallback`, the process default network or the physical carrier route.

A direct fallback may be considered later only as a clearly labelled opt-in setting, not as the default.

## Decision 6 — network-aware DNS

SOCKS5 domain targets must be resolved on the selected VPN network.

Acceptable:

- Android `Network.getAllByName()`;
- Android NDK network-aware resolver APIs.

Rejected:

- ordinary `getaddrinfo()` without network context;
- Java process-default DNS;
- resolving through the physical fallback while reporting the proxy as fail-closed.

## Decision 7 — standard UDP semantics

The MVP supports standard RFC 1928 `UDP ASSOCIATE`.

Requirements:

- association lifetime tied to control TCP connection;
- client source validation;
- bounded state and idle timeout;
- `FRAG != 0` dropped initially;
- every Internet-facing UDP socket pinned to the VPN network;
- tests with DNS, a UDP echo server and WARP WireGuard through FlClash.

Hev's non-standard `FWD UDP` extension is not required for FlClash interoperability and should not replace standard UDP ASSOCIATE.

## Decision 8 — initial downstream scope

First supported:

- Android system Wi-Fi tethering;
- USB tethering;
- Ethernet only after verification.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth tethering.

Reason: Proxy-only relies on Android's ordinary direct tethering route for non-proxied traffic. Local-only and app-created repeater modes need additional direct-path design.

## Decision 9 — FlClash owns per-application selection

VPN Hotspot cannot know which macOS process generated traffic after it reaches the phone.

Therefore:

- VPN Hotspot exposes `PhoneVPN` SOCKS5;
- FlClash uses `PROCESS-NAME`, regex and path rules;
- `MATCH,DIRECT` remains the fast path;
- optional WARP uses a Mihomo WireGuard outbound with `dialer-proxy: PhoneVPN`.

## Decision 10 — preserve future replacement options

The Kotlin controller, service and root firewall interfaces must not depend on Hev-specific data structures.

Use a backend abstraction:

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceNetwork(handle: ProxyBackendHandle, upstream: ProxyUpstream)
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle)
}
```

The first implementation is `HevProxyBackend`. A later `KotlinProxyBackend` or `RustProxyBackend` should be possible without redesigning UI, settings or root policy.

## Open questions before code implementation

1. Does the pinned Hev source centralize all outbound FD creation sufficiently for a small hook?
2. Is native network-aware DNS easier to maintain than a JNI resolver callback?
3. Can one native server instance safely update its network handle, or should every VPN generation trigger a full restart?
4. Which current firewall backend and chain conventions should proxy rules extend?
5. How should proxy counters integrate with `TrafficRecorder` without double counting?
6. Which current UI surface should own sharing mode selection?
7. Which foreground-service type is valid under the repository's current target SDK?
8. How should credential storage integrate with existing direct-boot behaviour?

These questions are intentionally part of Phase 0/architecture review, not reasons to weaken fail-closed behaviour.
