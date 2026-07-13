# Proxy-only — Phase 0 status tracker

Single source of truth for what is **done**, **in progress**, and **not started**
in the proxy-only Phase 0 spike. Supersedes the scattered status notes across the
implementation-review rounds.

- Implementation branch: `agent/proxy-only-design`
- Latest verified clean source head: `81d902c60ec05b068c86cb4a51e8f341ed01007a`
- Documentation commits may follow that source head without changing executable code
- Verification source: the normal `Test` and `Dependency Review` workflows
- PR state: **draft** (correctly held; Android service/root transport, native backend and
  physical-device evidence remain)

## Legend

- ✅ done and verified by focused regression coverage
- 🟡 partial / integration work remains
- ⬜ not started
- 🔴 blocked / failing

## What exists in the tree

| Area | File(s) | State |
| --- | --- | --- |
| Controller reconciliation + cleanup-debt loop | `proxy/ProxyOnlyController.kt` | ✅ service-owned retries, authoritative debt activation, mutex-safe teardown |
| Cleanup retry supervisor + Phase-0 owner | `proxy/ProxyCleanupSupervisor.kt`, `proxy/ProxyService.kt` | ✅ named owner, worker-independent scope, explicit shutdown ordering |
| Itemized cleanup debt model | `proxy/CleanupDebt.kt` | ✅ bounded and deduplicated failure history (Track D) |
| Domain models + normalization | `proxy/ProxyModels.kt`, `proxy/ProxyNormalizationReport.kt` | ✅ typed diagnostics and deterministic downstream address selection (Track E) |
| Service/firewall client interfaces | `proxy/ProxyServiceClient.kt` | ✅ interfaces defined; stable cleanup-failure identity added |
| Daemon health/firewall composition | `proxy/ProxyDaemonState.kt`, `proxy/DaemonProxyFirewallClient.kt` | ✅ acknowledgement-backed generation, deny-first bootstrap and serialized token use (Track G) |
| VPN selector | `proxy/ProxyVpnSelector.kt` | 🟡 selection logic present; production Android composition pending |
| Probe and UDP topology types | `proxy/Probes.kt`, `proxy/UdpTopology.kt` | 🟡 scaffolding/integration pending |
| Proxy firewall wire protocol | `mobile/src/main/proto/proxy_firewall.proto`, `daemon.proto` | ✅ command envelope, authoritative identity and typed acknowledgements |
| Rust daemon proxy firewall | `rust/vpnhotspotd/src/proxy_firewall/`, `proxy_firewall_kernel.rs` | ✅ enforced token boundary, ledger, persistent session store and iptables backend |
| Proxy controller/protocol/composition tests | `mobile/src/test/java/be/mygod/vpnhotspot/proxy/` | ✅ Tracks A–G plus reviewed race/transport regressions |
| Dependency graph submission/review | `.github/workflows/` | ✅ submission and moderate-severity review operational |
| **Concrete root-process request/reply transport for proxy commands** | — | ⬜ not implemented in the proxy composition |
| **Production ProxyService foreground service** | — | ⬜ not implemented; Phase-0 cleanup owner is not the Android FGS |
| **Hev backend / native hook integration** | — | ⬜ no pinned backend/JNI production integration |

## Track A result — complete

Track A covers generation/session/epoch matrices, authoritative teardown dominance,
daemon restart races and typed cleanup-debt conflicts. Primary sanitation and
cleanup-debt retry reject acknowledgement-generation disagreement without starting a
runtime.

## Track B result — complete

Track B moved session/epoch enforcement into the root daemon and closed the reviewed
protocol seams:

- every start/replace/deny/stop request carries daemon-issued identity;
- validation and kernel mutation are serialized under one daemon-side mutex;
- stale session/epoch commands return typed acknowledgements before mutation;
- sanitation installs deny containment before clearing the ledger and advancing epoch;
- transport failure and generation disagreement retain fail-closed sanitation debt;
- session IDs are crash-persistent and protected by inter-process locking;
- deny-first start, lifecycle invariants and stale-token rejection have direct tests;
- IPv4 rules bind downstream interface + client IP + MAC and IPv6 listener/relay ports
  remain denied.

The concrete root-process proxy RPC transport and Android service remain production
integration.

## Track C result — complete, review hardening applied

Track C replaces the raw cleanup-scope promise with an owned lifecycle object:

- `ProxyCleanupSupervisor` owns a `SupervisorJob` retry scope and exposes idempotent
  final shutdown;
- `ProxyServiceCleanupOwner` is the named Phase-0 owner and authoritative shutdown site;
- retries run in the service-owned scope and survive controller-worker cancellation;
- retry state is mutated under `stateMutex`, including worker-finally teardown;
- service retry eligibility comes from immutable `CleanupDebt.serviceWasActivated`;
- non-activated service/listener/feature debt resolves as void without IPC;
- the lifecycle regression enters the real worker-finally path, creates terminal debt,
  joins the worker, and proves supervisor-owned IPC drains the debt afterward;
- owner documentation requires cancel/join of the worker before final cleanup-owner
  shutdown.

The Phase-0 owner proves lifecycle semantics but is not the production Android foreground
service.

## Track D result — complete

Cleanup failure history is deterministic and bounded:

- `CleanupFailure.kind` and `CleanupFailure.key` provide stable identity without relying
  on `Throwable.equals`;
- message identity is capped at 120 characters;
- `mergeFailures` preserves first-seen order, deduplicates by key and hard-caps history
  at 32 entries while retaining the newest distinct diagnostics;
- ordinary failures, conflict failures and controller-created retry failures all pass
  through the bounded merge path;
- regressions cover repeated identical failures, distinct causes on one step, overflow
  tail retention and preserved conflict deduplication.

## Track E result — complete

Normalization is deterministic and auditable:

- `normalized()` remains the compatibility wrapper;
- `normalizedWithReport()` returns the canonical state plus structured drop/address
  diagnostics;
- invalid interface names, invalid MACs, filtered non-routable addresses and records
  with no routable IPv4 receive typed reasons;
- downstream address selection is independent of observation order and chooses the
  numerically smallest routable IPv4;
- the report records candidates, the chosen address and the explicit
  `smallest-routable-ipv4` policy;
- regressions cover order independence, all drop reasons, surviving records with filtered
  addresses and clean-input reporting.

## Track F result — complete

Dependency graph submission and read-only PR Dependency Review are operational.
`fail-on-severity: moderate` remains enforced; no warning-only bypass or blanket
suppression was added.

## Track G result — complete

Daemon health and generation composition now obey the Track B acknowledgement contract:

- `ProxyDaemonState` permits only unavailable state or a complete non-zero acknowledged
  `(sessionId, generation)` identity;
- raw desired-state health/generation values are overwritten by the acknowledged state;
- deny-first sanitation bootstraps identity before the controller worker starts and after
  root transport reconnect;
- failed sanitation cannot transiently publish healthy state;
- transport disconnect clears health and generation immediately;
- one composition-owned mutex serializes reconnect bootstrap with every controller firewall
  operation, preventing latest-token overwrite races;
- regressions cover initial bootstrap, daemon restart, failed acknowledgement, disconnect,
  identity validation and concurrent bootstrap/controller sanitation.

This closes the generation-clock composition seam. The concrete root-process transport and
physical-device restart test remain open.

## Phase 0 exit criteria

| Criterion | State | Track |
| --- | --- | --- |
| Exact Hev pin and maintainable fork delta | ⬜ | Phase 1/backend |
| Complete per-FD hook coverage in host CI | ⬜ | Phase 1/backend |
| Real Android binding through selected VPN | ⬜ | Phase 1/backend |
| Zero/one/multiple VPN behavior | 🟡 selector + focused controller coverage | integration tests |
| Per-app VPN include/exclude behavior | 🟡 | integration tests |
| Activation-grant + process-restart behavior | 🟡 controller and cleanup-owner paths | production integration tests |
| Typed, config-aware TCP/UDP/DNS/readiness probes | 🟡 | backend integration tests |
| UDP topology + safe firewall return-policy evidence | ⬜ | Phase 0.5 |
| Bounded VPN-aware DNS under blackhole | ⬜ | Phase 0.6 |
| Explicit IPv4/IPv6 denial | ✅ daemon-enforced proxy chains | B |
| Authoritative stale-token rejection | ✅ zero-mutation Rust tests | B |
| Authoritative sanitation generation agreement | ✅ controller fail-closed cross-checks | A, B |
| Acknowledgement-backed desired-state generation | ✅ owned bootstrap and serialized composition | G |
| Crash-persistent unique daemon session identity | ✅ serial + thread + process tests | B |
| Itemized cleanup debt + partial resolution | ✅ bounded history and focused regressions | A, D |
| Service-owned self-triggered cleanup retry | ✅ real worker-exit lifecycle regression | C |
| Deterministic normalization + drop diagnostics | ✅ structured report and order-independent selection | E |
| No restart over unresolved firewall debt | ✅ focused generation/race tests | A, B |
| Repeated start/stop without FD/thread leaks | ⬜ | backend/service integration |

## Why there is no proxy option in the installed app (read this first)

Tracks A–G build and unit-test the controller, daemon protocol, cleanup supervisor and
health composition **as a self-contained `proxy/` package exercised only by JVM tests**.
Verified against the tree: no class in `proxy/` is referenced by `MainActivity`,
`VpnHotspotApp`, `SettingsScreen`, or any other UI/app file, and there is **no `<service>`
for the proxy in `AndroidManifest.xml`**. `proxy/ProxyService.kt` is the Phase-0
`ProxyServiceCleanupOwner`, not an Android `Service`.

So the feature is invisible on-device by construction — the app-integration layer was never
written (it is Phase 4 + Phase 7 in `IMPLEMENTATION_PLAN.md`, deliberately gated behind
Phase 0). Tracks **H–K** below are that missing layer.

## UI + service integration (Tracks H–K) — makes the option appear and function

| Track | Delivers | Makes the option… |
| --- | --- | --- |
| **H** | `ProxyOnlyPreferences` (App.pref), settings flow, `DesiredProxyState` source, activation grants | …have persisted state to bind to |
| **I** | Real foreground `ProxyOnlyService` + manifest `<service>`, binder exposing `StateFlow<ProxyOnlyState>` | …runnable and observable by the OS/UI |
| **J** | `RootDestination.Proxy` tab, `ProxyScreen`, enable switch, Resume action, live state | **…visible and interactive in the app** |
| **K** | `RootProxyFirewallRpc` over the `DaemonController` channel | …actually reach the root firewall daemon |

Minimum to **see and interact** with the toggle: **H + I + J** (with a stubbed backend the
state machine lands in `WaitingForVpn`/`FailClosed`, which still proves the chain is live).
Add **K** for a real firewall control plane. Actual SOCKS5 traffic still needs the separate
Hev/JNI backend track.

## Open blockers after Tracks A–G

| # | Blocker | Track | Priority |
| --- | --- | --- | --- |
| 1 | No persisted settings / desired-state source feeding the controller | **H** | P0 for visibility |
| 2 | No production Android foreground service + manifest registration | **I** | P0 for visibility |
| 3 | No Compose UI tab/toggle — the literal cause of "I don't see the option" | **J** | P0 for visibility |
| 4 | No concrete root-process proxy request/reply transport | **K** | P0 for function |
| 5 | Pinned Hev/native backend, JNI boundary and VPN-bound socket hooks are absent | backend track | P0 before release |
| 6 | UDP/DNS evidence and physical-device start/stop/restart/leak verification are absent | device track | P0 before release |

## Recommended sequencing

1. **Track H → I → J** to make the proxy option visible and interactive in the app (this is
   the fix for the missing UI). With a stubbed backend it will show real typed states.
2. **Track K** to give the firewall side a real transport to the root daemon.
3. Integrate the pinned native (Hev) backend and VPN-bound socket/probe paths.
4. Run actual daemon-restart, UDP/DNS and repeated lifecycle evidence on devices.

## Track index

- ✅ [Track A — generation/session/epoch matrix tests](TRACK-A-generation-matrix-tests.md)
- ✅ [Track B — daemon protocol enforcement](TRACK-B-daemon-protocol-enforcement.md)
- ✅ [Track C — service-owned cleanup supervisor](TRACK-C-cleanup-supervisor.md)
- ✅ [Track D — bounded failure history](TRACK-D-bounded-failure-history.md)
- ✅ [Track E — normalization diagnostics + deterministic selection](TRACK-E-normalization-diagnostics.md)
- ✅ [Track F — Dependency Review CI fix](TRACK-F-dependency-review-ci.md)
- ✅ [Track G — acknowledgement-backed daemon health composition](TRACK-G-daemon-health-composition.md)
- ⬜ [Track H — settings persistence + DesiredProxyState source](TRACK-H-settings-and-desired-state.md)
- ⬜ [Track I — real foreground ProxyService + manifest](TRACK-I-foreground-service.md)
- ⬜ [Track J — Compose UI tab + toggle + live state](TRACK-J-compose-ui.md)
- ⬜ [Track K — concrete root-process RPC transport](TRACK-K-root-rpc-transport.md)

## Definition of done for Phase 0 sign-off

Tracks A–G controller/model/protocol/composition work is complete. Tracks H–K add the
app-integration layer (settings, foreground service, UI, root transport) that surfaces the
feature on-device. The PR must remain draft until H–K, the native backend integration and
the remaining device-evidence rows are complete.
