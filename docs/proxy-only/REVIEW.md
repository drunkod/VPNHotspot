# Structural review — proxy-only design (`agent/proxy-only-design`)

Reviewed: ARCHITECTURE.md, README.md, IMPLEMENTATION_PLAN.md, RESEARCH_DECISIONS.md,
CODE_SKETCHES.md, TEST_PLAN.md, cross-checked against the codebase at `86fedc89`.

## Verdict

The design is well-structured: fail-closed by default, deny-first firewall ordering,
serialized reconciliation, a backend abstraction that keeps Hev replaceable, and honest
open questions. It is approvable in direction. However, it contains one unverified
feasibility assumption that belongs in Phase 0, one incorrect claim about codebase
behaviour, one wrong entry in the failure matrix, an IPv6 policy gap, and several
internal inconsistencies between documents.

---

## 1. Blocking findings

### 1.1 `Upstreams.primary` is not guaranteed to be a VPN network

The design states throughout that `Upstreams.primary` is the VPN
(`ARCHITECTURE.md §1`, `RESEARCH_DECISIONS.md Decision 5`). In the actual code
(`Upstreams.kt:98–103`), `primary` is:

```kotlin
val primary = role(KEY_PRIMARY, vpn)   // preferenceFlow(KEY_PRIMARY).flatMapLatest {
                                       //   if (empty) vpn else iface(upstreamRegex) }
```

If the user has set the `service.upstream` preference to an interface regex (e.g.
`wlan0`), `primary` emits a **physical** network. A proxy pinning its sockets to
`Upstreams.primary.network` would then advertise "fail-closed VPN path" while sending
traffic over the carrier network — silently defeating the feature's entire security
contract.

**Fix:** the proxy controller must either consume `Upstreams.vpn` directly, or validate
`NetworkCapabilities.TRANSPORT_VPN` on the bound network and refuse (fail-closed) when
the selected upstream is not a VPN. Add this to the security properties in TEST_PLAN §1
and to the Kotlin unit tests.

Note: the existing routing path (`Routing.kt` L119, L312) also consumes
`Upstreams.primary` and forwards `networkHandle` to the daemon unvalidated. That is
acceptable there — the user explicitly chose the upstream and there is no fail-closed
promise. The proxy mode makes a stronger claim ("traffic exits via the VPN"), so it
cannot inherit the same permissiveness.

### 1.2 Per-app VPN policy can make `android_setsocknetwork()` fail — untested in Phase 0

Binding a socket to a `Network` requires the calling UID to be permitted to use that
network. If the user's VPN app is configured with an allowed-apps list that excludes
VPN Hotspot (or a disallowed-apps list that includes it — plausible, since users may
exclude it to avoid routing loops in the existing mode), `android_setsocknetwork()` /
`Network.bindSocket()` fails with EPERM.

The Phase 0 spike tests binding mechanics but not this **configuration dimension**.
Add to Phase 0 exit criteria: binding succeeds/fails as expected under (a) VPN applied
to all apps, (b) VPN Hotspot excluded, (c) VPN Hotspot explicitly included. Define the
user-visible error for case (b) — this will be a real support issue.

### 1.3 Failure matrix: "Root daemon killed → access defaults to denied" is wrong

iptables rules persist in the kernel after the daemon process dies. If the daemon is
killed while per-client **allow** rules are committed, those rules survive: clients keep
proxy access with no ACL reconciliation, no counters, and no block-list enforcement.
Access does not "default to denied".

**Fix:** the app must monitor daemon liveness (the existing long-lived call/channel
presumably breaks on death) and treat daemon loss as a stop event: close the native
listener immediately, then re-establish deny state when the daemon returns. Update
TEST_PLAN §6 accordingly and add an integration test that kills `vpnhotspotd` mid-session.

---

## 2. High-priority gaps

### 2.1 IPv6 is unhandled in the firewall model

`CODE_SKETCHES.md §8` matches "source IPv4"; `ARCHITECTURE.md §9` binds to "discovered
downstream IPv4 addresses"; the proto has no address-family field. But Hev supports
IPv6, tethered clients receive IPv6 addresses (the daemon even ships a full `nat66`
module), and `IptablesTarget::Ipv6` already exists in `firewall.rs`. A wildcard-bound
listener with only IPv4 rules is reachable over IPv6 with **no firewall policy at all**.

**Fix:** explicitly choose for the MVP: either (a) install ip6tables deny rules for the
proxy ports and bind IPv4-only, or (b) mirror the full rule set on both families. Option
(a) is the smaller change; either way it must be stated, implemented, and covered by the
interface-exposure tests (which currently don't mention address family).

### 2.2 ACL matches IP only; identity is MAC

Decision 4 / Phase 6 say "MAC is the public identity, IP is an implementation selector",
but the firewall pseudo-policy matches downstream interface + source IPv4 only. DHCP
reassignment or deliberate static-IP reuse lets one client inherit another's allow rule
(and its counters). iptables supports `-m mac --mac-source`; the neighbour monitor
already provides MAC↔IP mappings.

**Fix:** match iface + source IP + source MAC for allow rules on interfaces where MAC is
visible (Wi-Fi; USB/RNDIS has a peer MAC too). Document that IP-only matching is a
spoofing surface if MAC matching is dropped anywhere.

### 2.3 UDP relay ports vs. single `udp_port`

`ARCHITECTURE.md §7` mentions "listener UDP port or UDP port range", but
`ProxyFirewallConfig` has a single `uint32 udp_port`, and `ProxyOnlySettings` a single
`port`. Standard `UDP ASSOCIATE` servers commonly allocate **ephemeral per-association
relay ports** and return them in the reply. If Hev does this, the firewall must allow a
configured relay port range (and Hev must be constrained to it) or the UDP path dies at
the firewall.

**Fix:** resolve in Phase 0 against the pinned Hev source; make the proto carry
`udp_port_range_start/end` if needed. This is exactly the kind of mismatch that
surfaces late and forces a proto change mid-implementation.

### 2.4 Reconciler sketch is not cancellation-safe

`CODE_SKETCHES.md §2` uses `collectLatest { mutex.withLock { reconcile(desired) } }`.
`collectLatest` **cancels** the running block on a new emission. Cancellation between
`firewall.startDenied(...)` and `current = AppliedProxyState(...)` leaks the firewall
runtime (deny rules stay installed, `current` never records the id); cancellation inside
`stopCurrent()` can leave the native listener running with firewall state removed.

**Fix:** run reconciliation in a dedicated worker consuming a conflated channel of
desired states (emission never cancels an in-flight apply), or wrap commit/rollback
sections in `withContext(NonCancellable)`. Also move the `generation`/`previousNetwork`
mutation out of the `combine` transform — side effects in flow operators re-execute
unpredictably; derive generation inside the serialized worker.

Additionally, the state machine (`Running → Stopping → Starting` on generation change)
and the JNI surface (`updateNetwork`/`replaceNetwork` on a live instance) describe two
different mechanisms for the same event. Pick one for the MVP — full restart is simpler,
matches the state machine, and makes "close all old-generation sessions" trivially true.
Keep `updateNetwork` as a later optimization behind the `ProxyBackend` interface.

---

## 3. Medium-priority issues

**3.1 Global `SharingMode` vs per-downstream flags.** Phase 2 defines a single global
enum; Phase 5 defines `ManagedDownstream(vpnRoutingEnabled, proxyExposureEnabled)` per
interface. These are different products (all-or-nothing vs per-interface mixing). Decide
now: recommend global mode for MVP UI, but with the per-downstream data model underneath
so the setting can become per-interface without migration.

**3.2 Blocking DNS inside the Hev event loop.** Hev runs on hev-task-system coroutines.
Both proposed resolvers (`android_getaddrinfofornetwork()`, JNI → `Network.getAllByName()`)
are blocking calls; invoked from a task, they stall every session on that worker
(head-of-line blocking, seconds per timeout). The plan flags JVM-attach complexity but
not this. **Fix:** require the Phase 0 spike to measure resolver behaviour under a
blackholed DNS server; plan for a dedicated resolver thread pool or `android_res_nsend`
(async, network-aware, API 29+) rather than assuming the simple call is safe.

**3.3 Foreground-service type is a gating requirement, not a footnote.** Android 14+
requires a declared FGS type with a policy-compliant justification; `specialUse` needs
Play review. This can gate release regardless of code quality. Promote open question 7
to a Phase 2 task with an actual answer (likely `connectedDevice` — verify eligibility).

**3.4 `VPN_ROUTING_AND_PROXY` is designed but never tested.** TEST_PLAN covers baseline,
proxy-only, and the existing mode; no test exercises the combined mode (interaction of
RoutingManager rules with proxy firewall chains on the same interface is exactly where
rule-ordering bugs live). Either add a test section or cut the mode from the MVP —
README's own rationale for it ("useful for compatibility testing") is weak.

**3.5 Native fork CI story is missing.** The Hev-fork unit tests (hook coverage, retry
paths, IPv4/IPv6 fallback) are listed but nothing says where they run.
`android_setsocknetwork` doesn't exist on a Linux CI host. Specify a host build with a
shim (`vpnhotspot_prepare_outbound_socket` behind a testable seam — the hook design
already permits this) so the "every socket path invokes the hook" property is enforced
by CI, not by a manual audit checklist that rots as the fork rebases.

**3.6 `distinctUntilChanged` on `DesiredProxyState` / `runtimeKey()` underspecified.**
Client-list ordering or `LinkProperties` churn must not restart the listener. Define
`runtimeKey` precisely (port, credentials version, network handle, downstream set as a
sorted set) and make client changes a `replace`, never a restart — §2's sketch gestures
at this but the key derivation is the part that will be gotten wrong.

---

## 4. What is sound (keep as-is)

- **Fail-closed as default with explicit-opt-in fallback** (Decision 5) — correct call.
- **Deny-first startup / deny-before-teardown ordering** (ARCHITECTURE §10) — the
  open-proxy-window analysis is right.
- **Data plane in app process, policy in root daemon** (Decision 3/4) — correct
  privilege separation; keeps the fork off UID 0.
- **`ProxyBackend` abstraction** (Decision 10) — cheap insurance given Phase 0 has a
  real chance of rejecting Hev; the Kotlin/Rust fallbacks are pre-identified.
- **Explicit SOCKS5 over TPROXY** (Decision 1) — right scope for the FlClash use case.
- **Independent proxy-firewall proto lifecycle** rather than overloading
  `SessionConfig` — matches existing `daemon.proto` conventions (commands 9/10 are free,
  `CancelCommand` semantics reusable). One concretization: `proxy_firewall/` should
  reuse the existing `IptablesRule` ledger machinery
  (`routing/iptables.rs` — `-I`/`delete_repeated` idempotent mutations) and hook into
  `routing/firewall_cleanup.rs::clean()` rather than introducing a parallel rule
  representation; the sketches currently leave this open.
- **Phased PR sequence with a kill-switch spike** — Phase 0 exit criteria are concrete;
  the "stop before UI/daemon work" gate is the most valuable line in the plan.
- **Threat model coverage** (open proxy, VPN bypass, DNS leak, UDP hijack, exhaustion)
  is unusually complete for a design at this stage.

## 5. Recommended amendments before implementation

1. Phase 0 additions: VPN-capability validation of the upstream (1.1), per-app VPN
   binding matrix (1.2), Hev UDP relay-port behaviour (2.3), resolver blocking
   measurement (3.2).
2. Correct the failure matrix for daemon death (1.3) and add the kill-daemon
   integration test.
3. Add an explicit IPv6 policy section to ARCHITECTURE §7 and TEST_PLAN §4 (2.1).
4. Firewall rules: iface + IP + MAC matching (2.2).
5. Replace `collectLatest`+mutex with a non-cancellable serialized apply loop; choose
   restart-on-generation-change for MVP (2.4).
6. Resolve global-vs-per-downstream mode ambiguity (3.1); cut or test
   `VPN_ROUTING_AND_PROXY` (3.4).
7. Answer the FGS-type question in Phase 2, not at release (3.3).
8. Specify host-CI shim for the Hev fork tests (3.5).
