# Proxy-only implementation plan

This plan covers the selective SOCKS5 design only. Ordinary Android system tethering remains the direct path; proxy traffic is bound to an existing Android VPN `Network`.

No UI, production firewall integration or release code may proceed until the Phase 0 exit criteria pass.

## Global invariants

Every implementation phase preserves these rules:

1. `Upstreams.primary` is not accepted as VPN proof.
2. Exactly one current usable `TRANSPORT_VPN` network is required.
3. The app UID must be allowed to bind to that VPN.
4. No Internet-facing socket sends before network binding succeeds.
5. No direct fallback exists in MVP.
6. `ProxyService` is the sole backend/native owner.
7. Persisted enable state is not a foreground-service activation grant.
8. No runtime starts while itemized cleanup debt remains.
9. Every cleanup item is cleared only after that exact item succeeds.
10. Cleanup debt retries without requiring external state changes.
11. Probe failures are typed and configuration-aware.
12. Firewall deny is explicit for IPv4 and IPv6.
13. The root daemon reuses `IptablesRule` and deterministic cleanup.
14. IPv4 client access requires interface + IPv4 + MAC.
15. TPROXY and mixed routing+proxy mode are out of scope.

# Phase 0 — feasibility and security spike

## Objective

Prove that a pinned HevSocks5Server fork can provide fail-closed TCP/UDP SOCKS5 egress through an Android VPN without DNS leakage, hidden physical fallback, unsafe UDP firewall assumptions or lifecycle ambiguity.

## 0.1 Pin and audit Hev

1. Pin an exact Hev commit and recursive dependency state.
2. Record licenses and required notices.
3. Build host-test and Android variants for all VPN Hotspot ABIs.
4. Enumerate every Internet-facing socket creation path:
   - TCP connect;
   - UDP association/relay;
   - retry/fallback;
   - DNS/resolver;
   - address-family fallback.
5. Add one injectable socket-prepare seam.
6. Make hook failure fatal in fail-closed mode.
7. Add host CI proving the hook runs before every packet-producing operation.

## 0.2 VPN selection and permission matrix

Implement a proof-of-concept selector that:

- uses VPN-specific candidates;
- queries fresh capabilities;
- requires `TRANSPORT_VPN`;
- fails closed on zero candidates;
- fails closed on multiple candidates;
- does not sort transient handles as policy.

Test:

- VPN applies to all apps;
- VPN Hotspot explicitly allowed;
- VPN Hotspot excluded/denied;
- policy changes while running;
- always-on/lockdown where available;
- physical `service.upstream` override.

Expected exclusion result: typed `VpnPermissionDenied`, no listener exposure and no physical fallback.

## 0.3 Activation-grant proof

Model:

```kotlin
data class ActivationGrant(
    val id: UUID,
    val issuedAtElapsedRealtime: Long,
    val source: ActivationSource,
)
```

Prove:

- foreground user enable issues a grant;
- activation consumes it only after successful FGS start;
- persisted `enabled=true` after process restart does not start the FGS;
- no grant produces `ActivationRequired`;
- pre-activation wait/stop/emergency commands return no-op reports;
- background dependency recovery uses an already-active service;
- `ForegroundServiceStartNotAllowedException` is mapped to actionable activation state.

## 0.4 Typed outbound probes

Implement typed probe results for:

- app-UID bind;
- outbound TCP;
- outbound UDP;
- VPN-aware DNS;
- internal listener readiness.

Required set:

```text
always: bind, TCP, DNS, listener readiness
UDP enabled: plus UDP
```

Map outcomes explicitly:

- bind permission error -> `VpnPermissionDenied`;
- TCP failure -> `TcpProbeFailed`;
- DNS failure -> `DnsProbeFailed`;
- UDP failure when enabled -> `UdpProbeFailed`;
- listener failure -> `ListenerNotReady`.

Do not use `check(allRequiredPassed)` as the diagnostic path.

## 0.5 UDP topology discovery

Instrument the pinned Hev build to correlate:

- control TCP FD;
- returned `BND.ADDR/BND.PORT`;
- client-facing relay FD/port;
- Internet-facing FD/port;
- shared versus separate socket behavior;
- network-binding hook calls;
- packet ingress/egress interfaces;
- remote-reply conntrack state;
- association lifetime;
- range exhaustion.

Produce a machine-readable `UdpTopologyReport` and packet-capture notes.

The firewall return policy is not approved until replies work without a broad VPN-interface allow.

## 0.6 DNS behavior

Test domain-form requests with:

- working VPN DNS;
- blackholed VPN DNS;
- VPN loss during resolution;
- concurrent resolutions;
- process-default DNS capture.

Choose either:

- bounded network-aware resolver workers; or
- asynchronous Android network-aware resolver integration.

Unbounded synchronous resolver calls on Hev workers are rejected.

## 0.7 Cleanup-debt prototype

Implement a fake controller/service/firewall model with itemized debt:

```kotlin
data class CleanupDebt(
    val listenerClosePending: Boolean,
    val serviceHandlePending: ProxyServiceHandle?,
    val firewallHandlePending: ProxyFirewallHandle?,
    val firewallDenyPending: Boolean,
    val firewallStopPending: Boolean,
    val daemonCleanPending: Boolean,
    val featureStopPending: Boolean,
    val failures: List<CleanupFailure>,
    val generation: Long,
    val attempt: Int,
)
```

Prove:

- failed `firewall.stop` with healthy daemon retains the firewall handle;
- retry calls `denyAll` and `stop` again;
- emergency close alone cannot clear firewall debt;
- partial success clears only successful items;
- debt clears only when all items resolve;
- no backend start occurs while debt remains;
- idle state with no applied resources creates no emergency-close debt;
- feature stop has one owner.

## 0.8 Internal debt retry scheduler

Add a controller event:

```kotlin
RetryCleanupDebt(generation)
```

Requirements:

- unresolved debt schedules bounded exponential backoff with jitter;
- only one retry job is active;
- stale generations are ignored;
- latest desired snapshot is retained;
- debt can clear with no external state emission;
- successful cleanup resets backoff;
- user disable does not cancel required safety cleanup.

## 0.9 IPv6 and firewall baseline

Prove:

- listener and relays are IPv4-only;
- IPv6 scans cannot reach TCP or UDP range;
- dual-stack wildcard behavior is absent;
- explicit `deny_all_ipv4` and `deny_all_ipv6` exist in the protocol;
- an empty ACL is not the deny-state signal.

## Phase 0 exit criteria

All must pass:

- exact Hev pin and maintainable fork delta;
- complete per-FD hook coverage in host CI;
- real Android binding through the selected VPN;
- zero/one/multiple VPN behavior;
- per-app VPN include/exclude behavior;
- activation-grant and process-restart behavior;
- typed, config-aware TCP/UDP/DNS/readiness probes;
- UDP topology and safe firewall return-policy evidence;
- bounded VPN-aware DNS under blackhole;
- explicit IPv4/IPv6 denial;
- itemized cleanup debt and partial resolution;
- self-triggered backoff retry with quiescent external state;
- no restart over unresolved debt;
- repeated start/stop without FD/thread leaks.

If any security criterion fails, stop and evaluate a Kotlin or Rust backend behind the same interfaces.

# Phase 1 — dependency and native build integration

Proposed layout:

```text
external/hev-socks5-server/
mobile/src/main/cpp/proxy/
mobile/src/main/java/be/mygod/vpnhotspot/proxy/
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/
```

Tasks:

- reproducible source pin;
- license notices;
- Android ABIs;
- host-native hook tests;
- JNI symbol boundary;
- R8 rules for JNI entry points only;
- APK/AAB size and duplicate-symbol inspection;
- sanitizer configuration where practical.

Validation:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run host-native fork tests and Rust daemon tests.

# Phase 2 — domain model and foreground-service gate

## Models

Add:

```text
ProxyOnlySettings.kt
ProxyOnlyState.kt
ProxyActivationGrant.kt
ProxyEndpoint.kt
ProxyCredentials.kt
ProxyVpnSelector.kt
ProbeReport.kt
CleanupDebt.kt
```

State model includes:

```text
Disabled
ActivationRequired
ServiceStarting
WaitingForTethering
WaitingForVpn
MultipleVpnCandidates
VpnPermissionDenied
StartingBackend
Running
FailClosed(TypedReason)
CleanupDegraded
```

## Settings

- enabled flag;
- TCP port;
- UDP enabled;
- UDP relay range;
- maximum UDP associations;
- credentials version;
- encrypted credentials.

Fail-closed is mandatory and not exposed as a toggle.

## FGS policy gate

Before manifest integration:

1. verify current target SDK;
2. choose valid service type and permissions;
3. verify distribution-policy eligibility;
4. define foreground user activation action;
5. define grant lifetime/consumption;
6. add manifest/lint tests;
7. map foreground-start denial to `ActivationRequired` or an actionable activation error.

# Phase 3 — controller event loop

Add:

```text
ProxyOnlyController.kt
ProxyDesiredState.kt
ProxyControllerEvent.kt
ProxyRetryScheduler.kt
ProxyServiceClient.kt
ProxyFirewallClient.kt
```

## Inputs

- settings and credentials version;
- optional activation grant;
- VPN candidates/capabilities;
- tethering interface/IPv4 state;
- MAC↔IPv4 neighbors;
- blocked clients;
- daemon channel health;
- service activation status.

## Event model

```kotlin
sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}
```

One conflated channel and one worker.

## Worker requirements

- disabled check before service activation;
- activation requires a valid grant;
- parent cancellation rethrown;
- timeout enters fail-closed recovery;
- every iteration has exception containment;
- publication/reporting failures contained;
- typed probe failures are normal state transitions;
- terminal `finally` cleanup;
- explicit debt branch with publish/schedule/return;
- no exception-based cleanup gate.

# Phase 4 — ProxyService and backend

Files:

```text
ProxyService.kt
ProxyServiceClient.kt
ProxyNotification.kt
ProxyBackend.kt
HevProxyBackend.kt
ProxyNative.kt
```

`ProxyService` owns:

- foreground lifecycle;
- activation state;
- backend instance;
- native handles;
- listener/sessions;
- backend statistics;
- emergency close.

Client API:

```kotlin
interface ProxyServiceClient {
    suspend fun activateFeature(grant: ActivationGrant, initial: ProxyOnlyState): ServiceActivation
    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyServiceHandle, requirements: ProbeRequirements): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
    suspend fun stopFeature(reason: String): CleanupReport
}
```

Pre-activation wait/stop/emergency methods return no-op reports.

There is no live network replacement in MVP.

# Phase 5 — system tethering integration

Pure `PROXY_ONLY` downstreams do not create `RoutingManager`.

Initial support:

- system Wi-Fi tethering;
- USB tethering when reliable MAC identity exists;
- Ethernet only after equivalent tests.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth;
- downstreams without reliable MAC identity.

# Phase 6 — root proxy firewall

Add an independent long-lived proxy-firewall command/runtime.

Proto must include explicit deny flags:

```proto
message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool deny_all_ipv4 = 7;
  bool deny_all_ipv6 = 8;
  optional VerifiedUdpReturnPolicy udp_return_policy = 9;
}
```

Mandatory implementation rules:

- reuse `IptablesRule`/`IptablesChain`;
- use applied ledger and `delete_repeated()`;
- integrate proxy chains into `firewall_cleanup::clean()`;
- IPv4 allow requires interface + IP + MAC;
- IPv4 ends in reject;
- IPv6 rejects entire listener/range;
- no broad VPN-interface allow;
- return policy absent until Phase 0 evidence exists.

Daemon death:

- channel loss triggers immediate listener close;
- firewall handle and Clean become cleanup debt;
- daemon recovery resolves debt before restart.

# Phase 7 — UI and diagnostics

Show:

- sharing mode;
- `ActivationRequired` resume action;
- current typed state/reason;
- proxy endpoint and UDP range;
- effective UDP capacity;
- credential reveal/regeneration;
- selected VPN status;
- per-app VPN denial guidance;
- daemon/cleanup debt state;
- debt retry attempt and next delay;
- unresolved cleanup resources without exposing sensitive handles;
- active TCP/UDP counts;
- per-client counters;
- generated FlClash snippet.

Do not publish IPv6 endpoints.

# Phase 8 — integration and failure injection

Validate:

- `DIRECT` physical path;
- `PhoneVPN` VPN exit;
- optional WARP egress;
- activation-required after process restart;
- user resume grant activation;
- VPN include/exclude matrix;
- typed TCP/UDP/DNS probe states;
- UDP disabled does not require UDP probe;
- DNS blackhole;
- VPN generation restart;
- daemon kill/recovery;
- cleanup debt partial resolution;
- healthy-daemon `firewall.stop` failure/retry;
- debt retry without external state change;
- no idle emergency-close debt;
- explicit IPv4/IPv6 deny;
- MAC/IP spoof denial.

# Phase 9 — hardening and rollout

- connection/FD limits;
- handshake/DNS/idle timeouts;
- UDP capacity/range metrics;
- fuzz SOCKS handshake and UDP headers;
- native sanitizers;
- reproducible fork rebase process;
- throughput/latency/CPU/memory/battery measurements;
- experimental feature flag;
- support documentation;
- third-party notices.

# Suggested PR sequence

1. Final ADR/reviews and exact Hev pin.
2. Host-testable socket hook and Android binding spike.
3. VPN selector plus app-policy matrix.
4. ActivationGrant, ActivationRequired and FGS policy spike.
5. Typed/config-aware probes.
6. UDP topology instrumentation and report.
7. Controller event loop plus itemized debt/retry scheduler using fakes.
8. App-process TCP backend.
9. UDP range/capacity and VPN-aware DNS.
10. Root firewall lifecycle reusing `IptablesRule` and explicit deny flags.
11. MAC+IP ACL and counters.
12. System-tethering integration.
13. UI and FlClash generation.
14. Failure injection, device matrix and hardening.

Each PR must build independently and avoid unrelated routing refactors.
