# Proxy-only — Phase 0 status tracker

Single source of truth for what is **done**, **in progress**, and **not started**
in the proxy-only Phase 0 spike. Supersedes the scattered status notes across the
13 implementation-review rounds.

- Latest reviewed controller source: `e2492a5e3838013b089600f7b9631f9f6be01cee`
- Latest review document commit: `8189193a0b640d6ab3ee5649be605c8bb22870ce` (round 13)
- Track B verified clean implementation head: `e2492a5e3838013b089600f7b9631f9f6be01cee`
- Branch: `agent/proxy-only-design`
- PR state: **draft** (correctly held; Tracks C–E and production service/backend integration remain)

## Legend

- ✅ done and verified
- 🟡 partial / in progress
- ⬜ not started
- 🔴 blocked / failing

## What exists in the tree

| Area | File(s) | State |
| --- | --- | --- |
| Controller reconciliation + cleanup-debt loop | `proxy/ProxyOnlyController.kt` | 🟡 through R13; concrete supervisor remains Track C |
| Itemized cleanup debt model | `proxy/CleanupDebt.kt` | 🟡 logic complete, history unbounded (Track D) |
| Domain models + normalization | `proxy/ProxyModels.kt` | 🟡 works; silent drops, no diagnostics (Track E) |
| Service/firewall client interfaces | `proxy/ProxyServiceClient.kt` | ✅ interfaces defined; authoritative sanitation generation carried |
| VPN selector | `proxy/ProxyVpnSelector.kt` | 🟡 selection logic present |
| Probe and UDP topology types | `proxy/Probes.kt`, `proxy/UdpTopology.kt` | 🟡 scaffolding/integration pending |
| Proxy firewall wire protocol | `mobile/src/main/proto/proxy_firewall.proto`, `daemon.proto` | ✅ command envelope, authoritative identity and typed acks |
| Rust daemon proxy firewall | `rust/vpnhotspotd/src/proxy_firewall/`, `proxy_firewall_kernel.rs` | ✅ enforced token boundary, ledger, persistent session store and iptables backend |
| Kotlin daemon firewall adapter | `proxy/DaemonProxyFirewallClient.kt` | ✅ Wire mapping, transport-failure null gate and generation cross-check |
| Proxy controller/protocol tests | `mobile/src/test/java/be/mygod/vpnhotspot/proxy/` | ✅ Tracks A/B plus R13 mismatch/transport regressions |
| Dependency graph submission/review | `.github/workflows/` | ✅ submission and moderate-severity review green |
| **ProxyService (foreground service)** | — | ⬜ not implemented |
| **Hev backend / native hook integration** | — | ⬜ no pinned backend/JNI production integration |

## Track A result — complete

Track A covers the six-row generation/session/epoch matrices, authoritative teardown
dominance, daemon restart races and typed cleanup-debt conflicts. Round 13 extends this
coverage so primary sanitation and cleanup-debt retry reject acknowledgement-generation
disagreement without starting a runtime.

## Track B result — complete

Track B moved session/epoch enforcement into the root daemon and the round-13 follow-up
closed the remaining reviewed seams:

- every start/replace/deny/stop request carries the daemon-issued `(session_id, epoch)`;
- validation and kernel mutation are serialized under one daemon-side mutex;
- stale session/epoch commands return typed acknowledgements before any kernel mutation;
- sanitation installs explicit deny containment before clearing the ledger and advancing the epoch;
- sanitation transport `IOException` returns through the controller's null/debt gate and invalidates any cached token;
- the acknowledgement-issued daemon generation is carried through `SanitationResult` and must match the observed desired-state generation;
- generation mismatch retains `daemonCleanPending` and prevents start in both primary reconcile and debt retry;
- deny-first start rejects any allowed clients;
- daemon-wide external cleanup cannot clear the ledger or advance the epoch unless kernel cleanup succeeds;
- session IDs are crash-persistent, fsync'd and protected by an inter-process file lock;
- serial, threaded and eight-process tests verify session-counter uniqueness;
- stale session-counter temporary files are removed while the lock is held;
- second-start, deny, stop/unknown-handle and external-clean lifecycle invariants have direct Rust tests;
- proxy chains participate in daemon-wide cleanup;
- IPv4 rules require downstream interface + client IP + MAC and end in reject;
- IPv6 listener/relay ports remain denied;
- Kotlin trusts only acknowledgement-issued identity/handles and maps stale cleanup acks to non-resolving failures;
- Rust check/tests/clippy/audit, Android `assembleDebug check`, release R8 verification and Dependency Review passed on `e2492a5e` under the restored normal workflow.

The concrete foreground-service implementation that supplies the RPC transport,
containment configuration and health observation remains production integration. The
controller now rejects a health-generation value that disagrees with the same daemon's
acknowledgement instead of silently accepting two clocks.

## Track F result — complete

Dependency graph submission and read-only PR Dependency Review are operational.
`fail-on-severity: moderate` remains enforced; no warning-only bypass or blanket
suppression was added.

## Phase 0 exit criteria

| Criterion | State | Track |
| --- | --- | --- |
| Exact Hev pin and maintainable fork delta | ⬜ | Phase 1/backend |
| Complete per-FD hook coverage in host CI | ⬜ | Phase 1/backend |
| Real Android binding through selected VPN | ⬜ | Phase 1/backend |
| Zero/one/multiple VPN behavior | 🟡 selector + focused controller coverage | integration tests |
| Per-app VPN include/exclude behavior | 🟡 | integration tests |
| Activation-grant + process-restart behavior | 🟡 controller path | C / integration tests |
| Typed, config-aware TCP/UDP/DNS/readiness probes | 🟡 | backend integration tests |
| UDP topology + safe firewall return-policy evidence | ⬜ | Phase 0.5 |
| Bounded VPN-aware DNS under blackhole | ⬜ | Phase 0.6 |
| Explicit IPv4/IPv6 denial | ✅ daemon-enforced proxy chains | B |
| Authoritative stale-token rejection | ✅ zero-mutation Rust tests | B |
| Authoritative sanitation generation agreement | ✅ controller fail-closed cross-checks | A, B |
| Crash-persistent unique daemon session identity | ✅ serial + thread + process tests | B |
| Itemized cleanup debt + partial resolution | ✅ focused Track A tests | A, D |
| Self-triggered backoff retry, quiescent external state | 🟡 concrete supervisor remains | C |
| No restart over unresolved firewall debt | ✅ focused generation/race tests | A, B |
| Repeated start/stop without FD/thread leaks | ⬜ | backend/service integration |

## Open blockers after Tracks A, B and F

| # | Blocker | Track | Priority |
| --- | --- | --- | --- |
| 1 | `cleanupScope` is an abstract lifetime contract, not a real service-owned supervisor | **C** | P1 |
| 2 | `mergeUnresolved()` ordinary failure history can grow without bound | **D** | P1 |
| 3 | Normalization silently drops records and downstream IPv4 selection is order-dependent | **E** | P2 |
| 4 | Foreground service, real RPC/health composition, Hev backend/JNI and device verification are absent | production phases | P0 before release |

## Recommended sequencing

1. Implement **Track C** so cleanup ownership survives controller worker cancellation and the concrete service supplies RPC plus authoritative daemon health identity.
2. Implement **Track D** and **Track E** in parallel under Track A regression coverage.
3. Build the real foreground service/backend integration, then run physical-device verification.

## Track index

- ✅ [Track A — generation/session/epoch matrix tests](TRACK-A-generation-matrix-tests.md)
- ✅ [Track B — daemon protocol enforcement](TRACK-B-daemon-protocol-enforcement.md)
- ⬜ [Track C — service-owned cleanup supervisor](TRACK-C-cleanup-supervisor.md)
- ⬜ [Track D — bounded failure history](TRACK-D-bounded-failure-history.md)
- ⬜ [Track E — normalization diagnostics + deterministic selection](TRACK-E-normalization-diagnostics.md)
- ✅ [Track F — Dependency Review CI fix](TRACK-F-dependency-review-ci.md)

## Definition of done for Phase 0 sign-off

Tracks C, D and E must complete with tests green, followed by concrete service/backend
composition and the remaining Phase 0 evidence rows. Only then should the PR leave draft.
