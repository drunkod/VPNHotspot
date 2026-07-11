# Proxy-only code sketches

These snippets illustrate ownership and sequencing. They are intentionally incomplete and must be adapted to current project APIs.

## 1. Settings, state and activation models

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

enum class ActivationSource {
    USER_ENABLE,
    USER_RESUME,
}

data class ActivationGrant(
    val id: UUID,
    val issuedAtElapsedRealtime: Long,
    val source: ActivationSource,
)

interface ActivationGrantConsumer {
    suspend fun consume(grantId: UUID)
}

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

sealed interface FailClosedReason {
    data object RootDaemonUnavailable : FailClosedReason
    data class TcpProbeFailed(val detail: String) : FailClosedReason
    data class UdpProbeFailed(val detail: String) : FailClosedReason
    data class DnsProbeFailed(val detail: String) : FailClosedReason
    data class ListenerNotReady(val detail: String) : FailClosedReason
    data class InternalFailure(val category: String) : FailClosedReason
}

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object ActivationRequired : ProxyOnlyState
    data object ServiceStarting : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data class MultipleVpnCandidates(val count: Int) : ProxyOnlyState
    data object VpnPermissionDenied : ProxyOnlyState
    data object StartingBackend : ProxyOnlyState
    data class Running(val endpoint: ProxyEndpoint) : ProxyOnlyState
    data class FailClosed(val reason: FailClosedReason) : ProxyOnlyState
    data class CleanupDegraded(
        val unresolved: Set<CleanupResource>,
        val failures: List<CleanupFailure>,
        val retryAttempt: Int,
    ) : ProxyOnlyState
}
```

Persisted `settings.enabled` is not an activation grant. A grant is created only by an Android-permitted foreground user action.

## 2. Desired state and controller events

```kotlin
data class DesiredProxyState(
    val settings: ProxyOnlySettings,
    val activationGrant: ActivationGrant?,
    val vpnSelection: VpnSelection,
    val downstreams: List<ManagedDownstream>,
    val allowedClients: List<AllowedClient>,
    val daemonHealthy: Boolean,
)

sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}
```

Snapshots are normalized before entering the controller channel. Client ordering and irrelevant `LinkProperties` churn must not alter the runtime key.

## 3. VPN-only selection

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
            val caps = connectivity.getNetworkCapabilities(candidate.network)
                ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
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

No candidate is selected by sorting transient network handles. A typed app-UID bind probe decides whether the one candidate is usable by VPN Hotspot.

## 4. Service/backend ownership

```kotlin
interface ProxyServiceClient {
    suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation

    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyServiceHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
    suspend fun stopFeature(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyBackendHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

`ProxyOnlyController` never owns a backend/native handle. `ProxyService` is the sole backend owner.

Pre-activation behavior is defined:

```text
enterWaiting / stopBackend / emergencyCloseListener / stopFeature
  when service is inactive
  -> return CleanupReport.noOp(ServiceNotActive)
  -> do not throw
```

The controller still guards these calls with `serviceActivated`; the no-op contract is defense in depth.

## 5. Persistent service sketch

```kotlin
class ProxyService : Service() {
    private lateinit var backend: ProxyBackend
    private var backendHandle: ProxyBackendHandle? = null
    private var featureActive = false

    suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation {
        check(!featureActive)
        validateForegroundGrant(grant)
        startForeground(NOTIFICATION_ID, waitingNotification(initialState))
        featureActive = true
        return ServiceActivation.Active
    }

    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("waiting: $state")
        updateNotification(waitingNotification(state))
        return report
    }

    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle {
        check(featureActive)
        check(backendHandle == null)
        val handle = backend.start(config)
        backendHandle = handle
        return ProxyServiceHandle(handle.id)
    }

    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        if (current.id != handle.id) return CleanupReport.staleHandle(handle.id)
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.stopBackend")
    }

    suspend fun emergencyCloseListener(reason: String): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        return closeCurrentBackend("emergency: $reason")
    }

    suspend fun stopFeature(reason: String): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("feature stop: $reason")
        if (report.hasCriticalFailure) return report
        featureActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return report
    }

    private suspend fun closeCurrentBackend(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        backendHandle = null
        return backend.stop(current).withContext("ProxyService.closeCurrentBackend", reason)
    }
}
```

After activation, the service remains foreground but listener-free in waiting/fail-closed states.

## 6. Applied state and itemized cleanup debt

```kotlin
data class AppliedProxyState(
    val key: RuntimeKey,
    var firewall: ProxyFirewallHandle? = null,
    var service: ProxyServiceHandle? = null,
    var complete: Boolean = false,
)

enum class CleanupResource {
    LISTENER,
    SERVICE_HANDLE,
    FIREWALL_DENY,
    FIREWALL_RUNTIME,
    DAEMON_CLEAN,
    FEATURE_SERVICE,
}

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
) {
    val unresolved: Set<CleanupResource> = buildSet {
        if (listenerClosePending) add(CleanupResource.LISTENER)
        if (serviceHandlePending != null) add(CleanupResource.SERVICE_HANDLE)
        if (firewallDenyPending) add(CleanupResource.FIREWALL_DENY)
        if (firewallStopPending || firewallHandlePending != null) {
            add(CleanupResource.FIREWALL_RUNTIME)
        }
        if (daemonCleanPending) add(CleanupResource.DAEMON_CLEAN)
        if (featureStopPending) add(CleanupResource.FEATURE_SERVICE)
    }

    val isResolved: Boolean get() = unresolved.isEmpty()
}
```

No backend or new firewall runtime may start while debt exists.

## 7. Typed, configuration-aware probes

```kotlin
enum class ProbeKind {
    APP_UID_BIND,
    OUTBOUND_TCP,
    OUTBOUND_UDP,
    VPN_DNS,
    INTERNAL_LISTENER_READY,
}

sealed interface ProbeFailure {
    data object PermissionDenied : ProbeFailure
    data class Timeout(val operation: String) : ProbeFailure
    data class NetworkError(val message: String) : ProbeFailure
    data class Internal(val message: String) : ProbeFailure
}

sealed interface ProbeResult {
    data object Success : ProbeResult
    data class Failed(val failure: ProbeFailure) : ProbeResult
}

data class ProbeRequirements(val udpRequired: Boolean) {
    val requiredKinds: Set<ProbeKind> = buildSet {
        add(ProbeKind.APP_UID_BIND)
        add(ProbeKind.OUTBOUND_TCP)
        add(ProbeKind.VPN_DNS)
        add(ProbeKind.INTERNAL_LISTENER_READY)
        if (udpRequired) add(ProbeKind.OUTBOUND_UDP)
    }
}

data class ProbeReport(val results: Map<ProbeKind, ProbeResult>)

sealed interface ProbeEvaluation {
    data object Success : ProbeEvaluation
    data object VpnPermissionDenied : ProbeEvaluation
    data class TcpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class UdpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class DnsFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class ListenerNotReady(val failure: ProbeFailure) : ProbeEvaluation
    data class Incomplete(val missing: Set<ProbeKind>) : ProbeEvaluation
}

fun ProbeReport.evaluate(requirements: ProbeRequirements): ProbeEvaluation {
    val missing = requirements.requiredKinds - results.keys
    if (missing.isNotEmpty()) return ProbeEvaluation.Incomplete(missing)

    val bind = results.getValue(ProbeKind.APP_UID_BIND)
    if (bind is ProbeResult.Failed && bind.failure is ProbeFailure.PermissionDenied) {
        return ProbeEvaluation.VpnPermissionDenied
    }
    if (bind is ProbeResult.Failed) return ProbeEvaluation.Incomplete(setOf(ProbeKind.APP_UID_BIND))

    fun failure(kind: ProbeKind): ProbeFailure? =
        (results.getValue(kind) as? ProbeResult.Failed)?.failure

    failure(ProbeKind.OUTBOUND_TCP)?.let { return ProbeEvaluation.TcpFailed(it) }
    failure(ProbeKind.VPN_DNS)?.let { return ProbeEvaluation.DnsFailed(it) }
    failure(ProbeKind.INTERNAL_LISTENER_READY)?.let {
        return ProbeEvaluation.ListenerNotReady(it)
    }
    if (requirements.udpRequired) {
        failure(ProbeKind.OUTBOUND_UDP)?.let { return ProbeEvaluation.UdpFailed(it) }
    }
    return ProbeEvaluation.Success
}
```

UDP is not required when `settings.udpEnabled == false`.

## 8. Cleanup accumulator result

```kotlin
data class CleanupOutcome(
    val report: CleanupReport,
    val debt: CleanupDebt?,
)
```

`CleanupAccumulator`:

- applies a timeout to each step;
- attempts all eligible steps;
- records whether each specific resource was resolved;
- merges nested service reports;
- never marks a resource resolved because another resource succeeded;
- returns itemized debt.

## 9. Controller and retry scheduler

```kotlin
class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val activationGrants: ActivationGrantConsumer,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var latestSnapshot: DesiredProxyState? = null
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var nextDebtGeneration = 1L

    fun start(source: Flow<DesiredProxyState>): Job = scope.launch {
        val collector = launch {
            source.collect { snapshot ->
                val normalized = snapshot.normalized()
                latestSnapshot = normalized
                events.send(ControllerEvent.Snapshot(normalized))
            }
        }
        try {
            for (event in events) runIteration(event)
        } finally {
            collector.cancel()
            retryJob?.cancel()
            withContext(NonCancellable) {
                terminalStopSafely("controller worker terminated")
            }
        }
    }

    private suspend fun runIteration(event: ControllerEvent) {
        try {
            withContext(NonCancellable) {
                withTimeout(TRANSACTION_TIMEOUT) {
                    when (event) {
                        is ControllerEvent.Snapshot -> reconcile(event.state)
                        is ControllerEvent.RetryCleanupDebt -> retryDebtEvent(event)
                    }
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

    private suspend fun retryDebtEvent(event: ControllerEvent.RetryCleanupDebt) {
        val debt = cleanupDebt ?: return
        if (debt.generation != event.generation) return
        retryJob = null
        retryCleanupDebtSafely()
        val unresolved = cleanupDebt
        if (unresolved != null) {
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }
        latestSnapshot?.let { reconcile(it) }
    }
```

## 10. Reconciliation with activation grant and typed probes

```kotlin
    private suspend fun reconcile(next: DesiredProxyState) {
        if (!next.settings.enabled) {
            terminalStopSafely("disabled")
            publishSafely(ProxyOnlyState.Disabled)
            return
        }

        if (!serviceActivated) {
            val grant = next.activationGrant
            if (grant == null) {
                publishSafely(ProxyOnlyState.ActivationRequired)
                return
            }
            publishSafely(ProxyOnlyState.ServiceStarting)
            when (service.activateFeature(grant, ProxyOnlyState.ServiceStarting)) {
                ServiceActivation.Active -> {
                    serviceActivated = true
                    activationGrants.consume(grant.id)
                }
                ServiceActivation.ForegroundStartNotAllowed -> {
                    publishSafely(ProxyOnlyState.ActivationRequired)
                    return
                }
            }
        }

        cleanupDebt?.let { debt ->
            retryCleanupDebtSafely()
            cleanupDebt?.let { unresolved ->
                enterWaitingIfActive(debtState(unresolved))
                publishDebt(unresolved)
                scheduleDebtRetry(unresolved)
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
            val outcome = cleanupApplied("daemon unavailable", daemonAvailable = false)
            mergeDebt(outcome.debt)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(FailClosedReason.RootDaemonUnavailable)
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            firewall.replace(current.firewall!!, next.firewallConfig(denyAll = false))
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(next.runningState())
            return
        }

        val old = cleanupApplied("runtime key changed", daemonAvailable = true)
        mergeDebt(old.debt)
        cleanupDebt?.let { unresolved ->
            enterWaitingIfActive(debtState(unresolved))
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }

        publishSafely(ProxyOnlyState.StartingBackend)
        val partial = AppliedProxyState(key)
        applied = partial

        partial.firewall = firewall.start(next.firewallConfig(denyAll = true))
        partial.service = service.startBackend(next.backendConfig(upstream))

        val requirements = ProbeRequirements(udpRequired = next.settings.udpEnabled)
        val report = service.runOutboundProbes(partial.service!!, requirements)
        when (val evaluation = report.evaluate(requirements)) {
            ProbeEvaluation.Success -> Unit
            ProbeEvaluation.VpnPermissionDenied -> {
                transitionAfterExpectedProbeFailure(ProxyOnlyState.VpnPermissionDenied)
                return
            }
            is ProbeEvaluation.TcpFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.TcpProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.UdpFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.UdpProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.DnsFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.DnsProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.ListenerNotReady,
            is ProbeEvaluation.Incomplete -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.ListenerNotReady(evaluation.toString())
                    )
                )
                return
            }
        }

        firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false))
        partial.complete = true
        publishSafely(next.runningState())
    }
```

Typed expected probe failures are not converted to generic exceptions.

## 11. Expected probe failure transition

```kotlin
    private suspend fun transitionAfterExpectedProbeFailure(state: ProxyOnlyState) {
        val outcome = cleanupApplied("startup probe failed", daemonAvailable = true)
        mergeDebt(outcome.debt)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }
```

`VpnPermissionDenied` is now reachable and remains diagnosable.

## 12. Cleanup creation

```kotlin
    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(
            CleanupReport.noOp("nothing applied"),
            debt = null,
        )
        applied = null
        val acc = CleanupAccumulator(reporter)

        var denyResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            denyResolved = acc.stepSucceeded("deny") {
                firewall.denyAll(current.firewall!!)
                CleanupReport.empty()
            }
        }

        var serviceResolved = current.service == null
        if (current.service != null && serviceActivated) {
            serviceResolved = acc.stepSucceeded("backend_stop") {
                service.stopBackend(current.service!!)
            }
        }

        var listenerResolved = serviceResolved
        if (!serviceResolved && serviceActivated) {
            listenerResolved = acc.stepSucceeded("emergency_close") {
                service.emergencyCloseListener(reason)
            }
        }

        var firewallResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            firewallResolved = acc.stepSucceeded("firewall_stop") {
                firewall.stop(current.firewall!!)
                CleanupReport.empty()
            }
        }

        val debt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            firewallHandlePending = current.firewall.takeUnless { firewallResolved },
            firewallDenyPending = current.firewall != null && !denyResolved,
            firewallStopPending = current.firewall != null && !firewallResolved,
            daemonCleanPending = current.firewall != null && !daemonAvailable,
            featureStopPending = false,
            failures = acc.failures,
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        return CleanupOutcome(acc.report(), debt)
    }
```

Emergency close is never called when `applied == null`; idle snapshots cannot fabricate debt.

## 13. Item-by-item debt retry

```kotlin
    private suspend fun retryCleanupDebtSafely() {
        val original = cleanupDebt ?: return
        var debt = original
        val failures = mutableListOf<CleanupFailure>()

        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val report = withTimeout(CLEANUP_STEP_TIMEOUT) { block() }
                failures += report.failures
                report.failures.isEmpty()
            } catch (failure: Throwable) {
                failures += CleanupFailure(name, failure)
                reportSafely("proxy.cleanup_debt.$name", failure)
                false
            }
        }

        var serviceHandle = debt.serviceHandlePending
        var listenerPending = debt.listenerClosePending
        if (serviceHandle != null && serviceActivated) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false
            }
        }
        if (listenerPending && serviceActivated) {
            if (attempt("emergency_close") {
                    service.emergencyCloseListener("cleanup debt retry")
                }) {
                listenerPending = false
            }
        }

        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending
        val daemonHealthy = latestSnapshot?.daemonHealthy == true

        if (firewallHandle != null && daemonHealthy && denyPending) {
            if (attempt("deny") {
                    firewall.denyAll(firewallHandle!!)
                    CleanupReport.empty()
                }) {
                denyPending = false
            }
        }

        if (firewallHandle != null && daemonHealthy && stopPending) {
            if (attempt("firewall_stop") {
                    firewall.stop(firewallHandle!!)
                    CleanupReport.empty()
                }) {
                stopPending = false
                firewallHandle = null
            }
        }

        var daemonCleanPending = debt.daemonCleanPending
        if (daemonCleanPending && daemonHealthy) {
            if (attempt("daemon_clean") {
                    firewall.cleanOrDenyBeforeRestart().asCleanupReport()
                }) {
                daemonCleanPending = false
            }
        }

        var featureStopPending = debt.featureStopPending
        if (featureStopPending && serviceActivated) {
            if (attempt("feature_stop") { service.stopFeature("cleanup debt retry") }) {
                featureStopPending = false
                serviceActivated = false
            }
        }

        val updated = debt.copy(
            listenerClosePending = listenerPending,
            serviceHandlePending = serviceHandle,
            firewallHandlePending = firewallHandle,
            firewallDenyPending = denyPending,
            firewallStopPending = stopPending,
            daemonCleanPending = daemonCleanPending,
            featureStopPending = featureStopPending,
            failures = failures,
            attempt = debt.attempt + 1,
        )
        cleanupDebt = updated.takeUnless { it.isResolved }
    }
```

A healthy-daemon `firewall_stop` failure cannot be cleared by listener closure. Its handle and stop obligation remain until retried successfully.

## 14. Self-triggered debt retries

```kotlin
    private fun scheduleDebtRetry(debt: CleanupDebt) {
        if (retryJob?.isActive == true) return
        val generation = debt.generation
        val delayMillis = cleanupBackoff(
            attempt = debt.attempt,
            baseMillis = 1_000,
            maxMillis = 60_000,
            jitterFraction = 0.20,
        )
        retryJob = scope.launch {
            delay(delayMillis)
            events.send(ControllerEvent.RetryCleanupDebt(generation))
        }
    }
```

This retry runs even when settings, VPN, tethering and client state are unchanged.

## 15. Waiting, recovery and terminal stop

```kotlin
    private suspend fun enterWaiting(state: ProxyOnlyState, reason: String) {
        val outcome = cleanupApplied(reason, daemonAvailable = true)
        mergeDebt(outcome.debt)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    private suspend fun enterWaitingIfActive(state: ProxyOnlyState) {
        if (serviceActivated) service.enterWaiting(state)
    }

    private suspend fun recoverSafely(original: Throwable) {
        try {
            val outcome = cleanupApplied("reconcile failure", daemonAvailable = true)
            mergeDebt(outcome.debt)
            reportSafely("proxy.reconcile", original, outcome.report.failures)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.InternalFailure(original::class.java.simpleName)
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
        } catch (recoveryFailure: Throwable) {
            reportSafely("proxy.recovery", recoveryFailure)
            if (serviceActivated) {
                try {
                    val emergency = service.emergencyCloseListener("recovery failure")
                    mergeDebt(emergency.debt)
                } catch (emergencyFailure: Throwable) {
                    mergeDebt(
                        CleanupDebt(
                            listenerClosePending = true,
                            serviceHandlePending = applied?.service,
                            firewallHandlePending = applied?.firewall,
                            firewallDenyPending = applied?.firewall != null,
                            firewallStopPending = applied?.firewall != null,
                            daemonCleanPending = true,
                            featureStopPending = false,
                            failures = listOf(
                                CleanupFailure("emergency_close", emergencyFailure)
                            ),
                            generation = nextDebtGeneration++,
                            attempt = 0,
                        )
                    )
                }
            }
            cleanupDebt?.let {
                publishDebt(it)
                scheduleDebtRetry(it)
            }
        }
    }

    private suspend fun terminalStopSafely(reason: String) {
        val outcome = cleanupApplied(reason, daemonAvailable = latestSnapshot?.daemonHealthy == true)
        mergeDebt(outcome.debt)

        if (serviceActivated) {
            val featureReport = try {
                service.stopFeature(reason)
            } catch (failure: Throwable) {
                CleanupReport.failure("feature_stop", failure)
            }
            if (featureReport.failures.isEmpty()) {
                serviceActivated = false
            } else {
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false,
                        serviceHandlePending = null,
                        firewallHandlePending = null,
                        firewallDenyPending = false,
                        firewallStopPending = false,
                        daemonCleanPending = false,
                        featureStopPending = true,
                        failures = featureReport.failures,
                        generation = nextDebtGeneration++,
                        attempt = 0,
                    )
                )
            }
        }
        cleanupDebt?.let(::scheduleDebtRetry)
    }
```

`stopFeature` has one owner: `terminalStopSafely`.

## 16. Debt merge and observable state

```kotlin
    private fun mergeDebt(newDebt: CleanupDebt?) {
        if (newDebt == null) return
        cleanupDebt = cleanupDebt?.merge(newDebt) ?: newDebt
    }

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved,
        failures = debt.failures,
        retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) {
        publishSafely(debtState(debt))
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
            // Last-resort platform logging must not throw into the worker.
        }
    }
}
```

## 17. Testable Hev network hook

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

Android uses `android_setsocknetwork()`. Host CI injects a fake callback and asserts ordering on every socket path.

## 18. UDP topology observations

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

## 19. Explicit firewall proto

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

message ProxyDownstream {
  string interface_name = 1;
  repeated bytes ipv4_addresses = 2;
}

message ProxyClient {
  bytes mac = 1;
  repeated bytes ipv4 = 2;
}
```

`deny_all_ipv4` is explicit; an empty `allowed_clients` list is not used as a hidden deny-state flag. `VerifiedUdpReturnPolicy` is absent until Phase 0 proves exact semantics.
