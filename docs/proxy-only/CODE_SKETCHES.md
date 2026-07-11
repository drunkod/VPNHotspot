# Proxy-only code sketches

These snippets illustrate ownership and sequencing. They are not intended to compile unchanged.

## 1. Models

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
    val downstreams: List<Pair<String, String>>,
    val backendVersion: Int,
)

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object ServiceStarting : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data class MultipleVpnCandidates(val count: Int) : ProxyOnlyState
    data object VpnPermissionDenied : ProxyOnlyState
    data object StartingBackend : ProxyOnlyState
    data class Running(val endpoint: ProxyEndpoint) : ProxyOnlyState
    data class FailClosed(val reason: String) : ProxyOnlyState
    data class CleanupDegraded(val failures: List<CleanupFailure>) : ProxyOnlyState
}
```

Client lists are not part of `RuntimeKey`; they replace ACL/firewall state without restarting the listener.

## 2. VPN-only selection

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
            val capabilities = connectivity.getNetworkCapabilities(candidate.network)
                ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
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

A separate app-UID bind probe distinguishes `VpnPermissionDenied`. Multiple usable candidates fail closed; transient network handles are not a user-selection policy.

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

`ProxyOnlyController` never owns a backend/native handle. `ProxyService` is the sole backend owner.

## 4. Persistent service sketch

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
        val result = closeCurrentBackend("waiting: $state")
        updateNotification(waitingNotification(state))
        return result
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

    suspend fun emergencyCloseListener(reason: String): CleanupReport =
        closeCurrentBackend("emergency: $reason")

    suspend fun stopFeature(reason: String): CleanupReport {
        featureEnabled = false
        val result = closeCurrentBackend("feature stop: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return result
    }

    private suspend fun closeCurrentBackend(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.empty()
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.closeCurrentBackend", reason)
    }
}
```

Initial service activation comes only from an Android-permitted user enable action. Waiting/fail-closed states keep the FGS but have no listener. Disabled state never activates the service.

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

No backend start is allowed while cleanup debt exists.

## 6. Exception-safe worker

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
                runIteration(snapshot)
            }
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                terminalStopSafely("controller worker terminated")
            }
        }
    }

    private suspend fun runIteration(snapshot: DesiredProxyState) {
        try {
            withContext(NonCancellable) {
                withTimeout(TRANSACTION_TIMEOUT) {
                    reconcile(snapshot)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            withContext(NonCancellable) { recoverSafely(timeout) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) { recoverSafely(failure) }
        }
    }

    private suspend fun reconcile(next: DesiredProxyState) {
        // Disabled must be checked before any FGS activation.
        if (!next.settings.enabled) {
            terminalStopSafely("disabled")
            publishSafely(ProxyOnlyState.Disabled)
            return
        }

        if (!serviceActivated) {
            publishSafely(ProxyOnlyState.ServiceStarting)
            service.activateFeature(ProxyOnlyState.ServiceStarting)
            serviceActivated = true
        }

        cleanupDebt?.let { debt ->
            retryCleanupDebtSafely(debt)
            cleanupDebt?.let { unresolved ->
                val state = ProxyOnlyState.CleanupDegraded(unresolved.failures)
                service.enterWaiting(state)
                publishSafely(state)
                return
            }
        }

        if (next.downstreams.isEmpty()) {
            enterWaiting(ProxyOnlyState.WaitingForTethering, "no tethering")
            return
        }

        val upstream = when (val selection = next.vpnSelection) {
            VpnSelection.None -> {
                enterWaiting(ProxyOnlyState.WaitingForVpn, "no VPN")
                return
            }
            is VpnSelection.Multiple -> {
                enterWaiting(
                    ProxyOnlyState.MultipleVpnCandidates(selection.candidates.size),
                    "multiple VPNs",
                )
                return
            }
            is VpnSelection.One -> selection.upstream
        }

        if (!next.daemonHealthy) {
            val cleanup = cleanupApplied(
                reason = "daemon unavailable",
                daemonAvailable = false,
                stopFeature = false,
            )
            recordDebtIfNeeded(cleanup)
            val state = cleanupDebt?.let { ProxyOnlyState.CleanupDegraded(it.failures) }
                ?: ProxyOnlyState.FailClosed("Root daemon unavailable")
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

        val oldCleanup = cleanupApplied("runtime key changed", stopFeature = false)
        recordDebtIfNeeded(oldCleanup)
        check(cleanupDebt == null) { "Cleanup debt blocks backend restart" }

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

    private suspend fun enterWaiting(state: ProxyOnlyState, reason: String) {
        val cleanup = cleanupApplied(reason, stopFeature = false)
        recordDebtIfNeeded(cleanup)
        val published = cleanupDebt?.let { ProxyOnlyState.CleanupDegraded(it.failures) } ?: state
        service.enterWaiting(published)
        publishSafely(published)
    }

    private suspend fun recoverSafely(original: Throwable) {
        try {
            val cleanup = cleanupApplied("reconcile failure", stopFeature = false)
            recordDebtIfNeeded(cleanup)
            reportSafely("proxy.reconcile", original, cleanup.failures)
            val state = cleanupDebt?.let { ProxyOnlyState.CleanupDegraded(it.failures) }
                ?: ProxyOnlyState.FailClosed("Proxy stopped after internal failure")
            service.enterWaiting(state)
            publishSafely(state)
        } catch (recoveryFailure: Throwable) {
            // Recovery itself must not kill the resource worker.
            reportSafely("proxy.recovery", recoveryFailure)
            try {
                val emergency = service.emergencyCloseListener("recovery failure")
                recordDebtIfNeeded(emergency)
            } catch (emergencyFailure: Throwable) {
                reportSafely("proxy.emergency_close", emergencyFailure)
                cleanupDebt = cleanupDebt ?: CleanupDebt(
                    firewall = applied?.firewall,
                    service = applied?.service,
                    failures = listOf(CleanupFailure("emergency_close", emergencyFailure)),
                    requiresDaemonClean = true,
                )
            }
            publishSafely(ProxyOnlyState.CleanupDegraded(cleanupDebt?.failures.orEmpty()))
        }
    }

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean = true,
        stopFeature: Boolean,
    ): CleanupReport {
        val current = applied
        applied = null
        val accumulator = CleanupAccumulator(reporter)

        if (daemonAvailable && current?.firewall != null) {
            accumulator.step("deny") {
                firewall.denyAll(current.firewall!!)
                CleanupReport.empty()
            }
        }

        if (current?.service != null) {
            accumulator.step("backend_stop") {
                service.stopBackend(current.service!!)
            }
        }

        if (current?.service == null || accumulator.failed("backend_stop")) {
            accumulator.step("emergency_close") {
                service.emergencyCloseListener(reason)
            }
        }

        if (current?.firewall != null) {
            accumulator.step("firewall_stop") {
                firewall.stop(current.firewall!!)
                CleanupReport.empty()
            }
        }

        if (stopFeature && serviceActivated) {
            accumulator.step("feature_stop") {
                service.stopFeature(reason)
            }
            if (!accumulator.failed("feature_stop")) serviceActivated = false
        }

        return accumulator.report(
            unresolvedFirewall = current?.firewall,
            unresolvedService = current?.service,
            requiresDaemonClean = !daemonAvailable,
        )
    }

    private suspend fun terminalStopSafely(reason: String) {
        try {
            val cleanup = cleanupApplied(reason, stopFeature = true)
            recordDebtIfNeeded(cleanup)
            if (serviceActivated) {
                // A failed feature stop remains explicit debt.
                val extra = service.stopFeature(reason)
                recordDebtIfNeeded(extra)
                if (extra.failures.isEmpty()) serviceActivated = false
            }
        } catch (failure: Throwable) {
            reportSafely("proxy.terminal_stop", failure)
            try {
                recordDebtIfNeeded(service.emergencyCloseListener("terminal stop failure"))
            } catch (emergencyFailure: Throwable) {
                reportSafely("proxy.terminal_emergency", emergencyFailure)
            }
        }
    }

    private suspend fun retryCleanupDebtSafely(debt: CleanupDebt) {
        try {
            val emergency = service.emergencyCloseListener("cleanup debt retry")
            if (emergency.failures.isNotEmpty()) return
            if (debt.requiresDaemonClean && !firewall.cleanOrDenyBeforeRestart()) return
            cleanupDebt = null
        } catch (failure: Throwable) {
            reportSafely("proxy.cleanup_debt", failure)
        }
    }

    private fun recordDebtIfNeeded(report: CleanupReport) {
        report.debt?.let { cleanupDebt = it }
    }

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try {
            stateSink.publish(state)
        } catch (failure: Throwable) {
            reportSafely("proxy.state_publish", failure)
        }
    }

    private fun reportSafely(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    ) {
        try {
            reporter.report(category, failure, cleanupFailures)
        } catch (_: Throwable) {
            // Last-resort platform logging must not throw back into the worker.
        }
    }
}
```

`CleanupAccumulator` is a helper that:

- applies a timeout to each step;
- attempts every step;
- merges nested `CleanupReport` failures;
- preserves unresolved handles;
- produces `CleanupDebt` when a safety-relevant step fails.

The sketch deliberately favors explicit safety state over minimal code.

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

`INTERNAL_LISTENER_READY` reads native bind/listen status without traversing tethered-interface firewall rules. External reachability is tested after allow commit.

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

Android uses `android_setsocknetwork()`. Host CI injects a fake callback and asserts hook ordering on every path.

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

The firewall return rule remains absent until this report is correlated with packet capture and conntrack evidence.

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

`VerifiedUdpReturnPolicy` is absent until Phase 0 proves exact semantics. The daemon reuses the existing `IptablesRule` ledger and deterministic cleanup.
