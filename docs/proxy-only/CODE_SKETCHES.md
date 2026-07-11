# Proxy-only code sketches

These snippets define contracts and sequencing. They are intentionally incomplete and must be adapted to current project APIs.

## 1. Settings, states and runtime identity

```kotlin
enum class SharingMode {
    VPN_ROUTING,
    PROXY_ONLY,
}

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
    val downstreams: List<Pair<String, String>>,
    val backendVersion: Int,
)

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object ServiceStarting : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data object StartingBackend : ProxyOnlyState
    data class MultipleVpnCandidates(val count: Int) : ProxyOnlyState
    data object VpnPermissionDenied : ProxyOnlyState
    data class Running(val endpoint: ProxyEndpoint) : ProxyOnlyState
    data class FailClosed(val reason: String) : ProxyOnlyState
    data class CleanupDegraded(val failures: List<CleanupFailure>) : ProxyOnlyState
    data object Stopping : ProxyOnlyState
}
```

Client lists are excluded from `RuntimeKey`; client changes replace ACL/firewall state without restarting the listener.

## 2. Deterministic VPN-only selector

```kotlin
sealed interface VpnSelection {
    data object None : VpnSelection
    data class One(val upstream: ProxyVpnUpstream) : VpnSelection
    data class Multiple(val candidates: List<ProxyVpnUpstream>) : VpnSelection
}

class ProxyVpnSelector(
    private val connectivity: ConnectivityManager,
) {
    fun select(candidates: Collection<Upstream>): VpnSelection {
        val usable = candidates.mapNotNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate.network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            ProxyVpnUpstream(
                network = candidate.network,
                handle = candidate.network.networkHandle,
                interfaces = candidate.properties.allInterfaceNames.toSortedSet(),
            )
        }
        return when (usable.size) {
            0 -> VpnSelection.None
            1 -> VpnSelection.One(usable.single())
            else -> VpnSelection.Multiple(usable)
        }
    }
}
```

A separate app-UID bind probe determines whether VPN Hotspot is permitted by the VPN app policy. The MVP never sorts transient handles to choose among multiple usable VPNs.

## 3. Service/backend ownership

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

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(handle: ProxyBackendHandle): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

`ProxyOnlyController` owns desired-state reconciliation. `ProxyService` is the sole backend/native-handle owner.

## 4. Persistent foreground service

```kotlin
class ProxyService : Service() {
    private lateinit var backend: ProxyBackend
    private var backendHandle: ProxyBackendHandle? = null
    private var featureEnabled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, waitingNotification(ProxyOnlyState.ServiceStarting))
        featureEnabled = true
        return START_NOT_STICKY
    }

    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport {
        val cleanup = stopBackendInternal("waiting: $state")
        updateNotification(waitingNotification(state))
        return cleanup
    }

    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle {
        check(featureEnabled)
        check(backendHandle == null)
        val handle = backend.start(config)
        backendHandle = handle
        return ProxyServiceHandle(handle.id)
    }

    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport {
        val current = backendHandle ?: return CleanupReport.empty()
        if (current.id != handle.id) return CleanupReport.staleHandle(handle.id)
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.stopBackend")
    }

    suspend fun emergencyCloseListener(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.empty()
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.emergencyClose", reason)
    }

    suspend fun stopFeature(reason: String): CleanupReport {
        featureEnabled = false
        val cleanup = emergencyCloseListener("feature stop: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return cleanup
    }
}
```

The service is initially started only from an Android-permitted user enable action. It remains foreground but listener-free in waiting/fail-closed states. A disabled desired state never calls `activateFeature()`.

## 5. Applied state and cleanup debt

```kotlin
data class AppliedProxyState(
    val key: RuntimeKey,
    var firewall: ProxyFirewallHandle? = null,
    var service: ProxyServiceHandle? = null,
    var complete: Boolean = false,
)

data class CleanupDebt(
    val firewall: ProxyFirewallHandle?,
    val service: ProxyServiceHandle?,
    val failures: List<CleanupFailure>,
    val requiresDaemonClean: Boolean,
)
```

Restart is forbidden while `CleanupDebt` exists.

## 6. Exception-safe and terminal-safe worker

```kotlin
class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    private val desired = Channel<DesiredProxyState>(Channel.CONFLATED)
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null

    fun start(source: Flow<DesiredProxyState>): Job = scope.launch {
        val collector = launch {
            source.collect { desired.send(it.normalized()) }
        }
        try {
            for (snapshot in desired) {
                try {
                    withContext(NonCancellable) {
                        withTimeout(TRANSACTION_TIMEOUT) {
                            reconcile(snapshot)
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    withContext(NonCancellable) {
                        recoverFromIterationFailure(timeout)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        recoverFromIterationFailure(failure)
                    }
                }
            }
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                terminalStop("controller worker terminated")
            }
        }
    }

    private suspend fun reconcile(next: DesiredProxyState) {
        if (!next.settings.enabled) {
            terminalStop("disabled")
            publishSafely(ProxyOnlyState.Disabled)
            return
        }

        if (!serviceActivated) {
            publishSafely(ProxyOnlyState.ServiceStarting)
            service.activateFeature(ProxyOnlyState.ServiceStarting)
            serviceActivated = true
        }

        cleanupDebt?.let {
            retryCleanupDebt(it)
            if (cleanupDebt != null) {
                service.enterWaiting(ProxyOnlyState.CleanupDegraded(it.failures))
                publishSafely(ProxyOnlyState.CleanupDegraded(it.failures))
                return
            }
        }

        if (next.downstreams.isEmpty()) {
            stopApplied("no tethering", retainService = true)
            service.enterWaiting(ProxyOnlyState.WaitingForTethering)
            publishSafely(ProxyOnlyState.WaitingForTethering)
            return
        }

        val upstream = when (val selection = next.vpnSelection) {
            VpnSelection.None -> {
                stopApplied("no VPN", retainService = true)
                service.enterWaiting(ProxyOnlyState.WaitingForVpn)
                publishSafely(ProxyOnlyState.WaitingForVpn)
                return
            }
            is VpnSelection.Multiple -> {
                stopApplied("multiple VPNs", retainService = true)
                val state = ProxyOnlyState.MultipleVpnCandidates(selection.candidates.size)
                service.enterWaiting(state)
                publishSafely(state)
                return
            }
            is VpnSelection.One -> selection.upstream
        }

        if (!next.daemonHealthy) {
            stopApplied("daemon unavailable", daemonAvailable = false, retainService = true)
            val state = ProxyOnlyState.FailClosed("Root daemon unavailable")
            service.enterWaiting(state)
            publishSafely(state)
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            firewall.replace(current.firewall!!, next.firewallConfig(denied = false))
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(next.runningState())
            return
        }

        stopApplied("runtime key changed", retainService = true)
        publishSafely(ProxyOnlyState.StartingBackend)

        val partial = AppliedProxyState(key)
        applied = partial

        partial.firewall = firewall.start(next.firewallConfig(denied = true))
        partial.service = service.startBackend(next.backendConfig(upstream))

        val probes = service.runOutboundProbes(partial.service!!)
        check(probes.allRequiredPassed)

        firewall.replace(partial.firewall!!, next.firewallConfig(denied = false))
        partial.complete = true
        publishSafely(next.runningState())
    }

    private suspend fun recoverFromIterationFailure(failure: Throwable) {
        val report = stopApplied("reconcile failure", retainService = true)
        reporter.report("proxy.reconcile", failure, report.failures)
        val state = if (cleanupDebt == null) {
            ProxyOnlyState.FailClosed("Proxy stopped after internal failure")
        } else {
            ProxyOnlyState.CleanupDegraded(cleanupDebt!!.failures)
        }
        service.enterWaiting(state)
        publishSafely(state)
    }

    private suspend fun stopApplied(
        reason: String,
        daemonAvailable: Boolean = true,
        retainService: Boolean,
    ): CleanupReport {
        val current = applied
        applied = null
        if (current == null) {
            val emergency = service.emergencyCloseListener("no applied handle: $reason")
            return emergency
        }

        val failures = mutableListOf<CleanupFailure>()

        suspend fun step(name: String, block: suspend () -> CleanupReport) {
            try {
                val result = withTimeout(CLEANUP_STEP_TIMEOUT) { block() }
                failures += result.failures.map { it.withParent(name) }
            } catch (t: Throwable) {
                failures += CleanupFailure(name, t)
                reporter.report("proxy.cleanup.$name", t)
            }
        }

        if (daemonAvailable && current.firewall != null) {
            step("deny") {
                firewall.denyAll(current.firewall!!)
                CleanupReport.empty()
            }
        }

        if (current.service != null) {
            step("backend_stop") { service.stopBackend(current.service!!) }
        }

        if (failures.any { it.step == "backend_stop" } || current.service == null) {
            step("emergency_close") { service.emergencyCloseListener(reason) }
        }

        if (current.firewall != null) {
            step("firewall_stop") {
                firewall.stop(current.firewall!!)
                CleanupReport.empty()
            }
        }

        val report = CleanupReport(failures)
        if (failures.isNotEmpty()) {
            cleanupDebt = CleanupDebt(
                firewall = current.firewall,
                service = current.service,
                failures = failures,
                requiresDaemonClean = !daemonAvailable || failures.any { it.step.contains("firewall") },
            )
        }

        if (!retainService && serviceActivated) {
            step("feature_stop") { service.stopFeature(reason) }
            serviceActivated = false
        }
        return report
    }

    private suspend fun terminalStop(reason: String) {
        stopApplied(reason, retainService = false)
        if (serviceActivated) {
            service.stopFeature(reason)
            serviceActivated = false
        }
    }

    private suspend fun retryCleanupDebt(debt: CleanupDebt) {
        val emergency = service.emergencyCloseListener("cleanup debt retry")
        if (emergency.failures.isNotEmpty()) return
        if (debt.requiresDaemonClean) {
            val cleaned = firewall.cleanOrDenyBeforeRestart()
            if (!cleaned) return
        }
        cleanupDebt = null
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

This is a sequencing sketch, not compile-ready code. A production implementation should avoid duplicate service-stop calls and keep cleanup handles/debt in a single explicit state machine.

## 7. Outbound-only probes

```kotlin
enum class ProbeKind {
    APP_UID_BIND,
    OUTBOUND_TCP,
    OUTBOUND_UDP,
    VPN_DNS,
    INTERNAL_LISTENER_READY,
}

data class ProbeReport(val results: Map<ProbeKind, ProbeResult>) {
    val allRequiredPassed: Boolean
        get() = ProbeKind.entries.all { results[it]?.success == true }
}
```

`INTERNAL_LISTENER_READY` checks native bind/listen state without traversing tethered-interface iptables. External client reachability is tested only after allow commit.

## 8. Testable Hev socket hook

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

Android uses `android_setsocknetwork()`. Host CI injects a fake callback and asserts the hook occurs before connect/send on every path.

## 9. UDP topology observations

```kotlin
enum class UdpSocketRole {
    CLIENT_RELAY,
    INTERNET_FACING,
    SHARED_RELAY_AND_INTERNET,
}

data class UdpSocketObservation(
    val associationId: Long,
    val fd: Int,
    val role: UdpSocketRole,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress?,
    val boundNetworkHandle: Long?,
)

data class UdpTopologyReport(
    val observations: List<UdpSocketObservation>,
    val returnedBindAddresses: List<InetSocketAddress>,
    val replyIngressInterfaces: Set<String>,
    val replyConntrackStates: Set<String>,
)
```

The firewall return rule remains provisional until this report is correlated with packet capture and conntrack evidence.

## 10. Firewall proto sketch

```proto
message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool deny_all_ipv6 = 7;
  optional VerifiedUdpReturnPolicy udp_return_policy = 8;
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

`VerifiedUdpReturnPolicy` is absent until Phase 0 proves exact semantics. The daemon must reuse the existing `IptablesRule` ledger and deterministic cleanup.
