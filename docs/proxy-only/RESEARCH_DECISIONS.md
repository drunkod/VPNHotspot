# Research decisions

This document converts the supplied Android proxy research, repository inspection and code-graph review into explicit engineering decisions for VPN Hotspot.

## Context correction

Most Android proxy/VPN designs assume the application owns a `VpnService`, reads a TUN device and forwards traffic through tun2socks or a userspace stack.

VPN Hotspot is different:

- Proxy-only does not create another TUN interface;
- it observes an already active Android VPN network;
- ordinary system tethering remains the direct path;
- the SOCKS endpoint is explicit and used only by selected laptop applications;
- outbound sockets must target the existing VPN `Network`, not bypass it with `VpnService.protect()`.

The routing-loop analysis is still useful: no proxy socket may select an unintended route. Here the required route is a validated Android VPN network.

## Code-graph evidence

The supplied Graphify snapshot reports 2,464 nodes and 6,088 edges for VPNHotspot. Two highly connected abstractions are notable:

- `SessionConfig`: 90 edges;
- `IptablesRule`: 44 edges.

These metrics do not replace source review, but they support two design choices:

1. keep proxy firewall lifecycle separate from `SessionConfig` rather than increasing its responsibility;
2. reuse `IptablesRule` and existing cleanup machinery instead of creating a second firewall representation.

The graph also confirms that `Routing.kt` consumes `Upstreams.primary` and forwards its `networkHandle` to the daemon. This is acceptable for configurable routing mode, but Proxy-only makes a stronger VPN-only claim and must validate capabilities separately.

## Candidate comparison

| Candidate | TCP | UDP ASSOCIATE | Android network binding | Prototype fit | Main concern |
| --- | ---: | ---: | --- | --- | --- |
| HevSocks5Server | Yes | Yes | Small fork/hook required | Selected for Phase 0 | audit every socket and resolver path |
| Kotlin/dataproxy-derived | Yes | Research indicates yes | natural `Network.bindSocket()` APIs | primary fallback | allocations/GC and third-party audit |
| Rust `fast-socks5` | Yes | verify pinned release | custom socket factory can call NDK API | strong long-term fallback | larger integration effort |
| larger sing-box/shadowsocks stacks | Yes | Yes | possible | rejected for MVP | excessive scope/size/licensing complexity |
| TPROXY | N/A | N/A | kernel dependent | deferred | unnecessary for FlClash process rules |

## Decision 1 — explicit SOCKS5, not transparent interception

Selected:

```text
FlClash process rule -> explicit authenticated SOCKS5 endpoint
```

Rejected for MVP:

```text
iptables TPROXY/REDIRECT -> transparent proxy
```

Reasons: FlClash already performs application selection, UDP semantics remain explicit, device-specific TPROXY support is unnecessary, and failure testing is simpler.

## Decision 2 — Hev only as a gated prototype

Hev is selected for Phase 0 because it provides `CONNECT`, `UDP ASSOCIATE`, authentication, Android NDK build support and MIT licensing.

The stock `bind-interface` and static mark options do not prove Android VPN network binding. The fork invariant is:

```text
Every outbound FD
  -> testable prepare hook
  -> android_setsocknetwork(validatedVpnHandle, fd)
  -> only then connect/send
```

The fork must have a Linux host shim so hook coverage is enforced in CI. If DNS or socket coverage cannot be made complete with a small patch, reject Hev and use the backend abstraction to switch implementation.

## Decision 3 — data plane in app process

The SOCKS data plane runs inside the VPN Hotspot app process as a native backend.

Reasons:

- lifecycle and user-visible status remain in Android code;
- credentials/config stay app-private;
- payload forwarding does not run as UID 0;
- per-app VPN policy errors are attributable to the app UID;
- a Kotlin replacement remains possible without changing UI/root policy.

## Decision 4 — root daemon owns policy and reuses existing firewall ledger

The daemon owns:

- downstream exposure;
- deny-first policy;
- interface + IPv4 + MAC ACLs;
- IPv6 deny rules for the MVP;
- per-client kernel counters;
- deterministic cleanup.

Implementation must reuse:

- `routing/iptables.rs::IptablesRule` and `IptablesChain`;
- idempotent insertion and `delete_repeated()` cleanup;
- `routing/firewall_cleanup.rs::clean()` for proxy jumps/chains;
- existing IPv4/IPv6 target abstraction.

The proxy firewall has an independent long-lived command/runtime and does not extend `SessionConfig`.

## Decision 5 — VPN-only selection, not `Upstreams.primary` trust

`Upstreams.primary` is configurable and may emit a physical interface. Proxy-only therefore uses `Upstreams.vpn` or performs fresh `TRANSPORT_VPN` validation before startup.

When no validated VPN exists:

```text
reject and close proxy traffic
```

The controller never uses `Upstreams.fallback`, process-default routing or a physical primary override.

Existing routing mode remains unchanged and may keep using the user-selected primary upstream.

## Decision 6 — app-UID VPN permission is part of readiness

Binding can fail when the user's VPN app excludes VPN Hotspot from its allowed-app policy.

`Running` requires a successful app-owned probe. Exclusion produces a distinct fail-closed state with user guidance. It is not treated as a transient network timeout and never triggers direct fallback.

## Decision 7 — network-aware, non-blocking DNS boundary

Domain targets resolve through the validated VPN network only.

A synchronous network-aware resolver may block Hev workers. Phase 0 must test blackholed DNS and select either:

- bounded dedicated resolver workers with timeout/cancellation; or
- an asynchronous Android network-aware resolver path.

Ordinary `getaddrinfo()`/process-default Java DNS is rejected.

## Decision 8 — standard UDP with explicit relay range

The MVP supports RFC 1928 `UDP ASSOCIATE`.

Requirements:

- control TCP owns association lifetime;
- source peer validation;
- bounded associations and idle timeout;
- `FRAG != 0` dropped;
- every Internet-facing UDP FD passes the VPN hook;
- Hev relay ports constrained to a configured range;
- proto/firewall carry range start/end unless Phase 0 proves one shared fixed port.

Hev's non-standard `FWD UDP` extension is not required.

## Decision 9 — IPv4-only proxy for MVP, explicit IPv6 denial

Hev may support IPv6, but a partially implemented dual-stack firewall is unsafe.

MVP policy:

- listener and relay are IPv4-only;
- no IPv6 endpoints are published;
- ip6tables rejects the TCP port and complete UDP range;
- full IPv6 relay is deferred until mirrored ACL/DNS/counter tests exist.

## Decision 10 — MAC is part of enforcement, not only reporting

The public client identity is MAC. Allow rules match:

```text
input interface + source IPv4 + source MAC
```

IP-only fallback is rejected because DHCP/static-IP reuse can inherit another client's rule. Downstreams without reliable MAC identity are unsupported in the MVP.

## Decision 11 — global mode in UI, per-downstream model internally

MVP UI exposes:

```kotlin
VPN_ROUTING
PROXY_ONLY
```

Internally each downstream has separate routing/proxy flags so later per-interface controls do not require data migration.

`VPN_ROUTING_AND_PROXY` is deferred rather than shipping untested rule interaction.

## Decision 12 — restart backend on VPN generation change

The MVP has no live `replaceNetwork()` operation. A network identity/handle change causes:

```text
deny -> close sessions/listener -> stop backend -> validate new VPN -> start/probe -> allow
```

This matches the state machine and guarantees no old-generation association survives.

## Decision 13 — non-cancellable serialized reconciliation

Desired-state flows feed a conflated channel. One worker completes each apply/rollback transaction in non-cancellable context before processing the newest snapshot.

`collectLatest` is rejected for resource transactions because cancellation can leak firewall runtime or remove policy before listener shutdown.

The runtime key includes ports/range, credentials version, VPN handle, sorted downstream set and backend version. Client updates perform replace only.

## Decision 14 — daemon loss closes listener

iptables allow rules survive daemon death. Therefore daemon loss is not equivalent to deny state.

The long-lived firewall command/channel is a liveness signal. Unexpected completion immediately closes the app-process backend. On daemon recovery, Clean or explicit deny state completes before listener restart.

## Decision 15 — initial downstream scope

First supported:

- system Wi-Fi tethering;
- USB tethering when MAC identity is reliable;
- Ethernet only after equivalent testing.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth;
- interfaces without reliable MAC+IPv4 identity.

## Decision 16 — FlClash owns application selection

VPN Hotspot cannot identify the originating macOS process. FlClash owns `PROCESS-NAME`, regex/path rules and `MATCH,DIRECT`.

VPN Hotspot exposes `PhoneVPN`; optional WARP uses `dialer-proxy: PhoneVPN`.

## Decision 17 — preserve backend replacement options

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun probe(handle: ProxyBackendHandle)
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle)
}
```

The first implementation is `HevProxyBackend`. Kotlin or Rust can replace it without redesigning settings, controller or root policy.

## Remaining gates before implementation

1. Exact Hev pin and complete socket-path audit.
2. UDP relay-range behaviour on that pin.
3. Resolver implementation after blackhole measurements.
4. App-UID allowed/excluded VPN matrix on target Android versions.
5. Exact foreground-service type and distribution-policy eligibility.
6. Credential storage/direct-boot integration.
7. Counter integration without double counting.
8. Reliable MAC identity on each initial downstream type.