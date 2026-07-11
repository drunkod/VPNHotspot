# Proxy-only implementation plan

This plan is limited to the selective SOCKS5 design described in this directory. The feature keeps ordinary Android system tethering as the direct path and exposes an authenticated proxy whose outbound operations use a validated Android VPN `Network`.

No UI, production daemon command or release code may proceed until the Phase 0 exit criteria pass.

## Non-negotiable invariants

1. Proxy-only never treats `Upstreams.primary` as proof of VPN transport.
2. Exactly one usable `TRANSPORT_VPN` candidate must exist.
3. Every Internet-facing TCP, UDP and resolver socket is bound to that VPN before packet-producing use.
4. VPN/app-policy/binding failure never falls back to the physical network.
5. `ProxyService` is the sole `ProxyBackend` and native-handle owner.
6. The listener is never reachable without a live root firewall runtime.
7. Daemon loss closes the listener even though kernel allow rules may remain.
8. IPv4 allow requires downstream interface + source IPv4 + source MAC.
9. The MVP listener/relay is IPv4-only; proxy ports are denied over IPv6.
10. Desired-state reconciliation is serialized, exception-safe, terminal-safe and restart-blocked while cleanup debt exists.
11. Startup probes are outbound-only until client allow rules are committed.
12. TPROXY, mixed routing+proxy mode and direct fallback are outside the MVP.

---

## Phase 0 — feasibility and security spike

### Goal

Prove that a pinned HevSocks5Server revision can satisfy the invariants with a small maintainable fork. Phase 0 produces test code, measurements and an architecture decision; it does not produce release UI or production firewall integration.

### 0.1 Pin and audit Hev

- Pin exact HevSocks5Server and recursive dependency commits.
- Record license and attribution obligations.
- Build Linux host-test and Android variants for all VPN Hotspot ABIs.
- Enumerate every outbound TCP, UDP and resolver FD creation path, including retries and address-family fallback.
- Add one injected `prepare_outbound_socket` seam.
- Make prepare-hook failure fatal in fail-closed mode.
- Verify hook ordering: `socket()` → prepare/bind → `connect()` or first `sendto()`.

### 0.2 VPN candidate selection

- Use the VPN-specific candidate flow or enumerate networks with fresh capabilities.
- Reject physical/stale candidates.
- Fail closed when zero usable VPNs exist.
- Fail closed with `MultipleVpnCandidates` when more than one usable VPN exists.
- Do not sort transient network handles to infer user intent.
- Re-read `NetworkCapabilities` immediately before startup and before `Running`.

### 0.3 Per-app VPN policy matrix

Test binding and outbound traffic when:

- the VPN applies to all apps;
- VPN Hotspot is explicitly included;
- VPN Hotspot is excluded/denied;
- the policy changes while running;
- always-on/lockdown is enabled where available.

Expected exclusion behavior: stable `VpnPermissionDenied`, no listener exposure and no physical fallback.

### 0.4 UDP socket-topology discovery

Instrument and correlate:

- SOCKS control TCP FD;
- client-facing relay FD and returned `BND.ADDR/BND.PORT`;
- Internet-facing UDP FD;
- shared versus separate relay/upstream sockets;
- every prepare-hook call;
- local address/port for each FD;
- client packet ingress interface;
- remote-reply ingress interface;
- conntrack state of replies;
- behavior for multiple simultaneous associations.

Use packet capture and debug-only native events. Do not finalize the UDP INPUT rule until remote replies work with a narrowly evidenced rule. A broad VPN-interface allow is prohibited.

### 0.5 UDP range and capacity

Determine whether Hev supports:

- one fixed relay port;
- a bounded configured port range;
- one relay port per association;
- a shared relay port for many associations.

Unless one fixed/shared port is proven, the cross-process contract carries `udp_port_range_start/end`.

If a port is consumed per association:

```text
effectiveUdpCapacity = min(configuredAssociationLimit, usableRelayPortCount)
```

Range exhaustion returns a controlled failure and metric. The range never expands dynamically.

### 0.6 DNS architecture

Measure domain requests with:

- working VPN DNS;
- blackholed VPN DNS;
- many concurrent requests;
- VPN loss during resolution;
- network-generation replacement.

Select either:

- bounded dedicated resolver workers using a VPN-aware API; or
- asynchronous Android network-aware resolver integration.

The process-default resolver is forbidden. A blackholed resolver must not stall unrelated sessions.

### 0.7 Address-family proof

- Bind the MVP listener and relay explicitly as IPv4-only.
- Verify no unintended dual-stack wildcard listener exists.
- Verify native IPv6 and IPv4-mapped access cannot reach the proxy.
- Prepare explicit ip6tables denial for TCP port and complete UDP relay range.

### 0.8 Host CI seam

Linux host CI injects a fake network-binding function that records:

- FD;
- family/type;
- path/role;
- ordering;
- configured failure.

CI proves every outbound socket path invokes the hook before traffic and closes the FD on failure. Android instrumentation separately verifies real `android_setsocknetwork()` behavior.

### Phase 0 exit criteria

All must pass:

- exact Hev pin and small maintainable delta;
- complete host-tested prepare-hook coverage;
- TCP through the phone VPN;
- standard UDP ASSOCIATE through the phone VPN;
- documented UDP topology and safe return-rule design;
- bounded UDP range/capacity behavior;
- VPN-aware DNS without global worker stalls;
- zero/one/multiple VPN selection behavior;
- per-app VPN include/exclude matrix;
- invalid/stale/non-VPN handles fail closed;
- IPv6 cannot reach the listener/range;
- repeated start/stop leaks no FD/thread;
- VPN generation replacement requires full backend restart;
- no physical-network packet appears during failure tests.

If any security criterion fails, stop and evaluate a Kotlin or Rust backend behind the same service/controller interfaces.

---

## Phase 1 — dependency and build integration

### Layout

```text
external/hev-socks5-server/
mobile/src/main/cpp/proxy/
mobile/src/main/java/be/mygod/vpnhotspot/proxy/
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/
```

### Requirements

- reproducible source pin in local, CI and source archives;
- MIT notices included in app OSS notices;
- native library built for every supported ABI;
- Hev internals hidden behind `ProxyBackend`/JNI;
- host-native test target retained;
- APK/AAB ABI, symbols, duplicate libraries and size inspected;
- current Gradle/Rust checks remain green.

---

## Phase 2 — settings, state and foreground-service gate

### Settings

```kotlin
data class ProxyOnlySettings(
    val enabled: Boolean,
    val tcpPort: Int,
    val udpEnabled: Boolean,
    val udpPortRange: IntRange,
    val maxUdpAssociations: Int,
    val credentialsVersion: Long,
    val username: String,
    val password: String,
)
```

Requirements:

- random generated credentials;
- credential-encrypted storage and backup exclusion;
- validated TCP port, UDP range and conflicts;
- effective UDP capacity shown in diagnostics;
- no secrets in logs, `toString()`, implicit intents or routine notification;
- existing installations remain `VPN_ROUTING`.

### States

At minimum:

```text
Disabled
ServiceStarting
WaitingForTethering
WaitingForVpn
MultipleVpnCandidates
VpnPermissionDenied
StartingBackend
Running
FailClosed
CleanupDegraded
Stopping
```

### Foreground-service type and start context

Before merging the manifest change:

1. verify current target SDK;
2. select a valid FGS type and permissions;
3. verify distribution/store policy eligibility;
4. document the exact user action that starts the service;
5. add manifest/lint tests;
6. map `ForegroundServiceStartNotAllowedException` to an actionable error.

A disabled snapshot must never start the FGS. The first start occurs only after a user-approved enable action.

Once enabled and started, the FGS remains alive—without a listener—in waiting/fail-closed states. Automatic process-death resurrection is not assumed.

---

## Phase 3 — service ownership and command contract

### Ownership

```text
ProxyOnlyController
  -> ProxyServiceClient
      -> ProxyService
          -> ProxyBackend / native handles

ProxyOnlyController
  -> ProxyFirewallClient
      -> vpnhotspotd
```

The controller never holds `ProxyBackend` or native handles.

### Service commands

```kotlin
interface ProxyServiceClient {
    suspend fun activateFeature(initialState: ProxyOnlyState): ServiceActivation
    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyServiceHandle): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
    suspend fun stopFeature(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}
```

Semantics:

- `activateFeature` is called only from the approved enable path.
- `enterWaiting` closes backend/listener, retains FGS and updates notification.
- `stopFeature` closes backend/listener and terminates the FGS.
- backend commands are serialized inside the service.
- stop/emergency commands are idempotent and return structured results.

---

## Phase 4 — exception-safe desired-state worker

### Input

Immutable normalized snapshots include:

- settings and credential version;
- zero/one/multiple usable VPN result;
- tethered interface/IPv4 set;
- neighbour MAC↔IPv4 state;
- blocked clients;
- daemon/firewall channel health;
- service activation state;
- outstanding cleanup debt.

One `Channel.CONFLATED` feeds one worker. No resource operation uses `collectLatest`.

### Activation ordering

1. If settings are disabled, run terminal cleanup and `stopFeature`; do not call service activation.
2. If enabled but the service has not been user-started, request/await the approved activation result.
3. Waiting/fail-closed snapshots call `enterWaiting(state)` so the FGS notification and listener state match the controller state.
4. Running startup proceeds only when no cleanup debt exists.

### Exception and cancellation contract

Each iteration:

- runs resource commit/rollback in bounded `NonCancellable` sections;
- catches `TimeoutCancellationException` as an operation failure;
- rethrows parent `CancellationException` so terminal `finally` executes;
- catches other unexpected exceptions and enters fail-closed recovery;
- contains state-publication errors without killing the resource worker.

Worker `finally` performs terminal cleanup in `NonCancellable` context and attempts service emergency closure even if normal backend stop fails.

### Cleanup algorithm

Attempt independently, with per-step timeout:

1. firewall deny when daemon is available;
2. service backend stop;
3. service emergency listener close if stop failed or ownership is uncertain;
4. firewall runtime stop;
5. service waiting transition or terminal feature stop;
6. error/state publication.

Every failure is recorded with step, handle/generation context and cause. No silent `runCatching`.

### Cleanup debt

Failed cleanup creates structured debt containing unresolved service/firewall handles and required recovery actions.

While debt exists:

- no backend start is allowed;
- service remains listener-free/fail-closed;
- emergency close is retried;
- daemon recovery runs Clean or explicit deny before normal start;
- successful recovery clears debt;
- UI/notification reports degraded cleanup.

### Runtime key

Restart backend only when these change:

- TCP port;
- UDP enabled/range;
- credentials version;
- validated VPN network handle;
- sorted downstream interface/address set;
- backend configuration version.

Client ordering and block-list changes perform firewall/service ACL replacement only. Fast-path replacement exceptions use the same fail-closed recovery path.

---

## Phase 5 — backend, probes and DNS

### Backend contract

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyBackendHandle): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

No live network replacement in MVP. Network-handle change performs deny → close → stop → validate → recreate.

### Outbound-only probes

Before ingress allows:

- app-UID network bind;
- backend-created outbound TCP;
- backend-created outbound UDP;
- VPN-aware DNS;
- internal native bind/listen readiness.

The pre-allow probe never connects to the SOCKS listener through a tethered interface. External reachability is tested after allow commit.

### UDP

- RFC 1928 UDP ASSOCIATE;
- control TCP owns association lifetime;
- `FRAG != 0` rejected;
- authenticated/control peer source enforced;
- relay range constrained;
- effective capacity enforced;
- all Internet-facing FDs pass network hook;
- range exhaustion is controlled and measured.

---

## Phase 6 — system tethering integration

In `PROXY_ONLY`, do not instantiate the existing VPN-forwarding `RoutingManager` for that downstream.

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Initial support:

- system Wi-Fi tethering;
- USB tethering where MAC identity is reliable;
- Ethernet only after equivalent testing.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth;
- interfaces without reliable MAC+IPv4 identity.

---

## Phase 7 — root proxy firewall

### Lifecycle

Use an independent long-lived daemon command; do not extend `SessionConfig`.

### Mandatory reuse

- promote/reuse `routing/iptables.rs::IptablesRule` and `IptablesChain` as needed;
- use the existing applied ledger and `delete_repeated()` semantics;
- add proxy jumps/chains to `routing/firewall_cleanup.rs::clean()`;
- use both IPv4 and IPv6 targets;
- keep cleanup reconstructable without private app state.

### Policy

IPv4 client allow requires:

```text
input interface + source IPv4 + source MAC + destination port/range
```

IPv4 ends in reject. IPv6 rejects TCP port and complete UDP range.

Any UDP return-traffic rule remains provisional until Phase 0 topology evidence defines exact interface, state and ordering. No broad VPN-interface allow.

### Daemon death

Unexpected completion of the firewall command/channel:

1. triggers service emergency listener close immediately;
2. enters foreground fail-closed state;
3. records cleanup debt because stale kernel rules may remain;
4. requires Clean/deny reconciliation before backend restart.

---

## Phase 8 — UI and support diagnostics

Show:

- sharing mode;
- FGS/service state;
- waiting/fail-closed reason;
- cleanup-degraded state and recovery status;
- IPv4 endpoint and TCP port;
- UDP range and effective capacity;
- credentials with controlled reveal/copy/regenerate;
- selected VPN identity/status;
- multiple-VPN and app-excluded guidance;
- daemon health;
- active TCP/UDP counts and counters;
- generated FlClash snippet.

Do not publish IPv6 endpoints.

---

## Phase 9 — tests, hardening and rollout

Required suites:

- selector zero/one/multiple VPN tests;
- app-policy include/exclude tests;
- service start-context and background recovery tests;
- worker fast-path, publication and cleanup fault injection;
- parent cancellation and terminal cleanup tests;
- cleanup-debt blocks restart and later clears;
- host hook-ordering tests;
- UDP topology/range/capacity tests;
- DNS blackhole/concurrency tests;
- IPv6 exposure scans;
- iface+IP+MAC spoof tests;
- daemon-kill and recovery Clean tests;
- process-death behavior;
- FlClash DIRECT / PhoneVPN / WARP interoperability;
- throughput, latency, CPU, memory, battery, FD and APK-size measurements.

Release remains behind an experimental flag until all security gates pass on multiple Android versions/vendors.

---

## Suggested PR sequence

1. Review/ADR and exact Hev pin.
2. Host-testable prepare hook and Android feasibility harness.
3. VPN selection plus app-policy instrumentation.
4. UDP topology, relay range/capacity and DNS decision.
5. Settings/state and approved FGS declaration/start context.
6. `ProxyService` ownership and fake backend.
7. Exception-safe worker, terminal cleanup and cleanup debt.
8. TCP backend and outbound probes.
9. UDP ASSOCIATE implementation.
10. Root firewall lifecycle reusing `IptablesRule` and Clean.
11. MAC+IP ACL and counters.
12. System tethering Proxy-only integration.
13. UI and FlClash snippet.
14. Failure injection, device matrix and hardening.

Each PR must build independently and must not mix unrelated routing refactors.
