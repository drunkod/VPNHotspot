# Proxy-only code sketches

These snippets define ownership and sequencing. They are not expected to compile unchanged.

## 1. Settings, VPN candidate and runtime key

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

data class ProxyVpnUpstream(
    val network: Network,
    val handle: Long,
    val interfaces: Set<String>,
)

data class RuntimeKey(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val credentialsVersion: Long,
    val vpnNetworkHandle: Long,
    val downstreams: List<Pair<String, String>>, // normalized/sorted
    val backendVersion: Int,
)
```

Client lists are not part of `RuntimeKey`; they replace ACL state without restarting the listener.

## 2. Deterministic VPN-only selection

```kotlin
sealed interface ProxyVpnSelection {
    data object None : ProxyVpnSelection
    data class Selected(val upstream: ProxyVpnUpstream) : ProxyVpnSelection
    data class Rejected(val reason: ProxyVpnFailure) : ProxyVpnSelection
}

sealed interface ProxyVpnFailure {
    data object NoVpn : ProxyVpnFailure
    data object NonVpnCandidate : ProxyVpnFailure
    data object VpnPermissionDenied : ProxyVpnFailure
    data object MultipleVpnCandidates : ProxyVpnFailure
}

class ProxyVpnSelector(
    private val connectivity: ConnectivityManager,
    private val bindProbe: AppUidBindProbe,
) {
    suspend fun select(candidates: List<Upstream>): ProxyVpnSelection {
        val usable = candidates.mapNotNull { candidate ->
            val capabilities = connectivity.getNetworkCapabilities(candidate.network)
                ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
            if (!bindProbe.canUse(candidate.network)) return@mapNotNull null
            ProxyVpnUpstream(
                network = candidate.network,
                handle = candidate.network.networkHandle,
                interfaces = candidate.properties.allInterfaceNames.toSortedSet(),
            )
        }
        return when (usable.size) {
            0 -> ProxyVpnSelection.None
            1 -> ProxyVpnSelection.Selected(usable.single())
            else -> ProxyVpnSelection.Rejected(ProxyVpnFailure.MultipleVpnCandidates)
        }
    }
}
```

A production implementation should preserve why a candidate failed so app-policy denial can be distinguished from no VPN.

## 3. Service/backend ownership

```kotlin
interface ProxyServiceClient {
    suspend fun ensureServiceActive(): Unit
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyServiceHandle): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyBackendHandle): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

`ProxyOnlyController` depends on `ProxyServiceClient`, not `ProxyBackend`. `ProxyService` is the only class that owns a backend handle.

## 4. Persistent foreground service

```kotlin
class ProxyService : Service(), CoroutineScope {
    private var backendHandle: ProxyBackendHandle? = null
    private lateinit var backend: ProxyBackend

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceNotification.startForeground(this, waitingNotification())
        // The listener is started only by an explicit reconciler command.
        return START_NOT_STICKY
    }

    suspend fun enterWaiting(state: ProxyOnlyState) {
        stopBackendInternal("waiting: $state")
        ServiceNotification.update(this, waitingNotification(state))
        // Do not stopSelf(): VPN/daemon recovery may happen while UI is backgrounded.
    }

    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle {
        check(backendHandle == null)
        val native = backend.start(config)
        backendHandle = native
        return ProxyServiceHandle(native.id)
    }

    suspend fun emergencyCloseListener(reason: String): CleanupReport =
        stopBackendInternal("emergency: $reason")

    private suspend fun stopBackendInternal(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.empty()
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.stopBackend", reason)
    }
}
```

The service is initially started only from an Android-permitted user action. Once active, it remains alive without a listener in waiting/fail-closed states.

## 5. Exception-safe desired-state worker

```kotlin
class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    private val desired = Channel<DesiredProxyState>(Channel.CONFLATED)
    private var applied: AppliedProxyState? = null

    fun start(source: Flow<DesiredProxyState>): Job = scope.launch {
        val collector = launch {
            source.collect { desired.send(it.normalized()) }
        }
        try {
            for (snapshot in desired) {
                try {
                    withContext(NonCancellable) {
                        withTimeout(TRANSACTION_TIMEOUT) { reconcile(snapshot) }
                    }
                } catch (t: Throwable) {
                    withContext(NonCancellable) {
                        recoverFromIterationFailure(t)
                    }
                }
            }
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                stopApplied(reason = "controller worker terminated")
            }
        }
    }

    private suspend fun reconcile(next: DesiredProxyState) {
        service.ensureServiceActive()

        if (!next.settings.enabled) {
            stopApplied("disabled")
            publishSafely(ProxyOnlyState.Disabled)
            return
        }
        if (next.downstreams.isEmpty()) {
            stopApplied("no tethering")
            publishSafely(ProxyOnlyState.WaitingForTethering)
            return
        }
        val upstream = next.validatedVpn ?: run {
            stopApplied("VPN unavailable")
            publishSafely(ProxyOnlyState.FailClosed(next.vpnFailure))
            return
        }
        if (!next.daemonHealthy) {
            stopApplied("daemon unavailable", daemonAvailable = false)
            publishSafely(ProxyOnlyState.FailClosed("Root daemon unavailable"))
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.isComplete == true && current.key == key) {
            // Either call may throw; the outer iteration boundary performs fail-closed recovery.
            firewall.replace(current.firewall!!, next.firewallConfig(denied = false))
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(current.runningState(next))
            return
        }

        stopApplied("runtime key changed")
        publishSafely(ProxyOnlyState.Starting)

        val partial = AppliedProxyState(key = key)
        applied = partial

        val firewallHandle = firewall.start(next.firewallConfig(denied = true))
        partial.firewall = firewallHandle              // record immediately

        val serviceHandle = service.startBackend(next.backendConfig(upstream))
        partial.service = serviceHandle                // record immediately

        val probes = service.runOutboundProbes(serviceHandle)
        check(probes.allRequiredPassed)

        firewall.replace(firewallHandle, next.firewallConfig(denied = false))
        partial.isComplete = true
        publishSafely(partial.runningState(next))
    }

    private suspend fun recoverFromIterationFailure(failure: Throwable) {
        val cleanup = stopApplied("reconcile exception: ${failure::class.java.simpleName}")
        reporter.report("proxy.reconcile", failure, cleanup.failures)
        publishSafely(ProxyOnlyState.Error("Proxy stopped after internal failure", failure))
    }

    private suspend fun stopApplied(
        reason: String,
        daemonAvailable: Boolean = true,
    ): CleanupReport {
        val current = applied ?: return CleanupReport.empty()
        applied = null
        val failures = mutableListOf<CleanupFailure>()

        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                withTimeout(CLEANUP_STEP_TIMEOUT) { block() }
            } catch (t: Throwable) {
                failures += CleanupFailure(name, t)
                reporter.report("proxy.cleanup.$name", t)
            }
        }

        if (daemonAvailable && current.firewall != null) {
            step("deny") { firewall.denyAll(current.firewall!!) }
        }
        if (current.service != null) {
            step("service_stop") { service.stopBackend(current.service!!).throwIfCritical() }
        } else {
            step("service_emergency_close") {
                service.emergencyCloseListener(reason).throwIfCritical()
            }
        }
        if (current.firewall != null) {
            step("firewall_stop") { firewall.stop(current.firewall!!) }
        }

        return CleanupReport(failures)
    }

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try {
            stateSink.publish(state)
        } catch (t: Throwable) {
            reporter.report("proxy.state_publish", t)
        }
    }
}
```

Important properties:

- resource operations have timeouts even inside `NonCancellable`;
- partial handles are recorded immediately;
- every cleanup step is attempted;
- cleanup failures are visible;
- state publication cannot kill the worker;
- scope cancellation triggers terminal cleanup.

## 6. Outbound-only probe model

```kotlin
enum class ProbeKind {
    APP_UID_BIND,
    OUTBOUND_TCP,
    OUTBOUND_UDP,
    VPN_DNS,
    INTERNAL_LISTENER_READY,
}

data class ProbeReport(val results: Map<ProbeKind, ProbeResult>) {
    val allRequiredPassed: Boolean get() = ProbeKind.entries.all { results[it]?.success == true }
}
```

`INTERNAL_LISTENER_READY` checks native bind/listen state without connecting through the downstream firewall. Client reachability is tested only after allow rules are committed.

## 7. Hev socket hook with host shim

```c
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
    const vpnhotspot_network_state_t *state)
{
    if (state == NULL || state->network_handle == 0 || state->bind_fn == NULL)
        return state != NULL && state->fail_closed ? -ENONET : 0;
    return state->bind_fn(state->network_handle, fd, state->bind_opaque);
}
```

Android adapter:

```c
static int android_bind_network(uint64_t network, int fd, void *opaque) {
    (void)opaque;
    return android_setsocknetwork((net_handle_t)network, fd) == 0 ? 0 : -errno;
}
```

Every outbound socket path calls the hook after `socket()` and before `connect()`/`sendto()`. Host CI injects a fake callback and records ordering.

## 8. UDP topology discovery model

```kotlin
data class UdpSocketObservation(
    val associationId: Long,
    val fd: Int,
    val role: UdpSocketRole,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress?,
    val boundNetworkHandle: Long?,
)

enum class UdpSocketRole {
    CLIENT_RELAY,
    INTERNET_FACING,
    SHARED_RELAY_AND_INTERNET,
}

data class UdpTopologyReport(
    val observations: List<UdpSocketObservation>,
    val returnedBindAddresses: List<InetSocketAddress>,
    val replyIngressInterfaces: Set<String>,
    val replyConntrackStates: Set<String>,
)
```

Phase 0 uses debug-only native instrumentation and packet capture to populate this report. Firewall return rules remain provisional until the topology is proven.

## 9. UDP range and capacity

```kotlin
data class UdpRelayPolicy(
    val range: IntRange,
    val configuredAssociationLimit: Int,
    val onePortPerAssociation: Boolean,
) {
    val usablePorts: Int get() = range.last - range.first + 1
    val effectiveAssociationLimit: Int get() =
        if (onePortPerAssociation) minOf(configuredAssociationLimit, usablePorts)
        else configuredAssociationLimit
}
```

Range exhaustion produces a stable metric/error. The range is never widened at runtime.

## 10. Proto sketch

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

The long-lived firewall call is also the daemon-liveness signal.

## 11. Rust firewall ledger reuse

```rust
use crate::routing::iptables::{
    apply_iptables_batch,
    ensure_iptables_chain_result,
    IptablesRule,
};

pub(crate) struct Runtime {
    config: ProxyFirewallConfig,
    applied: Vec<IptablesRule>,
}
```

IPv4 client allow includes interface, source IPv4 and source MAC. IPv6 denies all proxy ports in the MVP. `firewall_cleanup::clean()` removes proxy jumps/chains.

A verified return-traffic rule may precede terminal UDP reject only after Phase 0 proves the Hev FD/port topology and conntrack state. Do not add a broad allow for the VPN interface.

## 12. Daemon liveness and background recovery

```kotlin
suspend fun onFirewallChannelLost() {
    service.emergencyCloseListener("root daemon channel lost")
    stateSink.publishSafely(ProxyOnlyState.FailClosed("Root daemon unavailable"))
    // ProxyService remains foreground and listener-free.
}

suspend fun onDaemonReturned() {
    firewall.cleanOrEstablishDeny()
    desiredStateSignal.refresh()
    // Existing foreground service handles recovery; no new background FGS start.
}
```

## 13. Credential requirements

- never include credentials in `toString()`;
- redact exceptions and crash reports;
- use credential-encrypted app-private storage;
- increment `credentialsVersion` on regeneration;
- never pass secrets through process arguments;
- reveal/copy only after explicit user action.
