# Structural review — proxy-only design (`agent/proxy-only-design`)

Reviewed: `README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `RESEARCH_DECISIONS.md`, `CODE_SKETCHES.md`, `TEST_PLAN.md`, cross-checked against the VPNHotspot source and then re-verified through the supplied Graphify index.

> This is the round-1 review. The follow-up resolution audit and additional lifecycle findings are in [REVIEW_ROUND2.md](REVIEW_ROUND2.md).

## Review provenance and graph grounding

The supplied Graphify index reports:

- 2,464 nodes;
- 6,088 edges;
- `SessionConfig` as a 90-edge god-node;
- `IptablesRule` as a 44-edge core abstraction.

The graph does not replace line-level source review, but it strengthened two conclusions:

1. `Routing.kt` already consumes `Upstreams.primary` and forwards its `networkHandle` to the daemon without VPN-capability validation. That is acceptable for configurable routing mode but cannot be inherited by a feature promising VPN-only fail-closed egress.
2. The daemon's established firewall convention is the `IptablesRule` mutation ledger in `routing/iptables.rs`, including idempotent insertion/deletion and `delete_repeated()`, plus deterministic cleanup in `routing/firewall_cleanup.rs::clean()`. `proxy_firewall/` must reuse this machinery rather than create a parallel rule representation.

The high connectivity of `SessionConfig` and `IptablesRule` also supports the design decision to keep the proxy firewall lifecycle separate from `SessionConfig` while reusing the existing firewall primitive.

## Verdict

The direction remains sound: explicit SOCKS5, app-process data plane, deny-first policy, fail-closed behaviour, backend abstraction and phased feasibility gate. The original plan needed corrections around VPN selection, daemon death, IPv6, identity enforcement, UDP ports and cancellation safety.

The revised design documents address the blocking and high-priority round-1 findings. Round 2 further requires exception-safe worker shutdown, exact service/backend ownership, outbound-only probes, UDP socket-topology discovery and persistent foreground-service recovery.

---

## 1. Blocking findings

### 1.1 `Upstreams.primary` is not guaranteed to be a VPN network

`Upstreams.primary` defaults to the VPN flow but can be replaced by the `service.upstream` interface-regex preference. A physical interface such as `wlan0` can therefore become primary.

The existing routing path forwards this selected network unvalidated. That is valid for routing mode because the user chose the upstream and the feature does not promise VPN-only egress.

Proxy-only requires:

- VPN-specific candidate discovery;
- fresh `TRANSPORT_VPN` validation;
- rejection of physical primary override;
- revalidation before startup and `Running`.

**Resolution:** addressed throughout the revised design.

### 1.2 Per-app VPN policy can make binding fail

A VPN allowed/disallowed-app policy may prevent the VPN Hotspot app UID from using the VPN `Network`.

Phase 0 tests:

- VPN applied to all apps;
- VPN Hotspot explicitly included;
- VPN Hotspot excluded/denied;
- policy changed while running.

Exclusion maps to a distinct fail-closed state and never falls back to physical networking.

### 1.3 Root daemon death does not make existing rules disappear

iptables rules can remain after `vpnhotspotd` exits.

The revised contract:

- daemon channel completion is critical dependency loss;
- `ProxyService` closes listener/sessions immediately;
- stale rules are harmless only because no listener remains;
- daemon recovery runs Clean or deny before restart.

---

## 2. High-priority findings

### 2.1 IPv6 policy gap

MVP uses IPv4-only listener/relay, no IPv6 endpoint and ip6tables reject rules for TCP and the full UDP range.

### 2.2 ACL must enforce MAC as well as IP

Allow requires:

```text
input interface + source IPv4 + source MAC + destination port/range
```

Interfaces without reliable MAC identity are unsupported.

### 2.3 UDP relay range must be explicit

Settings/proto/firewall use range start/end unless Phase 0 proves a single shared port.

### 2.4 Reconciliation must not be cancelled mid-transaction

Round 1 replaced `collectLatest` with a conflated channel and one non-cancellable worker. Round 2 adds the missing exception boundary, cleanup aggregation and terminal `finally` cleanup.

---

## 3. Medium-priority findings

- Global MVP mode with per-downstream internal state: resolved.
- Blocking DNS: Phase 0 bounded/asynchronous resolver gate.
- FGS type: Phase 2 gate.
- Combined mode: removed from MVP.
- Native fork CI: host bind shim plus Android instrumentation.
- Runtime key: ports/range, credentials, VPN handle, normalized downstreams and backend version.

## 4. Sound decisions retained

- fail-closed without direct fallback;
- deny-first startup;
- app-process data plane and root policy;
- backend abstraction;
- explicit SOCKS5 over TPROXY;
- proxy firewall lifecycle separate from `SessionConfig`;
- shared `IptablesRule` ledger and Clean;
- hard Phase 0 gate.

## 5. Implementation gates

Implementation must provide evidence for:

1. exact Hev pin and complete hook coverage;
2. per-app VPN policy matrix;
3. multiple-VPN handling;
4. UDP FD/port/interface/conntrack topology;
5. bounded VPN-aware DNS;
6. IPv6 denial;
7. iface+IP+MAC firewall rules;
8. daemon-death listener shutdown;
9. exception-safe worker and terminal cleanup;
10. ProxyService ownership and background recovery;
11. FGS type/start-context policy;
12. host CI plus Android device tests.

See [REVIEW_ROUND2.md](REVIEW_ROUND2.md) for the current approval boundary.
