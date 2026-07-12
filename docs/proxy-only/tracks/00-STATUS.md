# Proxy-only — Phase 0 status tracker

Single source of truth for what is **done**, **in progress**, and **not started**
in the proxy-only Phase 0 spike. Supersedes the scattered status notes across the
12 implementation-review rounds.

- Latest reviewed source commit: `379352e8e9a769624f29946c050e2a97e3f8c077`
- Latest review commit: `821cb681901f07aa496ff0df0a056f27d137fb0b` (round 12)
- Track A/F implementation head: `d2b814ea7f9539ecd53f1b9099fad12904ab93f9`
- Branch: `agent/proxy-only-design`
- PR state: **draft** (correctly held; structural integration gaps remain)

## Legend

- ✅ done and verified
- 🟡 partial / in progress
- ⬜ not started
- 🔴 blocked / failing

## What actually exists in the tree

| Area | File(s) | State |
| --- | --- | --- |
| Controller reconciliation + cleanup-debt loop | `mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyController.kt` | 🟡 iterated through R12 |
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
| **Proxy controller tests** | `mobile/src/test/java/be/mygod/vpnhotspot/proxy/` | ✅ Track A: generation matrix, teardown dominance, restart races, typed debt conflicts |
| Dependency graph submission | `.github/workflows/dependency-submission.yml` | ✅ workflow added; requires repository Dependency graph to be enabled |
| Dependency Review | `.github/workflows/dependency-review.yml` | 🔴 policy intact, blocked by dependency-graph API HTTP 403 |

## Track A result — complete

Track A added a test-only reflection harness and four focused suites without changing
production behavior:

- six-row generation/session/epoch matrix against `cleanupApplied()`;
- the same six rows against cleanup-debt retry;
- authoritative `stopFeature()` teardown dominance, including simultaneous firewall debt;
- G1-handle/G2-daemon fast-path and cleanup races;
- typed service/firewall handle-conflict behavior.

The first CI artifact showed every intended matrix/race/teardown test passing. Its only
failure was an extra merge assertion outside current Track D semantics; that assertion
was removed in `4da05cf068f14bf3724533636ec186d374a87d98`. The subsequent `assembleDebug check`
step completed successfully.

## Track F result — diagnosed and blocked on repository setting

The root cause is **configuration (Track F cause A)**, not a named vulnerable dependency:

```text
Dependency graph compare API HTTP status: 403
{"message":"Forbidden", ...}
```

Implemented safeguards:

- added a separate push-triggered Gradle dependency-submission workflow with narrowly
  scoped `contents: write` permission;
- kept the PR Dependency Review job read-only;
- preserved `fail-on-severity: moderate` with no `warn-only` or `continue-on-error`;
- enabled snapshot-warning retry for submission timing races;
- added an explicit preflight error that reports how to enable the missing graph;
- added per-PR concurrency so superseded CI runs are cancelled.

Repository action still required:

1. Open **Settings → Code security and analysis**.
2. Enable **Dependency graph**.
3. Re-run **Dependency Submission**, then **Dependency Review**.

Until that setting is enabled, the check should remain red rather than silently bypassing
the security policy.

## Phase 0 exit criteria (from IMPLEMENTATION_PLAN.md)

| Criterion | State | Track |
| --- | --- | --- |
| Exact Hev pin and maintainable fork delta | ⬜ | (Phase 1) |
| Complete per-FD hook coverage in host CI | ⬜ | (Phase 1) |
| Real Android binding through selected VPN | ⬜ | (Phase 1) |
| Zero/one/multiple VPN behavior | 🟡 selector code; broader selector tests still open | future integration tests |
| Per-app VPN include/exclude behavior | 🟡 | future integration tests |
| Activation-grant + process-restart behavior | 🟡 controller path | C / integration tests |
| Typed, config-aware TCP/UDP/DNS/readiness probes | 🟡 | backend integration tests |
| UDP topology + safe firewall return-policy evidence | ⬜ | (Phase 0.5) |
| Bounded VPN-aware DNS under blackhole | ⬜ | (Phase 0.6) |
| Explicit IPv4/IPv6 denial | 🟡 proto flags only | B |
| Itemized cleanup debt + partial resolution | ✅ logic + focused Track A tests | A, D |
| Self-triggered backoff retry, quiescent external state | 🟡 logic present; supervisor tests remain | C |
| No restart over unresolved firewall debt | ✅ focused generation/race tests | A |
| Repeated start/stop without FD/thread leaks | ⬜ | needs backend |

## Open blockers after Tracks A and F

| # | Blocker | Track | Priority |
| --- | --- | --- | --- |
| 1 | Daemon does not enforce session/epoch/acks/stale-token rejection | **B** | P0 — security boundary remains Kotlin-only |
| 2 | `cleanupScope` is an abstract lifetime contract, not a real supervisor | **C** | P1 |
| 3 | `mergeUnresolved()` concatenates ordinary failure lists → unbounded growth | **D** | P1 |
| 4 | Normalization silently drops records; downstream IPv4 pick is order-dependent | **E** | P2 |
| 5 | Repository Dependency graph is disabled/unavailable (HTTP 403) | **F** | external configuration blocker |

## Recommended sequencing

1. Enable the repository **Dependency graph** and re-run Track F workflows.
2. Begin **Track B** — the long pole and security boundary.
3. Run **Tracks C, D, E** alongside B; Track A now protects their controller changes.

## Track index

- ✅ [Track A — generation/session/epoch matrix tests](TRACK-A-generation-matrix-tests.md)
- ⬜ [Track B — daemon protocol enforcement](TRACK-B-daemon-protocol-enforcement.md)
- ⬜ [Track C — service-owned cleanup supervisor](TRACK-C-cleanup-supervisor.md)
- ⬜ [Track D — bounded failure history](TRACK-D-bounded-failure-history.md)
- ⬜ [Track E — normalization diagnostics + deterministic selection](TRACK-E-normalization-diagnostics.md)
- 🔴 [Track F — Dependency Review CI fix](TRACK-F-dependency-review-ci.md) — implementation ready; repository setting required

## Definition of done for Phase 0 sign-off

Tracks B, C, D and E complete with tests green, Track F check green after Dependency
graph enablement, and the Phase 0 security rows above showing no ⬜/🟡. Only then does
the PR leave draft.
