# Proxy-only — Phase 0 status tracker

Single source of truth for what is **done**, **in progress**, and **not started**
in the proxy-only Phase 0 spike. Supersedes the scattered status notes across the
12 implementation-review rounds.

- Latest reviewed source commit: `379352e8e9a769624f29946c050e2a97e3f8c077`
- Latest review commit: `821cb681901f07aa496ff0df0a056f27d137fb0b` (round 12)
- Branch: `agent/proxy-only-design`
- PR state: **draft** (correctly held; structural + test gaps remain)

## Legend

- ✅ done and reviewed
- 🟡 partial / in progress
- ⬜ not started
- 🔴 blocked / failing

## What actually exists in the tree

| Area | File(s) | State |
| --- | --- | --- |
| Controller reconciliation + cleanup-debt loop | `mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyController.kt` (1094 lines) | 🟡 iterated through R12 |
| Itemized cleanup debt model | `proxy/CleanupDebt.kt` | 🟡 logic complete, history unbounded (Track D) |
| Domain models + normalization | `proxy/ProxyModels.kt` | 🟡 works; silent drops, no diagnostics (Track E) |
| Service/firewall client interfaces | `proxy/ProxyServiceClient.kt` | ✅ interfaces defined |
| VPN selector | `proxy/ProxyVpnSelector.kt` | 🟡 selection logic present |
| Probe types | `proxy/Probes.kt` | 🟡 typed results present |
| UDP topology types | `proxy/UdpTopology.kt` | 🟡 scaffolding |
| Firewall proto | `mobile/src/main/proto/proxy_firewall.proto` | 🟡 messages only; no acks/session/epoch (Track B) |
| **Rust daemon proxy-firewall** | — | ⬜ **not implemented** (only `nat66/tproxy.rs` exists) |
| **ProxyService (foreground service)** | — | ⬜ not implemented |
| **Hev backend / native hook** | — | ⬜ no submodule pinned, no `HevProxyBackend`/`ProxyNative` |
| **Proxy tests** | — | 🔴 **none exist** (`mobile/src/test/.../proxy/` absent) |

## Phase 0 exit criteria (from IMPLEMENTATION_PLAN.md)

| Criterion | State | Track |
| --- | --- | --- |
| Exact Hev pin and maintainable fork delta | ⬜ | (Phase 1) |
| Complete per-FD hook coverage in host CI | ⬜ | (Phase 1) |
| Real Android binding through selected VPN | ⬜ | (Phase 1) |
| Zero/one/multiple VPN behavior | 🟡 selector code, no tests | A |
| Per-app VPN include/exclude behavior | 🟡 | A |
| Activation-grant + process-restart behavior | 🟡 controller path, no tests | A |
| Typed, config-aware TCP/UDP/DNS/readiness probes | 🟡 | A |
| UDP topology + safe firewall return-policy evidence | ⬜ | (Phase 0.5) |
| Bounded VPN-aware DNS under blackhole | ⬜ | (Phase 0.6) |
| Explicit IPv4/IPv6 denial | 🟡 proto flags only | B |
| Itemized cleanup debt + partial resolution | ✅ logic; ⬜ tests | A, D |
| Self-triggered backoff retry, quiescent external state | ✅ logic; ⬜ tests | A |
| No restart over unresolved debt | ✅ logic; ⬜ tests | A |
| Repeated start/stop without FD/thread leaks | ⬜ | (needs backend) |

## Open blockers from review round 12

| # | Blocker | Track | Priority |
| --- | --- | --- | --- |
| 1 | Generation/session/epoch matrix tests never added | **A** | P0 — cheapest, closes R10–R12 loop |
| 2 | Daemon does not enforce session/epoch/acks/stale-token rejection | **B** | P0 — largest; security boundary is Kotlin-only today |
| 3 | `cleanupScope` is an abstract lifetime contract, not a real supervisor | **C** | P1 |
| 4 | `mergeUnresolved()` concatenates failure lists → unbounded growth | **D** | P1 |
| 5 | Normalization silently drops records; downstream IPv4 pick is order-dependent | **E** | P2 |
| 6 | Dependency Review CI failing across R10–R12; cause never captured | **F** | P1 — red check on PR |

## Recommended sequencing

1. **Track A** first — the R11→R12 fix corrected the logic but left it unverified.
   Tests are the fastest way to lock in the current behavior and catch regressions
   in Tracks C–E.
2. **Track F** in parallel — small, unblocks a green PR check, no code coupling.
3. **Track B** — the long pole. Everything downstream (real ProxyService, backend)
   depends on the daemon protocol being an enforced boundary.
4. **Tracks C, D, E** — can proceed alongside B; each is self-contained.

## Track index

- [Track A — generation/session/epoch matrix tests](TRACK-A-generation-matrix-tests.md)
- [Track B — daemon protocol enforcement](TRACK-B-daemon-protocol-enforcement.md)
- [Track C — service-owned cleanup supervisor](TRACK-C-cleanup-supervisor.md)
- [Track D — bounded failure history](TRACK-D-bounded-failure-history.md)
- [Track E — normalization diagnostics + deterministic selection](TRACK-E-normalization-diagnostics.md)
- [Track F — Dependency Review CI fix](TRACK-F-dependency-review-ci.md)

## Definition of done for Phase 0 sign-off

All of Tracks A, B, C, D, E complete with tests green, Track F check green, and the
Phase 0 exit-criteria table above showing no ⬜/🟡 in the security rows. Only then
does the PR leave draft.
