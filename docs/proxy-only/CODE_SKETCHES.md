# Proxy-only code sketches

These snippets define interfaces and sequencing. They are not intended to compile unchanged.

## 1. Kotlin settings and state

```kotlin
package be.mygod.vpnhotspot.proxy

import android.net.Network

enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
    VPN_ROUTING_AND_PROXY,
}

data class ProxyOnlySettings(
    val enabled: Boolean,
    val port: Int,
    val udpEnabled: Boolean,
    val failClosed: Boolean,
    val username: String,
    val password: String,
)

data class ProxyEndpoint(
    val interfaceName: String,
    val host: String,
    val port: Int,
)

data class ProxyUpstream(
    val network: Network,
    val handle: Long,
    val generation: Long,
    val interfaces: List<String>,
)

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data object Starting : ProxyOnlyState
    data class Running(
        val endpoints: List<ProxyEndpoint>,
        val upstream: ProxyUpstream,
        val tcpConnections: Int,
        val udpAssociations: Int,
    ) : ProxyOnlyState
    data class FailClosed(val reason: String) : ProxyOnlyState
    data class Error(val message: String, val cause: Throwable?) : ProxyOnlyState
    data object Stopping : ProxyOnlyState
}
```

## 2. Serialized reconciler

```kotlin
class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var current: AppliedProxyState? = null

    fun start(
        settings: Flow<ProxyOnlySettings>,
        upstreams: Flow<Upstream?>,
        downstreams: Flow<List<DownstreamAddress>>,
        clients: Flow<List<AllowedClient>>,
    ) = scope.launch {
        var generation = 0L
        var previousNetwork: Network? = null

        combine(settings, upstreams, downstreams, clients) { config, upstream, ifaces, allowed ->
            if (upstream?.network != previousNetwork) {
                previousNetwork = upstream?.network
                generation += 1
            }
            DesiredProxyState(
                settings = config,
                upstream = upstream?.let {
                    ProxyUpstream(
                        network = it.network,
                        handle = it.network.networkHandle,
                        generation = generation,
                        interfaces = it.properties.allInterfaceNames,
                    )
                },
                downstreams = ifaces,
                clients = allowed,
            )
        }.distinctUntilChanged().collectLatest { desired ->
            mutex.withLock { reconcile(desired) }
        }
    }

    private suspend fun reconcile(desired: DesiredProxyState) {
        if (!desired.settings.enabled) {
            stopCurrent()
            return
        }
        if (desired.downstreams.isEmpty()) {
            stopCurrent()
            publish(ProxyOnlyState.WaitingForTethering)
            return
        }
        val upstream = desired.upstream
        if (upstream == null) {
            stopCurrent()
            publish(ProxyOnlyState.WaitingForVpn)
            return
        }

        val key = desired.runtimeKey()
        if (current?.key == key) {
            firewall.replaceClients(current!!.firewallId, desired.clients)
            return
        }

        stopCurrent()
        publish(ProxyOnlyState.Starting)

        // Deny first so wildcard/native listener startup never creates an open-proxy window.
        val firewallId = firewall.startDenied(
            downstreams = desired.downstreams,
            tcpPort = desired.settings.port,
            udpPort = desired.settings.port,
            generation = upstream.generation,
        )

        try {
            val native = service.start(
                settings = desired.settings,
                upstream = upstream,
                downstreams = desired.downstreams,
            )
            service.probe(native)
            firewall.allowClients(firewallId, desired.clients)
            current = AppliedProxyState(key, native, firewallId)
            publish(native.runningState())
        } catch (e: Throwable) {
            service.stopSafely()
            firewall.stop(firewallId)
            publish(ProxyOnlyState.Error("Failed to start proxy", e))
        }
    }

    private suspend fun stopCurrent() {
        val applied = current ?: return
        current = null
        firewall.denyAll(applied.firewallId)
        service.stop(applied.nativeHandle)
        firewall.stop(applied.firewallId)
    }
}
```

## 3. JNI facade

```kotlin
internal object ProxyNative {
    init {
        System.loadLibrary("vpnhotspot_proxy")
    }

    external fun start(
        configYaml: ByteArray,
        networkHandle: Long,
        generation: Long,
        failClosed: Boolean,
    ): Long

    external fun replaceNetwork(
        instance: Long,
        networkHandle: Long,
        generation: Long,
    ): Boolean

    external fun replaceAllowedClients(
        instance: Long,
        addresses: Array<String>,
    )

    external fun readStats(instance: Long): NativeProxyStats

    external fun stop(instance: Long)
}
```

Never pass username/password through command-line arguments. Keep the generated native configuration in memory or in an app-private file with restrictive permissions and guaranteed deletion.

## 4. Hev socket hook proposal

The exact Hev internal types must be verified against the pinned source.

```c
/* vpnhotspot_socket_hook.h */
#pragma once

#include <stdint.h>

typedef struct {
    uint64_t network_handle;
    uint64_t generation;
    int fail_closed;
} vpnhotspot_network_state_t;

int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state);
```

```c
#include <android/multinetwork.h>
#include <errno.h>

int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state) {
    (void)family;
    (void)type;

    if (state == NULL || state->network_handle == 0)
        return state != NULL && state->fail_closed ? -ENONET : 0;

    if (android_setsocknetwork((net_handle_t)state->network_handle, fd) != 0)
        return -errno;

    return 0;
}
```

Hook placement:

```c
int fd = socket(family, type, protocol);
if (fd < 0)
    return -errno;

int rc = server->socket_prepare(fd, family, type, server->socket_prepare_data);
if (rc < 0) {
    close(fd);
    return rc;
}

/* Only now may connect/sendto begin. */
```

The hook must cover all socket creation paths, including retries and IPv4/IPv6 fallback.

## 5. VPN-specific DNS callback

A Kotlin resolver avoids reproducing Android resolver integration in C:

```kotlin
class NetworkResolver(private val network: Network) {
    suspend fun resolve(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        network.getAllByName(host).toList()
    }
}
```

A JNI callback is possible, but callbacks from native worker threads require careful JVM attachment and exception handling. A lower-risk prototype may resolve the SOCKS domain request in Kotlin before native connect, provided the protocol layer can accept resolved candidates without performing its own DNS query.

A native alternative should use Android network-aware resolver APIs and remain fail-closed.

## 6. Fail-closed native checks

```c
static int network_snapshot(proxy_instance_t *instance,
                            uint64_t *handle,
                            uint64_t *generation) {
    pthread_rwlock_rdlock(&instance->network_lock);
    *handle = instance->network.network_handle;
    *generation = instance->network.generation;
    int valid = instance->network.valid;
    pthread_rwlock_unlock(&instance->network_lock);
    return valid ? 0 : -ENONET;
}
```

Every new connection takes a snapshot. When Kotlin reports VPN loss or a new generation:

```c
void proxy_replace_network(proxy_instance_t *instance,
                           uint64_t handle,
                           uint64_t generation) {
    pthread_rwlock_wrlock(&instance->network_lock);
    instance->network.valid = handle != 0;
    instance->network.network_handle = handle;
    instance->network.generation = generation;
    pthread_rwlock_unlock(&instance->network_lock);

    /* Cancel/close sessions created for an older generation. */
    proxy_cancel_sessions_before(instance, generation);
}
```

## 7. Proto sketch for firewall lifecycle

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
  repeated string downstream_interfaces = 1;
  uint32 tcp_port = 2;
  uint32 udp_port = 3;
  repeated ClientConfig allowed_clients = 4;
  uint64 generation = 5;
  bool deny_by_default = 6;
}
```

The call should remain active for the firewall runtime lifetime. Existing `CancelCommand` semantics can terminate it.

## 8. Rust runtime sketch

```rust
pub(crate) struct Runtime {
    call_id: u64,
    config: ProxyFirewallConfig,
    applied: Vec<AppliedMutation>,
    counters: ProxyCounters,
}

impl Runtime {
    pub(crate) async fn start(
        call_id: u64,
        config: ProxyFirewallConfig,
        netlink: RequestConnection,
    ) -> io::Result<Self> {
        let mut runtime = Self {
            call_id,
            config: config.clone(),
            applied: Vec::new(),
            counters: ProxyCounters::default(),
        };
        runtime.reconcile(None, &config, netlink).await?;
        Ok(runtime)
    }

    pub(crate) async fn replace(
        &mut self,
        next: ProxyFirewallConfig,
    ) -> io::Result<()> {
        self.reconcile(Some(&self.config), &next).await?;
        self.config = next;
        Ok(())
    }

    pub(crate) async fn stop(mut self) {
        self.rollback().await;
    }
}
```

Desired firewall policy pseudo-code:

```text
create app-owned proxy input chain
jump from INPUT only for tcp/udp destination proxy ports
accept established/related
for each allowed client:
  match input interface
  match source IPv4
  count by MAC-facing identity
  accept
reject
```

Use project-owned chain names, mutation ledgers and deterministic Clean conventions rather than raw shell commands embedded in Kotlin.

## 9. Tethering mode integration sketch

```kotlin
private class Downstream(
    caller: Any,
    downstream: String,
    val mode: SharingMode,
) : RoutingManager(caller, downstream) {
    override fun Routing.configure() {
        ipv6Mode = RoutingManager.ipv6Mode
    }
}
```

Do not instantiate `RoutingManager` in pure `PROXY_ONLY` mode:

```kotlin
when (sharingMode) {
    SharingMode.VPN_ROUTING -> startRouting(downstream)
    SharingMode.PROXY_ONLY -> proxyOnlyController.expose(downstream)
    SharingMode.VPN_ROUTING_AND_PROXY -> {
        startRouting(downstream)
        proxyOnlyController.expose(downstream)
    }
}
```

The actual implementation should avoid putting UI settings reads directly in the service loop. Provide a reconciled desired-state object.

## 10. Credentials

```kotlin
object ProxyCredentialGenerator {
    private val random = SecureRandom()

    fun generate(): ProxyCredentials {
        val user = ByteArray(9).also(random::nextBytes)
        val pass = ByteArray(24).also(random::nextBytes)
        return ProxyCredentials(
            username = Base64.getUrlEncoder().withoutPadding().encodeToString(user),
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(pass),
        )
    }
}
```

Requirements:

- never include credentials in `toString()`;
- redact them from crash reports;
- clear temporary byte arrays where practical;
- show password only after explicit user action;
- require authentication whenever listening on a tethering address.

## 11. Generated client snippet

```kotlin
fun ProxyEndpoint.toFlClash(credentials: ProxyCredentials) = """
proxies:
  - name: PhoneVPN
    type: socks5
    server: $host
    port: $port
    username: ${credentials.username}
    password: ${credentials.password}
    udp: true
""".trimIndent()
```

Do not log the returned string.
