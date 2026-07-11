# Structural review — proxy-only design (`agent/proxy-only-design`)

Reviewed: `README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `RESEARCH_DECISIONS.md`, `CODE_SKETCHES.md`, `TEST_PLAN.md`, cross-checked against the VPNHotspot source and then re-verified through the supplied Graphify index.

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

The revised design documents now address the blocking and high-priority findings below. Implementation is still gated on Phase 0 evidence.

---

## 1. Blocking findings

### 1.1 `Upstreams.primary` is not guaranteed to be a VPN network

`Upstreams.primary` defaults to the VPN flow but can be replaced by the `service.upstream` interface-regex preference. A physical interface such as `wlan0` can therefore become primary.

The existing routing path (`Routing.kt` collection/start path and `SessionConfig.primary_network` construction) forwards this selected network unvalidated. That is valid for routing mode because the user chose the upstream and the feature does not promise VPN-only egress.

Proxy-only makes a stronger claim. The revised plan now requires:

- `Upstreams.vpn` or equivalent VPN-only selection;
- fresh `TRANSPORT_VPN` capability validation;
- rejection of a physical primary override;
- revalidation immediately before startup/`Running`.

**Resolution:** addressed in `README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `CODE_SKETCHES.md`, `RESEARCH_DECISIONS.md` and `TEST_PLAN.md`.

### 1.2 Per-app VPN policy can make binding fail

A VPN allowed/disallowed-app policy may prevent the VPN Hotspot app UID from using the VPN `Network`. `android_setsocknetwork()`/`Network.bindSocket()` can fail even when the network exists and has `TRANSPORT_VPN`.

The revised Phase 0 matrix tests:

- VPN applied to all apps;
- VPN Hotspot explicitly included;
- VPN Hotspot excluded/denied;
- policy changed while running.

Exclusion maps to a distinct user-visible fail-closed state and never falls back to physical networking.

**Resolution:** addressed in Phase 0, selector sketches and security tests.

### 1.3 Root daemon death does not make existing rules disappear

iptables rules remain in the kernel after `vpnhotspotd` exits. Previously committed allow rules can survive without ACL reconciliation or counters.

The revised liveness contract is:

- long-lived firewall call/channel completion is treated as a critical dependency loss;
- app-process listener and active sessions close immediately;
- stale rules are harmless only because no listener remains;
- daemon recovery runs Clean or deny reconciliation before listener restart.

**Resolution:** corrected in architecture, controller sketch and failure matrix; a mid-session daemon-kill integration test is mandatory.

---

## 2. High-priority findings

### 2.1 IPv6 policy gap

An IPv4-only firewall around a dual-stack/wildcard listener could expose the proxy over IPv6.

MVP now explicitly chooses:

- IPv4-only listener and relay;
- no published IPv6 endpoint;
- ip6tables reject rules for TCP listener port and full UDP relay range;
- IPv6 interface-scan tests.

Full IPv6 relay is deferred.

### 2.2 ACL must enforce MAC as well as IP

IP-only matching permits DHCP/static-IP reuse. Revised IPv4 allows require:

```text
input interface + source IPv4 + source MAC + destination port/range
```

Interfaces without reliable MAC identity are unsupported in the MVP.

### 2.3 UDP relay range must be explicit

The original single `udp_port` proto was premature. Phase 0 must verify the pinned Hev behaviour and constrain relays to a fixed range. Revised settings/proto/firewall use `udp_port_range_start/end` unless a single shared port is proven.

### 2.4 Reconciliation must not be cancelled mid-transaction

`collectLatest` can cancel after firewall creation but before applied-state recording, or during unsafe teardown.

The revised controller:

- sends immutable snapshots to `Channel.CONFLATED`;
- uses one serialized worker;
- runs commit/rollback in `NonCancellable` context;
- records partial resources immediately;
- derives VPN generation in the worker;
- fully restarts backend on network-handle change;
- treats client changes as ACL replacement only.

---

## 3. Medium-priority findings

### 3.1 Global mode versus per-downstream model

Resolved as global MVP UI (`VPN_ROUTING`, `PROXY_ONLY`) with independent per-downstream flags underneath. Mixed mode is deferred.

### 3.2 Blocking DNS in Hev workers

Phase 0 now includes blackholed-DNS concurrency measurements. The design requires a bounded resolver pool or asynchronous network-aware resolver; no unbounded blocking call in Hev workers.

### 3.3 Foreground-service type is a merge gate

Moved into Phase 2. The manifest change cannot merge until the current target SDK, required type/permissions and distribution-policy eligibility are documented and tested.

### 3.4 Untested combined mode

`VPN_ROUTING_AND_PROXY` was cut from the MVP rather than left untested.

### 3.5 Native fork CI

The prepare hook now accepts an injected bind function. Linux host CI uses a shim to record calls and force failures; Android instrumentation tests the real `android_setsocknetwork()` path.

### 3.6 Runtime key precision

The revised key includes TCP port, UDP range, credentials version, validated VPN handle, sorted downstream set and backend version. Client ordering and irrelevant link churn do not restart the listener.

---

## 4. Sound decisions retained

- fail-closed by default with no MVP direct fallback;
- deny-first startup and deny-before-normal-teardown ordering;
- data plane in app process and privileged policy in root daemon;
- `ProxyBackend` abstraction so Phase 0 can reject Hev;
- explicit SOCKS5 instead of TPROXY;
- independent proxy firewall lifecycle instead of extending `SessionConfig`;
- phased PR sequence with a hard Phase 0 stop gate;
- threat coverage for open proxy, VPN bypass, DNS leaks, UDP hijacking and exhaustion.

## 5. Required implementation gates after rewrite

The documents are now internally aligned, but implementation must still provide evidence for:

1. exact Hev pin and complete prepare-hook coverage;
2. per-app VPN policy matrix;
3. UDP relay range behaviour;
4. non-blocking VPN-aware DNS under blackhole;
5. IPv6 denial;
6. iface+IP+MAC rules using shared `IptablesRule` ledger;
7. daemon-death listener shutdown and recovery Clean;
8. foreground-service declaration/policy;
9. reliable MAC identity for each supported downstream;
10. host CI plus Android instrumentation tests.

Until these pass, the design is approved only in direction, not implementation-ready.