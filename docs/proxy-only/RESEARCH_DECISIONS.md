# Research decisions

This document converts the Android proxy research, repository inspection, Graphify review and three structural review rounds into explicit engineering decisions.

## Context

VPN Hotspot differs from a conventional Android VPN client:

- Proxy-only does not create another TUN interface;
- it observes an already active Android VPN network;
- Android system tethering remains the direct path;
- an explicit SOCKS endpoint is used only by selected laptop applications;
- outbound sockets target the existing VPN `Network`.

## Code-graph grounding

The supplied Graphify snapshot reported 2,464 nodes and 6,088 edges. Two highly connected abstractions were notable:

- `SessionConfig`: 90 edges;
- `IptablesRule`: 44 edges.

This supports:

1. keeping proxy-firewall lifecycle separate from `SessionConfig`;
2. reusing `IptablesRule` and existing cleanup machinery.

The graph also confirmed that existing routing code consumes configurable `Upstreams.primary`. Proxy-only therefore requires a separate VPN-only security boundary.

## Decision 1 — explicit SOCKS5, not transparent interception

Selected:

```text
FlClash PROCESS-NAME rule -> authenticated SOCKS5 endpoint
```

Deferred:

```text
TPROXY/REDIRECT -> transparent proxy
```

FlClash already owns application selection, explicit SOCKS5 keeps UDP semantics observable, and the MVP avoids kernel-dependent interception.

## Decision 2 — Hev is a gated prototype

HevSocks5Server is selected for Phase 0 because it provides:

- TCP `CONNECT`;
- UDP `ASSOCIATE`;
- authentication;
- Android NDK support;
- embeddable API;
- MIT licensing.

The stock interface/mark features do not prove Android VPN binding. The fork invariant is:

```text
every outbound FD
  -> testable prepare hook
  -> android_setsocknetwork(validated VPN handle, fd)
  -> only then connect/send
```

If complete socket/DNS coverage requires a large or fragile fork, reject Hev behind the backend abstraction.

## Decision 3 — app-process data plane

`ProxyService` and its backend run in the app process.

Reasons:

- credentials remain app-private;
- payload forwarding does not run as UID 0;
- per-app VPN policy is attributable to the app UID;
- Android lifecycle/status remain local;
- Kotlin/Rust backend replacement remains possible.

## Decision 4 — ProxyService is sole backend owner

Ownership:

```text
ProxyOnlyController -> ProxyServiceClient -> ProxyService -> ProxyBackend
ProxyOnlyController -> ProxyFirewallClient -> vpnhotspotd
```

The controller never stores or invokes a backend/native handle directly.

## Decision 5 — root daemon owns policy and reuses firewall ledger

The daemon owns:

- downstream listener exposure;
- deny-first policy;
- interface + IPv4 + MAC ACLs;
- IPv6 denial;
- kernel counters;
- deterministic cleanup.

Implementation reuses:

- `IptablesRule`/`IptablesChain`;
- idempotent mutation and `delete_repeated()`;
- `firewall_cleanup::clean()`;
- IPv4/IPv6 target abstraction.

The proxy firewall has an independent long-lived command and does not extend `SessionConfig`.

## Decision 6 — VPN-only selection

`Upstreams.primary` may be physical. Proxy-only:

- enumerates VPN-specific candidates;
- requires current `TRANSPORT_VPN`;
- accepts exactly one usable VPN;
- rejects zero or multiple candidates;
- never uses physical fallback.

Transient network-handle ordering is not user intent.

## Decision 7 — app-UID permission is readiness

A candidate VPN is usable only when the VPN Hotspot app UID can bind to it.

Permission denial maps to `VpnPermissionDenied`; it is not treated as a generic internal failure and never triggers direct fallback.

## Decision 8 — foreground activation requires a grant

Persisted `enabled=true` does not authorize background FGS startup.

A one-time `ActivationGrant` is issued only from an Android-permitted foreground user action and consumed after successful service activation.

Without a valid grant:

```text
ActivationRequired
```

Pre-activation wait/stop/emergency service calls return structured no-op reports.

## Decision 9 — service persists through recovery

Once validly activated, `ProxyService` remains foreground while the feature is enabled, including waiting, VPN loss, daemon loss and cleanup-degraded states.

These states have no listener. This avoids attempting a new background FGS start during dependency recovery.

Automatic process-death resurrection is not assumed.

## Decision 10 — typed, configuration-aware probes

Startup probes are outbound-only:

- app-UID bind;
- outbound TCP;
- outbound UDP when enabled;
- VPN-aware DNS;
- internal listener readiness.

Results map to typed states. UDP is not required when disabled. Generic exceptions are reserved for unexpected failures.

## Decision 11 — network-aware bounded DNS

Domain targets resolve through the validated VPN only.

Phase 0 chooses:

- bounded network-aware resolver workers; or
- asynchronous Android network-aware resolution.

Process-default DNS and unbounded blocking on Hev workers are rejected.

## Decision 12 — standard UDP with explicit range

The MVP supports RFC 1928 `UDP ASSOCIATE`.

Requirements:

- control TCP owns association lifetime;
- source peer validation;
- bounded association count;
- `FRAG != 0` rejected;
- every Internet-facing UDP FD passes the VPN hook;
- relay ports remain inside a configured range;
- range exhaustion is controlled and measured.

If one port is consumed per association:

```text
effective capacity = min(configured association limit, usable port count)
```

## Decision 13 — UDP firewall return policy is evidence-gated

The firewall cannot assume client-facing and Internet-facing UDP sockets are distinct.

Phase 0 must correlate FDs, ports, interfaces, hook calls, packet captures and conntrack state.

No broad VPN-interface allow is permitted. `VerifiedUdpReturnPolicy` remains absent until evidence proves exact semantics.

## Decision 14 — IPv4-only MVP, explicit IPv6 denial

MVP:

- IPv4-only listener/relay;
- no IPv6 endpoint;
- explicit ip6tables reject for TCP and UDP range;
- dual-stack/mapped-address tests.

Full IPv6 relay is deferred.

## Decision 15 — explicit IPv4 and IPv6 deny flags

Firewall deny is protocol state, not an empty ACL convention.

```proto
bool deny_all_ipv4;
bool deny_all_ipv6;
```

This keeps deny-first startup unambiguous as ACL behavior evolves.

## Decision 16 — MAC is enforcement identity

IPv4 allow requires:

```text
input interface + source IPv4 + source MAC
```

IP-only fallback is rejected. Downstreams without reliable MAC identity are unsupported in MVP.

## Decision 17 — global UI mode, per-downstream internals

MVP UI exposes:

```text
VPN_ROUTING
PROXY_ONLY
```

Internal state keeps independent routing/proxy flags for future expansion. Mixed mode is deferred.

## Decision 18 — full restart on VPN generation change

No live `replaceNetwork()` exists in MVP.

```text
deny -> close sessions/listener -> stop backend -> validate new VPN -> start/probe -> allow
```

No session survives across network generations.

## Decision 19 — serialized exception-safe event loop

One conflated event channel and one worker process snapshots and cleanup-retry events.

- new snapshots do not cancel resource transactions;
- timeout enters recovery;
- parent cancellation is rethrown;
- each iteration has an exception boundary;
- terminal `finally` cleanup is mandatory;
- publish/report failures are contained.

## Decision 20 — cleanup debt is itemized

Debt records unresolved resources/actions separately:

- listener closure;
- service/backend handle;
- firewall deny;
- firewall runtime stop;
- daemon Clean;
- feature stop.

Each field clears only when that item succeeds. Listener closure cannot clear firewall debt.

No backend/firewall runtime starts while debt remains.

## Decision 21 — cleanup retry is self-triggered

Unresolved debt schedules `RetryCleanupDebt(generation)` with bounded exponential backoff and jitter.

The controller retains the latest desired snapshot, ignores stale generations and can recover without external state changes.

## Decision 22 — idle state does not create cleanup work

When no applied resources exist:

- cleanup returns a no-op report;
- emergency close is not invoked;
- no cleanup debt can be fabricated.

## Decision 23 — feature stop has one owner

Terminal shutdown owns `stopFeature`. Ordinary backend/firewall cleanup does not also stop the feature service.

## Decision 24 — daemon loss closes listener and creates debt

iptables rules can survive daemon death.

Channel loss triggers immediate listener closure. Surviving firewall handle/Clean obligations are represented as debt. Daemon recovery resolves debt before restart.

## Decision 25 — FlClash owns application selection

VPN Hotspot cannot identify the originating macOS process. FlClash owns `PROCESS-NAME`, path/regex rules and `MATCH,DIRECT`.

VPN Hotspot exposes `PhoneVPN`; optional WARP uses `dialer-proxy: PhoneVPN`.

## Phase 0 gates

1. Exact Hev pin and complete hook audit.
2. Zero/one/multiple VPN selection.
3. Per-app VPN include/exclude matrix.
4. Activation grant and process-restart behavior.
5. Typed/config-aware probes.
6. UDP FD/port/interface/conntrack topology.
7. Bounded VPN-aware DNS.
8. Explicit IPv4/IPv6 denial.
9. Itemized debt creation and partial resolution.
10. Self-triggered retry in a quiescent system.
11. Daemon-death listener closure and Clean.
12. Reliable MAC identity on supported downstreams.
13. Host CI and Android instrumentation.

Until these pass, approval is limited to Phase 0 feasibility work.
