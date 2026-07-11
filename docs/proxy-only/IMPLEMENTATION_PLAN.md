# Proxy-only implementation plan

This plan is intentionally split into reviewable stages. Do not implement the feature as one large patch.

## Phase 0 — feasibility spike

### Objective

Prove that a HevSocks5Server outbound socket can be bound to the currently selected Android VPN `Network` before any packet leaves the socket.

### Tasks

1. Pin an exact HevSocks5Server commit and record its license.
2. Build it for the same ABIs as VPN Hotspot.
3. Locate every Internet-facing TCP and UDP socket creation path.
4. Add a native callback or direct NDK call that receives a `uint64_t network_handle`.
5. Make socket binding mandatory when `fail_closed=true`.
6. Add a native test mode that opens one TCP and one UDP socket through the supplied network handle.
7. Verify that changing the handle invalidates old sessions.

### Exit criteria

- TCP `CONNECT` works through the phone VPN.
- UDP `ASSOCIATE` works through the phone VPN.
- A deliberately invalid network handle fails without direct fallback.
- The native core can start, stop and restart repeatedly without leaking threads or file descriptors.
- The exact fork delta is small enough to maintain.

If this spike fails, stop before UI or daemon work. Re-evaluate a Kotlin implementation using `Network.bindSocket()`/`bindDatagramSocket()` or a Rust implementation that reuses the existing Tokio/socket2 stack.

## Phase 1 — dependency and native build integration

### Proposed repository layout

```text
external/hev-socks5-server/                 # pinned source/submodule or vendored source
mobile/src/main/cpp/proxy/                  # JNI bridge and Android-specific hook
mobile/src/main/java/be/mygod/vpnhotspot/proxy/
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/
```

### Build tasks

- Add Hev and its recursive dependencies using a reproducible pin.
- Prefer a Git submodule only if CI and release source archives already initialize it reliably.
- Otherwise vendor the minimal source and preserve the MIT license/notice.
- Produce one shared library per existing APK ABI.
- Avoid duplicate C runtime or symbol collisions.
- Add R8 keep rules only for JNI entry points that require stable class/method names.
- Include the Hev license in the OSS notices shown by the app.

### Suggested build boundary

Expose one Android-specific library to Kotlin:

```text
libvpnhotspot_proxy.so
  ├── JNI bridge
  ├── Android network binding adapter
  └── linked HevSocks5Server
```

Do not expose Hev internals directly to Kotlin.

### Validation

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
```

Also inspect the final APK/AAB for all expected ABIs and duplicate native libraries.

## Phase 2 — Kotlin domain model and settings

### New files

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlySettings.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyState.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyEndpoint.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyCredentials.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyNative.kt
```

### Settings

```kotlin
data class ProxyOnlySettings(
    val enabled: Boolean = false,
    val port: Int = 10808,
    val udpEnabled: Boolean = true,
    val failClosed: Boolean = true,
    val username: String,
    val password: String,
)
```

Requirements:

- generate random credentials on first enable;
- never log the password;
- reject empty credentials when listening beyond loopback;
- validate port range and conflicts;
- store secrets in credential-encrypted storage;
- exclude secret storage from backup;
- add an explicit regenerate action;
- do not export credentials through implicit intents.

### Sharing mode

Add an explicit setting:

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
    VPN_ROUTING_AND_PROXY,
}
```

Migration must preserve current behaviour by mapping existing installations to `VPN_ROUTING`.

## Phase 3 — controller and foreground lifecycle

### New files

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyController.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyService.kt
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyNotification.kt
```

### Controller inputs

Combine these flows:

- user settings;
- `Upstreams.primary`;
- active system-tethering interfaces;
- downstream addresses;
- blocked-client changes;
- application/service lifecycle.

Use a single serialized reconciliation loop instead of independent callbacks that start/stop native code concurrently.

Pseudo-flow:

```kotlin
combine(settings, vpnUpstream, tetheredIfaces, clients) { settings, vpn, ifaces, clients ->
    DesiredProxyState(settings, vpn, ifaces, clients)
}.distinctUntilChanged().collectLatest { desired ->
    reconciler.apply(desired)
}
```

### Foreground service rules

- Start only after the user explicitly enables proxy sharing.
- Keep a persistent notification while the listener exists.
- Use `START_NOT_STICKY` unless boot restoration is explicitly enabled.
- On process restart, default to deny until settings, VPN and downstream state are all known.
- Do not expose a binder or intent action that allows another app to change credentials or network handles.

### Manifest

Add a non-exported service. Select the foreground-service type only after checking the target SDK requirements; do not claim `VpnService` ownership.

## Phase 4 — native Hev integration

### JNI contract

Recommended minimal API:

```kotlin
internal object ProxyNative {
    external fun start(config: NativeProxyConfig): Long
    external fun updateNetwork(instance: Long, networkHandle: Long, generation: Long): Boolean
    external fun updateAcl(instance: Long, allowedClientIps: Array<String>)
    external fun stop(instance: Long)
}
```

`start` should return an opaque native instance handle. Avoid process-global state so tests can verify lifecycle isolation.

### Hev fork changes

Add a socket-created hook with a narrow contract:

```c
typedef int (*hev_socket_prepare_fn)(int fd, int family, int type, void *user_data);

void hev_socks5_server_set_socket_prepare(
    hev_server_t *server,
    hev_socket_prepare_fn callback,
    void *user_data);
```

Call it for every outbound socket after creation and before any of:

- `connect()`;
- `sendto()`;
- DNS resolution that may create an unbound network socket.

The Android adapter:

```c
static int prepare_android_vpn_socket(int fd, int family, int type, void *opaque) {
    const proxy_network_state_t *state = opaque;
    if (state->network_handle == 0)
        return state->fail_closed ? -ENONET : 0;
    return android_setsocknetwork(state->network_handle, fd) == 0
        ? 0
        : -errno;
}
```

### DNS

For SOCKS requests with a domain address, use one of:

1. a native resolver tied to the same network handle; or
2. a Kotlin callback that calls `Network.getAllByName()` and returns IP candidates.

Never use the process-default resolver in fail-closed mode.

### UDP requirements

- implement standard `UDP ASSOCIATE`;
- keep the UDP association alive only while the control TCP connection remains alive;
- reject `FRAG != 0` in the MVP;
- enforce idle timeout and maximum association count;
- bind every Internet-facing UDP socket to the VPN network;
- prevent spoofing by accepting UDP packets only from the client address associated with the control connection.

## Phase 5 — system-tethering integration

### Required behavioural change

In `PROXY_ONLY`, system tethering must not be added to the existing full VPN routing manager.

Do not simply start the current `TetheringService.Downstream` and add a proxy. That would still route direct client traffic through the VPN.

### Suggested implementation

Split tethered interface ownership into two independent concerns:

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)
```

Reconcile:

- `vpnRoutingEnabled` through existing `RoutingManager`;
- `proxyExposureEnabled` through the new root proxy-firewall command.

### Initial support

MVP:

- Wi-Fi system tethering;
- USB tethering;
- optionally Ethernet tethering after testing.

Deferred:

- LocalOnlyHotspot, because it has no normal direct Internet path;
- Wi-Fi Direct repeater, because its direct fallback path is app-created rather than ordinary system tethering;
- Bluetooth, until UDP and MTU behaviour are verified.

## Phase 6 — root daemon firewall, ACL and counters

### Proto additions

Prefer an independent proxy-firewall lifecycle rather than overloading `SessionConfig`.

Example:

```proto
message StartProxyFirewallCommand {
  ProxyFirewallConfig config = 1;
}

message ReplaceProxyFirewallCommand {
  uint64 runtime_id = 1;
  ProxyFirewallConfig config = 2;
}

message ProxyFirewallConfig {
  repeated string downstream_interfaces = 1;
  uint32 tcp_port = 2;
  uint32 udp_port = 3;
  repeated ClientConfig allowed_clients = 4;
  uint64 generation = 5;
}
```

Also add stop/cancel semantics consistent with existing long-lived calls.

### Daemon files

```text
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/mod.rs
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/desired.rs
mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/applied.rs
```

Responsibilities:

- deny target ports by default;
- allow only configured downstream interfaces;
- allow only known, unblocked clients;
- maintain per-client counters;
- remove access immediately when a client is blocked;
- reject access arriving from VPN/upstream interfaces;
- reconstruct and remove app-owned state during Clean.

### Counter extensions

Add proxy TCP/UDP traffic sources to `DaemonTrafficSource`. Keep MAC as the public identity and IP address as an implementation selector, matching current project conventions.

Document whether counters represent:

- packets entering the listener;
- packets leaving the listener;
- payload bytes reported by native code;
- or a combination.

Do not label firewall byte counts as application payload.

## Phase 7 — UI

Add a section to the existing tethering management UI following current Compose patterns.

Fields:

- Sharing mode;
- Enable proxy server;
- Status;
- endpoint address(es);
- port;
- username;
- reveal/copy/regenerate password actions;
- UDP availability;
- selected VPN interface;
- fail-closed switch, enabled by default;
- active TCP/UDP session counts;
- per-client proxy usage;
- QR or copyable FlClash snippet.

Status messages must distinguish:

- no system tethering;
- no VPN;
- VPN detected but socket probe failed;
- listener running but firewall not committed;
- running;
- blocked because VPN was lost;
- native crash or unexpected stop.

## Phase 8 — FlClash interoperability

Provide a generated snippet containing only the endpoint and credentials. The Android application must not attempt to maintain the user's process rules.

Example:

```yaml
proxies:
  - name: PhoneVPN
    type: socks5
    server: 192.168.43.1
    port: 10808
    username: generated-user
    password: generated-password
    udp: true
```

The laptop remains responsible for rules such as:

```yaml
rules:
  - PROCESS-NAME,Safari,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)telegram,SELECTIVE-VPN
  - MATCH,DIRECT
```

## Phase 9 — hardening and rollout

- add connection limits per client;
- add global file-descriptor and memory limits;
- enforce idle timeouts;
- redact credentials and destination metadata from logs by default;
- fuzz SOCKS5 handshake and UDP header parsing;
- verify repeated VPN handoffs;
- verify no direct fallback under failure;
- add deterministic cleanup tests;
- measure throughput, battery, memory and APK size;
- keep the feature behind an experimental flag for the first release.

## Suggested PR sequence

1. ADR and dependency pin only.
2. Native Hev spike and tests.
3. Kotlin settings/state/controller without UI.
4. Proxy service and TCP-only path.
5. UDP ASSOCIATE.
6. Root firewall and ACL lifecycle.
7. Counters and client integration.
8. UI and generated FlClash snippet.
9. Failure tests and hardening.

Each PR should build independently and avoid mixing unrelated routing refactors.
