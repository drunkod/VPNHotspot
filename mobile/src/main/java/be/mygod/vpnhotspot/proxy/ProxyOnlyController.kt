package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException

// ---------------------------------------------------------------------------
// Step 6 + Step 10 — ProxyOnlyController (with all post-split corrections)
// Sketches:
//   docs/proxy-only/sketches/06-controller-worker.md
//   docs/proxy-only/sketches/10-post-split-corrections.md
//
// Post-split corrections applied (Step 10):
//   A. AtomicReference latest snapshot + daemon-generation sanitation gate
//   B. Retry-generation ownership (clears timer before stale-gen check)
//   C. Typed probe mapping (no check(); handles all ProbeEvaluation branches)
//   D. Cleanup dominance (firewall stop clears deny; backend stop clears listener)
//   E. Daemon availability threaded into waiting/recovery cleanup
// ---------------------------------------------------------------------------

private const val TRANSACTION_TIMEOUT = 30_000L
private const val CLEANUP_STEP_TIMEOUT = 10_000L

fun cleanupBackoff(
    attempt: Int,
    baseMillis: Long = 1_000L,
    maxMillis: Long = 60_000L,
    jitterFraction: Double = 0.20,
): Long {
    val base = min(baseMillis * (1L shl attempt.coerceAtMost(30)), maxMillis)
    val jitter = (base * jitterFraction * Random.nextDouble()).toLong()
    return base + jitter
}

class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val activationGrants: ActivationGrantConsumer,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    // Step 10 §A — atomic snapshot for cross-thread safety
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)
    private var sanitizedDaemonGeneration: Long? = null

    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var scheduledRetryGeneration: Long? = null
    private var nextDebtGeneration = 1L

    // -----------------------------------------------------------------------
    // 6.1  Worker entry point
    // -----------------------------------------------------------------------

    fun start(source: Flow<DesiredProxyState>): Job = scope.launch {
        val collector = launch {
            source.collect { raw ->
                val normalized = raw.normalized()
                latestSnapshot.set(normalized)
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

    // -----------------------------------------------------------------------
    // Step 10 §B — retry-generation ownership
    // -----------------------------------------------------------------------

    private suspend fun retryDebtEvent(event: ControllerEvent.RetryCleanupDebt) {
        // Clear timer ownership BEFORE the stale-generation check so a stale event
        // always reschedules for the current generation instead of leaving debt stuck.
        retryJob = null
        scheduledRetryGeneration = null

        val debt = cleanupDebt ?: return
        if (debt.generation != event.generation) {
            scheduleDebtRetry(debt)
            return
        }

        retryCleanupDebtSafely()
        val unresolved = cleanupDebt
        if (unresolved != null) {
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }
        latestSnapshot.get()?.let { reconcile(it) }
    }

    // -----------------------------------------------------------------------
    // 6.2  Reconciliation
    // -----------------------------------------------------------------------

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
            enterWaiting(next, ProxyOnlyState.WaitingForTethering, "no tethering")
            return
        }

        val upstream = when (val selection = next.vpnSelection) {
            VpnSelection.None -> {
                enterWaiting(next, ProxyOnlyState.WaitingForVpn, "no VPN")
                return
            }
            is VpnSelection.Multiple -> {
                enterWaiting(
                    next,
                    ProxyOnlyState.MultipleVpnCandidates(selection.candidates.size),
                    "multiple VPNs",
                )
                return
            }
            is VpnSelection.One -> selection.upstream
        }

        // Step 10 §A — daemon-generation sanitation gate
        if (!next.daemonHealthy || next.daemonGeneration == null) {
            sanitizedDaemonGeneration = null
            val outcome = cleanupApplied("daemon unavailable", daemonAvailable = false)
            mergeDebt(outcome.debt)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(FailClosedReason.RootDaemonUnavailable)
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }
        if (sanitizedDaemonGeneration != next.daemonGeneration) {
            if (!firewall.cleanOrDenyBeforeRestart()) {
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false,
                        serviceHandlePending = null,
                        firewallHandlePending = null,
                        firewallDenyPending = false,
                        firewallStopPending = false,
                        daemonCleanPending = true,
                        featureStopPending = false,
                        failures = listOf(
                            CleanupFailure(
                                "startup_sanitation",
                                RuntimeException("startup sanitation failed"),
                            )
                        ),
                        generation = nextDebtGeneration++,
                        attempt = 0,
                    )
                )
                val state = cleanupDebt?.let(::debtState)
                    ?: ProxyOnlyState.FailClosed(
                        FailClosedReason.StartupSanitationFailed("cleanOrDenyBeforeRestart returned false")
                    )
                publishSafely(state)
                cleanupDebt?.let(::scheduleDebtRetry)
                return
            }
            sanitizedDaemonGeneration = next.daemonGeneration
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

        // Step 10 §C — fully typed probe mapping, no check()
        when (val evaluation = report.evaluate(requirements)) {
            ProbeEvaluation.Success -> Unit
            ProbeEvaluation.VpnPermissionDenied ->
                transitionAfterExpectedProbeFailure(ProxyOnlyState.VpnPermissionDenied)
            is ProbeEvaluation.BindFailed ->
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.BindProbeFailed(evaluation.failure.toString())
                    )
                ).also { return }
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
            is ProbeEvaluation.ListenerNotReady -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.ListenerNotReady(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.Incomplete -> {
                // Incomplete is NOT a listener failure.
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.ProbeReportIncomplete(evaluation.missing)
                    )
                )
                return
            }
        }

        firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false))
        partial.complete = true
        publishSafely(next.runningState())
    }

    // -----------------------------------------------------------------------
    // 6.3  Expected probe-failure transition
    // -----------------------------------------------------------------------

    private suspend fun transitionAfterExpectedProbeFailure(state: ProxyOnlyState) {
        val outcome = cleanupApplied("startup probe failed", daemonAvailable = true)
        mergeDebt(outcome.debt)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    // -----------------------------------------------------------------------
    // 6.4  Cleanup creation
    // Step 10 §D — firewall stop clears deny; backend stop clears listener
    // Step 10 §E — daemonAvailable threaded in
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(CleanupReport.noOp("nothing applied"), debt = null)
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
            val stopped = acc.stepSucceeded("firewall_stop") {
                firewall.stop(current.firewall!!)
                CleanupReport.empty()
            }
            if (stopped) {
                firewallResolved = true
                // Step 10 §D: successful firewall stop also resolves pending deny
                denyResolved = true
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

    // -----------------------------------------------------------------------
    // 6.5  Item-by-item debt retry
    // Step 10 §D — firewall stop success clears denyPending
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
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
                listenerPending = false   // §D: backend stop clears listener too
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
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

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
                denyPending = false   // §D: firewall stop clears deny obligation
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

    // -----------------------------------------------------------------------
    // 6.6  Self-triggered debt retry scheduler (Step 10 §B)
    // -----------------------------------------------------------------------

    private fun scheduleDebtRetry(debt: CleanupDebt) {
        // Step 10 §B: cancel old timer if generation differs
        if (retryJob?.isActive == true && scheduledRetryGeneration == debt.generation) return

        retryJob?.cancel()
        scheduledRetryGeneration = debt.generation
        retryJob = scope.launch {
            delay(cleanupBackoff(debt.attempt))
            events.send(ControllerEvent.RetryCleanupDebt(debt.generation))
        }
    }

    // -----------------------------------------------------------------------
    // Step 10 §B — mergeDebt assigns fresh generation and cancels old timer
    // -----------------------------------------------------------------------

    private fun mergeDebt(incoming: CleanupDebt?) {
        if (incoming == null) return
        val merged = cleanupDebt?.mergeUnresolved(incoming) ?: incoming
        cleanupDebt = merged.copy(
            generation = nextDebtGeneration++,
            attempt = 0,
        )
        retryJob?.cancel()
        retryJob = null
        scheduledRetryGeneration = null
    }

    // -----------------------------------------------------------------------
    // 6.7  Waiting, recovery and terminal stop
    // Step 10 §E — daemonAvailable forwarded from snapshot
    // -----------------------------------------------------------------------

    private suspend fun enterWaiting(
        next: DesiredProxyState,
        state: ProxyOnlyState,
        reason: String,
    ) {
        // Step 10 §E: pass actual daemon health, not hardcoded true
        val outcome = cleanupApplied(reason, daemonAvailable = next.daemonHealthy)
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
            // Step 10 §E: read daemon health from atomic latest snapshot
            val outcome = cleanupApplied(
                "reconcile failure",
                daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
            )
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
                            failures = listOf(CleanupFailure("emergency_close", emergencyFailure)),
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
        val outcome = cleanupApplied(
            reason,
            daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
        )
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

    // -----------------------------------------------------------------------
    // 6.8  Observable state helpers
    // -----------------------------------------------------------------------

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved,
        failures = debt.failures,
        retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) = publishSafely(debtState(debt))

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try { stateSink.publish(state) } catch (failure: Throwable) {
            reportSafely("proxy.state_publish", failure)
        }
    }

    private fun reportSafely(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    ) {
        try { reporter.report(category, failure, cleanupFailures) } catch (_: Throwable) { }
    }
}

// ---------------------------------------------------------------------------
// Extension helpers on DesiredProxyState (implemented by the integration layer)
// ---------------------------------------------------------------------------

fun DesiredProxyState.runtimeKey(upstream: ProxyVpnUpstream): RuntimeKey =
    RuntimeKey(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        credentialsVersion = settings.credentialsVersion,
        vpnNetworkHandle = upstream.handle,
        downstreams = downstreams.map { it.interfaceName to (it.ipv4Address ?: "") },
        backendVersion = 1,
    )

fun DesiredProxyState.firewallConfig(denyAll: Boolean): ProxyFirewallConfig =
    ProxyFirewallConfig(
        downstreams = downstreams.map {
            ProxyDownstreamConfig(it.interfaceName, listOfNotNull(it.ipv4Address))
        },
        tcpPort = settings.tcpPort,
        udpPortRangeStart = settings.udpPortRange.first,
        udpPortRangeEnd = settings.udpPortRange.last,
        allowedClients = if (denyAll) emptyList() else allowedClients,
        generation = System.currentTimeMillis(),
        denyAllIpv4 = denyAll,
        denyAllIpv6 = true, // IPv6 always denied until Phase 0 evidence
    )

fun DesiredProxyState.backendConfig(upstream: ProxyVpnUpstream): ProxyBackendConfig =
    ProxyBackendConfig(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        maxUdpAssociations = settings.maxUdpAssociations,
        username = settings.username,
        password = settings.password,
        vpnNetworkHandle = upstream.handle,
        backendVersion = 1,
    )

fun DesiredProxyState.runningState(): ProxyOnlyState.Running =
    ProxyOnlyState.Running(
        endpoint = ProxyEndpoint(
            host = "127.0.0.1",
            tcpPort = settings.tcpPort,
            udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        )
    )
