# Research decisions

This document converts the supplied Android proxy research, repository inspection, Graphify review and two structural review rounds into explicit decisions.

## Context

Proxy-only does not create another Android `VpnService` or TUN stack. Ordinary system tethering remains the direct path; selected laptop applications use an explicit SOCKS5 endpoint whose outbound sockets target an already active Android VPN `Network`.

The supplied Graphify snapshot reports 2,464 nodes and 6,088 edges. `SessionConfig` and `IptablesRule` are highly connected abstractions, supporting two decisions:

- keep proxy firewall lifecycle separate from `SessionConfig`;
- reuse `IptablesRule` and deterministic firewall cleanup.

## Candidate decision

| Candidate | Result |
| --- | --- |
| HevSocks5Server | selected for gated Phase 0; small fork/hook required |
| Kotlin/dataproxy-derived | primary fallback if Hev feasibility fails |
| Rust `fast-socks5`/custom backend | strong long-term fallback |
| sing-box/shadowsocks stacks | rejected for MVP scope/size/maintenance |
| TPROXY | deferred; unnecessary for FlClash process rules |

## Decision 1 — explicit SOCKS5

FlClash performs application selection. VPN Hotspot exposes an authenticated SOCKS5 endpoint. Transparent interception is not part of the MVP.

## Decision 2 — Hev is a feasibility candidate, not an assumed solution

Every outbound FD must pass a testable prepare hook and Android `android_setsocknetwork()` before connect/send. Host CI uses an injected shim. If complete socket/resolver coverage requires an unmaintainable fork, Hev is rejected.

## Decision 3 — `ProxyService` is the sole data-plane owner

The data plane runs in the app process, but ownership is precise:

```text
ProxyOnlyController -> ProxyServiceClient -> ProxyService -> ProxyBackend
```

`ProxyService` owns native handles, listener, sessions and backend statistics. The controller owns desired-state reconciliation and never invokes `ProxyBackend` directly.

Reasons:

- native lifecycle remains inside the FGS boundary;
- emergency listener closure remains possible when controller operations fail;
- credentials/config remain app-private;
- payload forwarding does not run as root;
- backends remain replaceable.

## Decision 4 — root daemon owns policy using existing ledger

The daemon owns deny-first exposure, iface+IPv4+MAC ACL, IPv6 deny, counters and Clean.

Implementation reuses:

- `routing/iptables.rs::IptablesRule`/`IptablesChain`;
- idempotent insert/delete and `delete_repeated()`;
- `routing/firewall_cleanup.rs::clean()`;
- IPv4/IPv6 target abstraction.

The lifecycle is a separate long-lived command, not `SessionConfig` growth.

## Decision 5 — VPN-only selection and ambiguity handling

`Upstreams.primary` may be physical. Proxy-only requires fresh `TRANSPORT_VPN` validation and app-UID usability.

Selection policy:

- zero usable VPNs: waiting/fail-closed;
- one usable VPN: selected;
- multiple usable VPNs: `MultipleVpnCandidates` fail-closed.

Transient network-handle sorting is not user-intent policy. A future explicit selector requires stable user-facing identity.

## Decision 6 — app-policy permission is readiness

If the VPN excludes VPN Hotspot, binding failure is a distinct user-visible configuration error. It never triggers physical fallback.

## Decision 7 — service stays alive through recovery

After an Android-permitted user foreground start, `ProxyService` remains alive while Proxy-only is enabled, including waiting and fail-closed states. The listener is closed in those states.

This avoids an Android 12+ background FGS start when VPN or daemon connectivity returns. Automatic resurrection after process death is not assumed.

## Decision 8 — exception-safe serialized reconciliation

A conflated channel feeds one worker. Non-cancellable transactions solve cancellation only; the worker also needs:

- per-iteration exception boundary;
- fail-closed recovery after fast-path or rollback failures;
- safe publication;
- cleanup that attempts every step;
- aggregated cleanup errors;
- terminal `stopApplied()` in worker `finally`.

Silent `runCatching` cleanup is rejected.

## Decision 9 — full backend restart on VPN generation change

No live `replaceNetwork()` in MVP:

```text
deny -> close listener/sessions -> stop backend -> validate new VPN -> start/probe -> allow
```

## Decision 10 — probes are outbound-only before allow commit

Deny-first startup makes a downstream listener probe contradictory. Pre-allow checks are:

- app-UID bind;
- backend outbound TCP;
- backend outbound UDP;
- VPN-aware DNS;
- internal listener bind/listen readiness.

External client reachability is tested after allow rules commit.

## Decision 11 — VPN-aware bounded DNS

Domain targets use the selected VPN only. Phase 0 chooses bounded resolver workers or an asynchronous Android resolver. Process-default DNS and unbounded blocking inside Hev workers are rejected.

## Decision 12 — standard UDP with explicit topology gate

MVP uses RFC 1928 `UDP ASSOCIATE` with source validation, control-TCP lifetime, idle timeout, bounded state and `FRAG != 0` rejection.

Before firewall rules are final, Phase 0 must determine:

- client-facing versus Internet-facing FDs;
- same-FD versus separate-FD behavior;
- returned relay port mapping;
- reply ingress interface;
- conntrack state;
- whether a narrow `ESTABLISHED/RELATED` rule is required.

No broad VPN-interface allow is permitted.

## Decision 13 — explicit UDP range and capacity

The relay range is fixed/configured and never widened dynamically.

If one port is consumed per association:

```text
effective capacity = min(configured association limit, usable relay ports)
```

Range exhaustion returns a controlled failure and metric.

## Decision 14 — IPv4-only MVP

Listener/relay are IPv4-only. ip6tables denies TCP listener port and full UDP range. Full IPv6 proxying is deferred.

## Decision 15 — MAC is enforcement identity

Allow requires input interface + source IPv4 + source MAC. IP-only fallback is rejected. Downstreams without reliable MAC identity are unsupported.

## Decision 16 — daemon loss closes listener, not service

iptables allow rules may survive daemon death. Unexpected daemon-channel completion causes `ProxyService` emergency listener closure. The service remains foreground fail-closed. Daemon recovery performs Clean/deny before backend restart.

## Decision 17 — global UI mode, per-downstream internal state

MVP UI exposes `VPN_ROUTING` and `PROXY_ONLY`. Internal flags remain per downstream. Mixed mode is deferred.

## Decision 18 — FlClash owns process selection

VPN Hotspot cannot identify a macOS process after traffic reaches the phone. FlClash owns `PROCESS-NAME`/regex/path rules and `MATCH,DIRECT`. Optional WARP uses `dialer-proxy: PhoneVPN`.

## Decision 19 — preserve backend replacement

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyBackendHandle): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

The backend interface is owned by `ProxyService`. Hev is first; Kotlin/Rust remain possible.

## Phase 0 gates

1. Exact Hev pin and socket/resolver audit.
2. Per-app VPN include/exclude matrix.
3. Multiple-VPN selection behavior.
4. UDP FD/port/interface/conntrack topology.
5. Relay range and effective capacity.
6. Bounded VPN-aware DNS.
7. Outbound-only probe implementation.
8. IPv6 denial.
9. Host bind-shim CI.
10. Exception-safe worker and service ownership contracts in fake implementations.
11. FGS type, user start context and background recovery policy.

Passing these gates approves later implementation phases; failing a security gate stops the Hev path.
