# Proxy-only code sketches

These snippets define contracts and sequencing. They are deliberately incomplete and must be adapted to current project APIs.

## 1. Settings, validated upstream and runtime key

```kotlin
package be.mygod.vpnhotspot.proxy

import android.net.Network

enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}

data class ProxyOnlySettings(
    val enabled: Boolean,
    val tcpPort: Int,
    val udpEnabled: Boolean,
    val udpPortRange: IntRange,
    val credentialsVersion: Long,
    val username: String,
    val password: String,
)

data class ProxyVpnUpstream(
    val network: Network,
    val handle: Long,
    val interfaces: Set<String>,
)

data class ProxyEndpoint(
    val interfaceName: String,
    val ipv4: String,
    val tcpPort: Int,
    val udpPortRange: IntRange?,
)

data class RuntimeKey(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val credentialsVersion: Long,
    val vpnNetworkHandle: Long,
    val downstreams: List<Pair<String, String>>, // sorted iface/address pairs
    val backendVersion: Int,
)
```

Client lists are intentionally absent from `RuntimeKey`; client updates replace ACL/firewall state without restarting the listener.

## 2. VPN-only selector

```kotlin
class ProxyVpnSelector(
    private val connectivity: ConnectivityManager,
) {
    fun validate(candidate: Upstream?): Result<ProxyVpnUpstream> = runCatching {
        val upstream = candidate ?: throw ProxyStartException.NoVpn
        val capabilities = connectivity.getNetworkCapabilities(upstream.network)
            ?: throw ProxyStartException.NoVpn
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw ProxyStartException.NonVpnUpstream
        }
        ProxyVpnUpstream(
            network = upstream.network,
            handle = upstream.network.networkHandle,
            interfaces = upstream.properties.allInterfaceNames.toSortedSet(),
        )
    }
}
```

The input should normally be `Upstreams.vpn`. If another selector is used, the capability check remains mandatory. `Upstreams.primary` alone is not sufficient because it can be overridden to a physical interface.

A start probe must additionally distinguish an app-UID policy error:

```kotlin
sealed class ProxyStartException(message: String) : Exception(message) {
    data object NoVpn : ProxyStartException("No VPN network is available")
    data object NonVpnUpstream : ProxyStartException("Selected upstream is not a VPN")
    data object VpnPermissionDenied : ProxyStartException(
        "VPN Hotspot is excluded from the selected VPN app policy"
    )
}
```

## 3. Cancellation-safe desired-state worker

Do not use `collectLatest` around resource creation or teardown. New emissions must not cancel an in-flight transaction.

```kotlin
class ProxyOnlyController(
    private val backend: ProxyBackend,
    private val firewall: ProxyFirewallClient,
    private val scope: CoroutineScope,
) {
    private val desired = Channel<DesiredProxyState>(Channel.CONFLATED)
    private var applied: AppliedProxyState? = null

    fun start(source: Flow<DesiredProxyState>) {
        scope.launch {
            source.collect { desired.trySend(it) }
        }
        scope.launch {
            for (snapshot in desired) {
                withContext(NonCancellable) {
                    reconcile(snapshot.normalized())
                }
            }
        }
    }

    private suspend fun reconcile(next: DesiredProxyState) {
        if (!next.settings.enabled) {
            stopApplied()
            publish(ProxyOnlyState.Disabled)
            return
        }
        if (next.downstreams.isEmpty()) {
            stopApplied()
            publish(ProxyOnlyState.WaitingForTethering)
            return
        }
        val upstream = next.validatedVpn ?: run {
            stopApplied()
            publish(ProxyOnlyState.FailClosed(next.vpnFailure))
            return
        }
        if (!next.daemonHealthy) {
            // Existing kernel allows may still exist, so listener closure is mandatory.
            stopBackendFirst()
            publish(ProxyOnlyState.FailClosed("Root daemon unavailable"))
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.key == key) {
            firewall.replace(current.firewallId, next.firewallConfig(denied = false))
            backend.replaceAcl(current.backendHandle, next.allowedClients)
            publish(current.runningState(next))
            return
        }

        stopApplied()
        publish(ProxyOnlyState.Starting)

        var partialFirewall: ProxyFirewallHandle? = null
        var partialBackend: ProxyBackendHandle? = null
        try {
            partialFirewall = firewall.start(next.firewallConfig(denied = true))
            partialBackend = backend.start(next.backendConfig(upstream))
            backend.probe(partialBackend)
            firewall.replace(partialFirewall, next.firewallConfig(denied = false))
            applied = AppliedProxyState(key, partialBackend, partialFirewall)
            publish(applied!!.runningState(next))
        } catch (t: Throwable) {
            partialBackend?.let { backend.stop(it) }
            partialFirewall?.let { firewall.stop(it) }
            publish(ProxyOnlyState.Error("Failed to start proxy", t))
        }
    }

    private suspend fun stopApplied() {
        val current = applied ?: return
        applied = null
        runCatching { firewall.denyAll(current.firewallId) }
        runCatching { backend.stop(current.backendHandle) }
        runCatching { firewall.stop(current.firewallId) }
    }

    private suspend fun stopBackendFirst() {
        val current = applied ?: return
        applied = null
        // Daemon may be dead and unable to deny; listener must close anyway.
        runCatching { backend.stop(current.backendHandle) }
        runCatching { firewall.stop(current.firewallId) }
    }
}
```

`normalized()` sorts downstreams and clients and removes irrelevant `LinkProperties` churn. Network-generation changes alter the runtime key and therefore use full stop/start. Live `replaceNetwork()` is not part of the MVP.

## 4. Backend abstraction

```kotlin
interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun probe(handle: ProxyBackendHandle)
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle)
}

internal object ProxyNative {
    init { System.loadLibrary("vpnhotspot_proxy") }

    external fun start(
        configYaml: ByteArray,
        networkHandle: Long,
        failClosed: Boolean,
    ): Long

    external fun replaceAllowedClients(instance: Long, addresses: Array<String>)
    external fun readStats(instance: Long): NativeProxyStats
    external fun stop(instance: Long)
}
```

No native network-update entry point is required for the MVP. A VPN handle change destroys the old instance and all sessions.

## 5. Testable Hev socket hook

```c
/* vpnhotspot_socket_hook.h */
#pragma once
#include <stdint.h>

typedef int (*vpnhotspot_network_bind_fn)(uint64_t network, int fd, void *opaque);

typedef struct {
    uint64_t network_handle;
    int fail_closed;
    vpnhotspot_network_bind_fn bind_fn;
    void *bind_opaque;
} vpnhotspot_network_state_t;

int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state);
```

Android implementation:

```c
#include <android/multinetwork.h>
#include <errno.h>

static int android_bind_network(uint64_t network, int fd, void *opaque) {
    (void)opaque;
    return android_setsocknetwork((net_handle_t)network, fd) == 0 ? 0 : -errno;
}

int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state) {
    (void)family;
    (void)type;
    if (state == NULL || state->network_handle == 0 || state->bind_fn == NULL) {
        return state != NULL && state->fail_closed ? -ENONET : 0;
    }
    return state->bind_fn(state->network_handle, fd, state->bind_opaque);
}
```

Hook placement:

```c
int fd = socket(family, type, protocol);
if (fd < 0)
    return -errno;

int rc = vpnhotspot_prepare_outbound_socket(fd, family, type, &server->network);
if (rc < 0) {
    close(fd);
    return rc;
}

/* connect(), sendto() or resolver use may begin only here. */
```

Host-CI shim:

```c
typedef struct {
    int calls;
    int fail_with;
    int last_fd;
} fake_bind_state_t;

static int fake_bind(uint64_t network, int fd, void *opaque) {
    fake_bind_state_t *state = opaque;
    state->calls++;
    state->last_fd = fd;
    return state->fail_with;
}
```

Unit tests inject `fake_bind` and assert every TCP/UDP/retry/fallback path calls it before the first packet-producing operation.

## 6. DNS worker boundary

A simple `Network.getAllByName()` call must not run inline on a Hev worker. One acceptable prototype shape is a bounded Kotlin resolver dispatcher:

```kotlin
class NetworkResolver(
    private val network: Network,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun resolve(host: String): List<InetAddress> = withTimeout(5_000) {
        withContext(dispatcher) { network.getAllByName(host).toList() }
    }
}
```

The dispatcher must have bounded parallelism and queueing. Phase 0 may instead choose an asynchronous native resolver. In either case, cancellation/VPN loss returns a controlled failure and never invokes process-default DNS.

## 7. UDP relay range model

```kotlin
data class UdpRelayRange(val start: Int, val endInclusive: Int) {
    init {
        require(start in 1..65535)
        require(endInclusive in start..65535)
        require(endInclusive - start <= 255) // product limit; tune after tests
    }
}
```

The pinned Hev config must constrain every `UDP ASSOCIATE` relay to this range. The root firewall denies the entire range before backend startup, then allows only authenticated/known client source identities.

## 8. Proto sketch

```proto
message ClientEnvelope {
  uint64 call_id = 1;
  oneof command {
    // existing commands...
    StartProxyFirewallCommand start_proxy_firewall = 9;
    ReplaceProxyFirewallCommand replace_proxy_firewall = 10;
  }
}

message StartProxyFirewallCommand {
  ProxyFirewallConfig config = 1;
}

message ReplaceProxyFirewallCommand {
  uint64 runtime_id = 1;
  ProxyFirewallConfig config = 2;
}

message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool deny_all_ipv4 = 7;
  bool deny_all_ipv6 = 8;
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

The call remains active for the firewall runtime lifetime. Existing cancellation semantics terminate it. Channel termination is also the app's daemon-liveness signal.

## 9. Reusing the Rust iptables ledger

The proxy firewall module should reuse, not duplicate, the current routing firewall abstractions. This may require promoting visibility from `pub(super)` to `pub(crate)`.

```rust
use crate::firewall::IptablesTarget;
use crate::routing::iptables::{
    apply_iptables_batch,
    ensure_iptables_chain_result,
    IptablesChain,
    IptablesRule,
};

pub(crate) struct Runtime {
    config: ProxyFirewallConfig,
    applied: Vec<IptablesRule>,
}
```

IPv4 allow-rule shape:

```rust
IptablesRule::new(
    IptablesTarget::Ipv4,
    "filter",
    "vpnhotspot_proxy_input",
    vec![
        "-i".into(), downstream.interface_name.clone(),
        "-s".into(), client_ip.to_string(),
        "-m".into(), "mac".into(),
        "--mac-source".into(), mac_string(&client.mac),
        "-p".into(), "tcp".into(),
        "--dport".into(), config.tcp_port.to_string(),
        "-j".into(), "ACCEPT".into(),
    ],
)
```

Equivalent UDP rules cover the configured relay range. IPv4 chains end in reject. IPv6 chains reject TCP port and UDP range without allow rules in the MVP.

`routing/firewall_cleanup.rs::clean()` must delete repeated proxy jumps and flush/delete proxy chains for both address families. The new runtime must use the same applied-ledger rollback conventions as routing.

## 10. Tethering integration

```kotlin
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Addresses: Set<Inet4Address>,
    val vpnRoutingEnabled: Boolean,
    val proxyExposureEnabled: Boolean,
)

when (sharingMode) {
    SharingMode.VPN_ROUTING -> startRouting(downstream)
    SharingMode.PROXY_ONLY -> exposeProxyWithoutRouting(downstream)
}
```

Do not instantiate `RoutingManager` for a pure Proxy-only downstream.

## 11. Daemon liveness

```kotlin
firewall.start(config).use { runtime ->
    select<Unit> {
        runtime.completion.onAwait {
            // Kernel allow rules may persist, so close listener immediately.
            backend.stop(activeBackend)
            publish(ProxyOnlyState.FailClosed("Root daemon unavailable"))
        }
        controllerStop.onAwait { /* normal deny/stop path */ }
    }
}
```

On reconnection, run Clean or establish deny state before starting a new backend.

## 12. Credential requirements

- never include username/password in `toString()`;
- redact them from exceptions, crash reports and analytics;
- use app-private credential-encrypted storage;
- increment `credentialsVersion` on regeneration so runtime restart is deterministic;
- never pass secrets through process arguments;
- show password only after an explicit user action.