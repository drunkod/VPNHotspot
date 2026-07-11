# Proxy-only implementation plan

This plan is split into independently reviewable stages. Do not begin UI or broad routing changes until Phase 0 proves the security-critical assumptions.

## Phase 0 — feasibility and security spike

### Objective

Prove that the pinned HevSocks5Server source can provide a fail-closed SOCKS5 data plane on Android without hidden physical-network fallback, event-loop DNS stalls or unbounded UDP exposure.

### Source and build tasks

1. Pin an exact HevSocks5Server commit and recursive dependency state.
2. Record all third-party licenses and required notices.
3. Build host-test and Android variants for the same ABIs as VPN Hotspot.
4. Enumerate every Internet-facing TCP, UDP and resolver socket creation path, including retries and address-family fallback.
5. Add one narrow socket-prepare seam that can use either a host test shim or Android `android_setsocknetwork()`.
6. Make prepare-hook failure fatal whenever fail-closed is enabled.

### VPN-selection tasks

1. Demonstrate that `Upstreams.primary` can be a physical network when `service.upstream` is overridden.
2. Implement a proof-of-concept selector using `Upstreams.vpn` or explicit `TRANSPORT_VPN` capability validation.
3. Re-read capabilities immediately before binding/probing.
4. Reject a non-VPN network even when it is the configured primary upstream.

### Per-app VPN policy matrix

Test socket binding when:

- the phone VPN applies to all apps;
- VPN Hotspot is explicitly included in an allowed-apps policy;
- VPN Hotspot is excluded or denied by the VPN app;
- always-on/lockdown policy is enabled where available.

Expected result for exclusion/denial: `android_setsocknetwork()` or equivalent fails; the UI reports that VPN Hotspot is not permitted to use the selected VPN; no direct fallback occurs.

### UDP discovery

Inspect and test the pinned Hev source to determine:

- whether one shared UDP relay port or per-association ports are used;
- whether the `udp-port` configuration accepts a fixed range;
- which address/port is returned in `UDP ASSOCIATE` replies;
- whether all relay sockets pass the prepare hook;
- how association lifetime follows the control TCP connection.

The spike must produce an explicit `udp_port_range_start/end` contract unless a single fixed relay port is proven.

### DNS blocking and leak tests

Test domain-form requests with:

- working VPN DNS;
- a blackholed DNS server;
- VPN loss during resolution;
- repeated concurrent resolutions.

Measure whether `android_getaddrinfofornetwork()` or a JNI `Network.getAllByName()` callback blocks Hev workers. Select a bounded resolver pool or asynchronous network-aware resolver. The process-default resolver is prohibited.

### IPv6 discovery

Verify how Hev creates dual-stack listeners and IPv4-mapped sockets. The MVP decision is IPv4-only relay with explicit IPv6 firewall denial. Prove the listener cannot be reached over native IPv6 or an unintended dual-stack wildcard.

### Host-CI seam

Provide a Linux host build where:

```c
vpnhotspot_prepare_outbound_socket(fd, state)
```

calls an injected function instead of `android_setsocknetwork()`. The shim records FD/path/family/type and can return deterministic errors. CI must prove every socket path invokes the hook exactly once before connect/send.

### Exit criteria

- a candidate must have current `TRANSPORT_VPN` capability before startup;
- TCP `CONNECT` works through the phone VPN;
- UDP `ASSOCIATE` works through the phone VPN and within the documented relay range;
- VPN-specific DNS works without blocking all Hev workers under a blackhole test;
- invalid/non-VPN handles fail closed;
- app-UID VPN exclusion produces a stable user-visible error and no fallback;
- IPv6 cannot reach the MVP listener;
- network-generation replacement is handled by complete backend restart;
- repeated start/stop cycles leak no threads or FDs;
- host CI enforces prepare-hook coverage;
- the fork delta remains small enough to maintain.

If any security criterion fails, stop before Kotlin UI or daemon policy work. Re-evaluate a Kotlin backend using `Network.bindSocket()`/`bindDatagramSocket()` or a Rust backend using socket2/Tokio.

## Phase 1 — dependency and native build integration

### Proposed layout

```text
external/hev-socks5-server/                  # pinned fork/submodule or vendored source
mobile/src/main/cpp/proxy/                   # JNI + network-binding seam
mobile/src/main/java/be/mygod/vpnhotspot/proxy/
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/
```

### Build requirements

- make the Hev pin reproducible in local, CI and source-archive builds;
- preserve MIT notices and add the dependency to app OSS notices;
- build `libvpnhotspot_proxy.so` for every supported ABI;
- keep Hev internals behind one JNI/backend boundary;
- add R8 rules only for stable JNI entry points;
- add a host-native test target using the injected network-bind shim;
- inspect APK/AAB for duplicate symbols/libraries and size changes.

Validation:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run host-native fork tests and current Rust daemon tests.

## Phase 2 — domain model, settings and Android service gate

### New files

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlySettings.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyState.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyEndpoint.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyCredentials.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyVpnSelector.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyBackend.kt
```

### Settings model

```kotlin
data class ProxyOnlySettings(
    val enabled: Boolean = false,
    val tcpPort: Int = 10808,
    val udpEnabled: Boolean = true,
    val udpPortRange: IntRange,
    val credentialsVersion: Long,
    val username: String,
    val password: String,
)
```

Fail-closed is mandatory in the MVP and is not exposed as a switch. A future direct fallback requires a separate product/security review.

Requirements:

- random credentials on first enable;
- no empty credentials for LAN listeners;
- validate TCP port and bounded UDP range/conflicts;
- credential-encrypted storage and backup exclusion;
- explicit regeneration increments `credentialsVersion`;
- no credentials in logs, model `toString()`, implicit intents or routine notifications.

### Mode model

MVP:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}
```

Existing installations migrate to `VPN_ROUTING`. Keep per-downstream flags in internal desired state, but do not expose mixed mode yet.

### Foreground-service gate

Before adding `ProxyService` to the manifest:

1. read the repository's current target SDK;
2. identify the valid foreground-service type for this sustained LAN networking use;
3. verify required permissions and app-store policy eligibility;
4. document the decision and add manifest/lint tests.

Do not leave this as a release-time open question and do not claim `VpnService` ownership.

## Phase 3 — VPN selector and serialized controller

### New files

```text
ProxyOnlyController.kt
ProxyDesiredState.kt
ProxyReconciler.kt
ProxyServiceClient.kt
ProxyFirewallClient.kt
```

### VPN-only selector

- prefer `Upstreams.vpn`;
- query fresh capabilities for the candidate;
- require `TRANSPORT_VPN`;
- perform an app-UID bind probe;
- surface `VpnNotAvailable`, `NonVpnUpstream` and `VpnPermissionDenied` distinctly;
- never select `Upstreams.fallback`.

### Desired-state input

Combine immutable snapshots of:

- settings and credential version;
- validated VPN candidate;
- active system-tethering interfaces/IPv4 addresses;
- neighbour MAC↔IPv4 state;
- blocked-client state;
- root-daemon/firewall channel health;
- app/service lifecycle.

The collector sends snapshots into `Channel.CONFLATED`. One worker processes the newest pending state after finishing the current transaction. Do not use `collectLatest` for apply operations.

### Runtime key

Restart the backend only when one of these changes:

- TCP port;
- UDP relay range or UDP feature flag;
- credentials version;
- validated VPN network handle;
- sorted downstream interface/address set;
- backend implementation/configuration version.

Client-list ordering, blocked-client changes and irrelevant `LinkProperties` churn trigger ACL/firewall replacement only.

### Transaction safety

- apply and rollback execute in `withContext(NonCancellable)`;
- record each created resource immediately in partial applied state;
- stop order is deny, listener/session close, firewall cleanup;
- daemon channel completion closes the listener even though kernel allow rules may persist;
- a network generation change always performs full backend restart in the MVP.

## Phase 4 — ProxyService and Hev backend

### Files

```text
ProxyService.kt
ProxyNotification.kt
HevProxyBackend.kt
ProxyNative.kt
```

### Backend interface

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle)
}
```

There is no `replaceNetwork()` in the MVP contract. The controller stops and recreates the backend on VPN generation change.

### JNI contract

Expose opaque per-instance handles and avoid process-global server state. Native configuration remains in memory or an app-private file with guaranteed deletion; never pass secrets via command-line arguments.

### DNS

Implement the resolver architecture selected by Phase 0, including strict timeout, cancellation and bounded concurrency. Add metrics for queue time, resolution time and failure category without logging requested hostnames by default.

### UDP

- standard `UDP ASSOCIATE` only;
- relay ports constrained to the configured range;
- association lifetime tied to control TCP;
- `FRAG != 0` rejected;
- source address tied to the authenticated/control peer;
- every Internet-facing UDP FD bound through the prepare hook;
- bounded association count and idle timeout.

## Phase 5 — system-tethering integration

In `PROXY_ONLY`, do not create the existing VPN-forwarding `RoutingManager` for that downstream.

Use an internal model:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Reconcile `vpnRoutingEnabled` through existing routing and `proxyExposureEnabled` through the proxy controller/firewall command.

Initial support:

- Wi-Fi system tethering;
- USB tethering where MAC identity is reliable;
- Ethernet only after the same identity/firewall tests.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth;
- any downstream that cannot provide reliable MAC+IP identity.

## Phase 6 — root proxy firewall, ACL and counters

### Independent daemon lifecycle

Add start/replace commands whose call remains active until cancelled. Do not extend `SessionConfig`.

Suggested proto:

```proto
message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool ipv6_deny = 7;
}

message ProxyDownstream {
  string interface_name = 1;
  repeated bytes ipv4_addresses = 2;
}

message ProxyClient {
  bytes mac = 1;
  repeated bytes ipv4 = 2;
}
```

### Mandatory reuse of firewall machinery

The new module must not create a parallel mutation representation.

- promote/reuse `routing/iptables.rs::IptablesRule` and `IptablesChain` at crate scope as needed;
- use the same insertion ledger and `delete_repeated()` cleanup semantics;
- add proxy jumps/chains to `routing/firewall_cleanup.rs::clean()`;
- use both `IptablesTarget::Ipv4` and `Ipv6`;
- preserve deterministic Clean without private DB/preferences.

Suggested files:

```text
vpnhotspotd/src/proxy_firewall/mod.rs
vpnhotspotd/src/proxy_firewall/desired.rs
vpnhotspotd/src/proxy_firewall/applied.rs
```

### Rules

IPv4 allow requires:

```text
input interface + source IPv4 + source MAC + destination TCP port/UDP range
```

IPv4 ends with reject. IPv6 rejects access to all proxy ports/ranges in the MVP.

There is no IP-only fallback. A client/interface without reliable MAC identity remains denied.

### Daemon death

A long-lived call/channel is the liveness signal. When it terminates unexpectedly, the app closes the native listener immediately. On daemon restart, Clean or deny state is re-established before listener startup. Tests must assume old allow rules can persist in the kernel.

### Counters

Add proxy TCP/UDP sources while keeping MAC as public identity. Distinguish kernel listener bytes from backend payload bytes.

## Phase 7 — UI and support diagnostics

Show:

- global sharing mode;
- status and actionable failure reason;
- IPv4 endpoint and TCP port;
- UDP relay range/availability;
- username and controlled password reveal/copy/regenerate;
- validated VPN interface/network status;
- app-excluded-from-VPN error guidance;
- daemon unavailable state;
- active TCP/UDP counts;
- per-client proxy counters;
- generated FlClash snippet.

Do not publish IPv6 endpoints in the MVP.

## Phase 8 — interoperability and complete integration tests

Validate:

- `DIRECT` uses physical tethering;
- `PhoneVPN` uses phone VPN;
- `WARP-via-PhoneVPN` produces Cloudflare WARP egress;
- VPN Hotspot included/excluded app-policy matrix;
- DNS blackhole and VPN-loss behaviour;
- UDP relay range enforcement;
- IPv6 listener denial;
- iface+IP+MAC anti-spoofing;
- kill `vpnhotspotd` while allow rules are active;
- app-process death and deterministic recovery;
- repeated VPN generation restart;
- no `VPN_ROUTING_AND_PROXY` behaviour in MVP.

## Phase 9 — hardening and rollout

- per-client/global connection and FD limits;
- idle/handshake/DNS timeouts;
- fuzz SOCKS handshake and UDP headers;
- native sanitizers where practical;
- reproducible fork rebases with host hook-coverage CI;
- throughput, latency, CPU, memory, battery and APK-size measurements;
- experimental feature flag for first release;
- visible limitations and troubleshooting guidance.

## Suggested PR sequence

1. Revised ADR/review and exact Hev pin.
2. Host-testable Hev socket hook plus Android feasibility spike.
3. VPN-only selector and per-app policy instrumentation tests.
4. Settings/domain model and resolved FGS declaration.
5. Cancellation-safe controller and fake backends.
6. App-process TCP backend.
7. UDP range and DNS worker/async resolver.
8. Root firewall lifecycle reusing `IptablesRule` and Clean.
9. MAC+IP ACL and counters.
10. System tethering Proxy-only integration.
11. UI and generated FlClash snippet.
12. Failure injection, device matrix and hardening.

Each PR must build independently and must not mix unrelated routing refactors.