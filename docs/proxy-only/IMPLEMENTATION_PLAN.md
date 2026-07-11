# Proxy-only implementation plan

This plan is split into independently reviewable stages. Only Phase 0 is approved to start. UI, production firewall integration and release work remain gated by Phase 0 evidence.

## Phase 0 — feasibility and security spike

### Objective

Prove that a pinned HevSocks5Server can provide a fail-closed SOCKS5 data plane on Android without hidden physical fallback, worker death, DNS head-of-line blocking, ambiguous UDP firewall behavior or background-service restart assumptions.

### 0.1 Pin and build

1. Pin an exact Hev commit and recursive dependency state.
2. Record licenses/notices.
3. Build host-test and Android variants for all VPN Hotspot ABIs.
4. Enumerate every Internet-facing TCP, UDP and resolver FD creation path.
5. Add one injected socket-prepare seam for host CI and Android `android_setsocknetwork()`.
6. Make hook failure fatal in fail-closed mode.

### 0.2 VPN selection and app-UID policy

1. Demonstrate that `Upstreams.primary` may be physical.
2. Prototype a VPN-only candidate source.
3. Re-read capabilities immediately before startup.
4. Require `TRANSPORT_VPN`.
5. Test app-UID binding when VPN applies to all apps, explicitly includes VPN Hotspot, excludes VPN Hotspot and changes policy while running.
6. Detect more than one usable VPN candidate and return `MultipleVpnCandidates` instead of selecting nondeterministically.

Expected exclusion result: stable permission error, no listener exposure and no physical fallback.

### 0.3 UDP socket-topology discovery

For multiple simultaneous `UDP ASSOCIATE` sessions, record:

- control TCP FD and peer;
- returned `BND.ADDR/BND.PORT`;
- client-facing relay FD/local port;
- Internet-facing FD/local port;
- whether those roles share an FD;
- prepare-hook calls for each FD;
- ingress interface for client packets and remote replies;
- conntrack state of remote replies;
- behavior when relay range is exhausted.

Use packet capture plus native FD/port logging in a debug-only test build. Determine whether the firewall needs a verified `ESTABLISHED/RELATED` rule before terminal relay-range reject. Do not approve a broad VPN-interface allow.

The spike must define:

```text
udp_port_range_start
udp_port_range_end
configured association limit
effective association limit
```

If one port is consumed per association:

```text
effective limit = min(configured limit, usable ports in range)
```

### 0.4 DNS architecture

Test domain requests with working and blackholed VPN DNS, VPN loss, cancellation and concurrent resolutions.

Select either:

- bounded resolver workers around a VPN-aware synchronous API; or
- an asynchronous Android resolver path.

The chosen path must not block all Hev workers and must never use process-default DNS.

### 0.5 Probe semantics

Startup probes are outbound-only:

1. app-UID socket bind;
2. backend-created outbound TCP;
3. backend-created outbound UDP;
4. VPN-aware DNS;
5. internal listener bind/listen readiness.

Do not run a downstream listener-reachability probe while deny-first rules are active. External client reachability is tested only after allow commit.

### 0.6 IPv6 boundary

Prove IPv4-only listener/relay creation and ip6tables denial. Test native IPv6 and IPv4-mapped access.

### 0.7 Host-CI seam

Host tests inject a fake bind callback and assert every TCP/UDP/retry/resolver path invokes it before packet-producing operations. The host target must not link Android APIs.

### 0.8 Exit criteria

- exactly one usable VPN candidate is required;
- physical primary override is rejected;
- app exclusion is diagnosed fail-closed;
- TCP `CONNECT` works through VPN;
- UDP `ASSOCIATE` works with documented FD/port topology;
- firewall reply rule requirements are proven by capture/conntrack evidence;
- relay ports stay in range and exhaustion fails safely;
- VPN-aware DNS is bounded and non-blocking to unrelated sessions;
- all outbound FDs pass the hook;
- IPv6 cannot reach the MVP listener/range;
- backend restart handles VPN generation changes;
- repeated start/stop leaks no threads or FDs;
- fork delta is maintainable.

If any security criterion fails, stop and evaluate a Kotlin or Rust backend behind the same service/controller contracts.

## Phase 1 — native dependency integration

Proposed layout:

```text
external/hev-socks5-server/
mobile/src/main/cpp/proxy/
mobile/src/main/java/be/mygod/vpnhotspot/proxy/
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/
```

Requirements:

- reproducible pin in local, CI and source archives;
- MIT notices in app OSS notices;
- `libvpnhotspot_proxy.so` for every ABI;
- Hev internals hidden behind backend/JNI boundary;
- host-native test target using bind shim;
- APK/AAB symbol, ABI and size inspection.

Validation:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run host-native fork tests and current Rust daemon tests.

## Phase 2 — domain model and foreground-service gate

### Models

```kotlin
enum class SharingMode { VPN_ROUTING, PROXY_ONLY }

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

- random credentials;
- credential-encrypted storage and backup exclusion;
- bounded TCP port, UDP range and association limit;
- effective UDP capacity exposed to diagnostics;
- no secret logging or implicit export;
- existing users migrate to `VPN_ROUTING`.

### Foreground-service type and start context

Before merging `ProxyService`:

1. verify current target SDK;
2. choose a valid FGS type and permissions;
3. verify store-policy eligibility;
4. document which user action starts the service;
5. add manifest/lint tests;
6. explicitly handle `ForegroundServiceStartNotAllowedException`.

Once user-started and enabled, the service remains alive in waiting/fail-closed states. It does not stop on VPN or daemon loss and then attempt a background restart.

Automatic process-death resurrection is not assumed.

## Phase 3 — controller, service client and exception-safe worker

### Ownership

```text
ProxyOnlyController -> ProxyServiceClient -> ProxyService -> ProxyBackend
ProxyOnlyController -> ProxyFirewallClient -> vpnhotspotd
```

The controller never owns or calls `ProxyBackend` directly.

### Desired inputs

- settings/credentials version;
- VPN candidates and fresh capabilities;
- tethered interface/IPv4 state;
- MAC↔IPv4 neighbours and blocked clients;
- daemon channel health;
- service lifecycle.

### Worker contract

One `Channel.CONFLATED` feeds one worker.

Required behavior:

- no `collectLatest` around resource operations;
- normalized runtime key;
- per-iteration `try/catch`;
- `NonCancellable` transaction and rollback;
- fast-path replace exceptions enter fail-closed recovery;
- publication errors are contained;
- cleanup attempts deny, service backend stop and firewall stop independently;
- cleanup errors are aggregated and reported;
- worker `finally` runs terminal `stopApplied()`.

### Runtime key

Restart only for:

- TCP port;
- UDP enabled/range;
- credentials version;
- VPN network handle;
- sorted downstream interface/address set;
- backend configuration version.

Client changes perform firewall/service ACL replacement only.

## Phase 4 — ProxyService and backend

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

- FGS lifecycle;
- backend instance;
- native handles;
- listener/sessions;
- backend stats;
- emergency listener closure.

Service API should expose intent/binder-safe commands such as:

```kotlin
interface ProxyServiceClient {
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyServiceHandle): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
}
```

`stopBackend` must be idempotent and attempt complete native cleanup even when one native step fails.

There is no live network replacement in the MVP.

## Phase 5 — system tethering integration

Pure `PROXY_ONLY` downstreams do not create `RoutingManager`.

Internal model:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Initial support:

- Wi-Fi system tethering;
- USB tethering where MAC identity is reliable;
- Ethernet only after equivalent tests.

Deferred:

- LocalOnlyHotspot;
- Wi-Fi Direct repeater;
- Bluetooth;
- interfaces without reliable MAC identity.

## Phase 6 — root proxy firewall

### Independent lifecycle

Add a long-lived start/replace/cancel command separate from `SessionConfig`.

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
```

### Mandatory reuse

- expose/reuse `routing/iptables.rs::IptablesRule` and `IptablesChain` at crate scope;
- reuse applied ledger and `delete_repeated()`;
- add proxy jumps/chains to `firewall_cleanup.rs::clean()`;
- use IPv4 and IPv6 targets;
- preserve deterministic cleanup without app DB state.

### Policy

IPv4 client allow requires:

```text
input interface + source IPv4 + source MAC + destination TCP/UDP port range
```

IPv4 terminal reject and IPv6 deny are mandatory.

The exact return-traffic rule before UDP terminal reject is implemented only after Phase 0 proves Hev socket topology and conntrack semantics.

No IP-only fallback and no broad VPN-interface allow.

### Daemon death

Unexpected long-lived call completion causes immediate service listener closure. The FGS remains alive in fail-closed state. On daemon return, Clean/deny completes before backend restart.

## Phase 7 — UI and diagnostics

Show:

- sharing mode;
- persistent service state;
- actionable VPN/permission/multiple-VPN errors;
- endpoint and credentials;
- UDP range and effective capacity;
- daemon unavailable state;
- cleanup failures requiring attention;
- active TCP/UDP counts and per-client counters;
- generated FlClash snippet.

Waiting/fail-closed notifications must clearly state that the listener is closed.

## Phase 8 — integration and failure tests

Must cover:

- direct path versus PhoneVPN/WARP;
- physical primary rejection;
- multiple VPN candidates;
- app-policy include/exclude matrix;
- outbound-only probe ordering;
- DNS blackhole;
- UDP FD/port/conntrack topology;
- range exhaustion and capacity metrics;
- IPv6 denial;
- iface+IP+MAC spoofing;
- exceptions in fast-path replace, publish and every cleanup step;
- controller scope cancellation terminal cleanup;
- daemon death and background recovery without FGS restart;
- VPN return while UI backgrounded;
- process death without assumed automatic resurrection.

## Phase 9 — hardening and rollout

- connection/FD/memory limits;
- handshake, DNS and idle timeouts;
- fuzz SOCKS and UDP headers;
- native sanitizers;
- reproducible fork rebases;
- performance and battery measurements;
- experimental feature flag;
- visible limitations and troubleshooting.

## Suggested PR sequence

1. Round-2 ADR and exact Hev pin.
2. Host-testable socket hook and UDP topology spike.
3. VPN-only/multiple-candidate selector and app-policy tests.
4. DNS/probe feasibility and IPv6 boundary.
5. Settings plus FGS type/start-context decision.
6. Exception-safe controller with fake service/firewall clients.
7. Persistent ProxyService and TCP backend.
8. UDP backend, range and capacity enforcement.
9. Root firewall using `IptablesRule` and Clean.
10. MAC+IP ACL and counters.
11. System tethering integration.
12. UI, FlClash snippet and full failure matrix.

Each PR must build independently and avoid unrelated routing refactors.
