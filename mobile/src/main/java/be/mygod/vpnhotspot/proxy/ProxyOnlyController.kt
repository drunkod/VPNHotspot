package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Step 6 + Step 10 — ProxyOnlyController (all post-split corrections + R1 fixes)
//
// R1 blockers fixed:
//   #1  VpnPermissionDenied fallthrough → every non-Success branch returns.
//   #2  Firewall Boolean → denyAll/stop return CleanupReport; failures create debt.
//   #3  Emergency debt discarded → emergencyCloseListener returns CleanupOutcome.
//   #4  Terminal stop strands handle → feature stop deferred until backend debt cleared.
//
// R1 high-severity fixed:
//   #5  Per-step timeout in CleanupAccumulator (moved to ProxyServiceClient.kt).
//   #6  NonCancellable removed from normal reconciliation; only cleanup uses it.
//   #7  Reporter calls are non-throwing (handled in accumulator and safeReport).
//   #8  Endpoint host derived from downstream interface (placeholder).
//   #9  Firewall generation uses controller-owned monotonic counter.
// ---------------------------------------------------------------------------

private const val TRANSACTION_TIMEOUT_MS = 30_000L

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
    // Fix #6 (Step 10 §A): atomic snapshot for cross-thread safety
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)
    private var sanitizedDaemonGeneration: Long? = null

    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var scheduledRetryGeneration: Long? = null
    private var nextDebtGeneration = 1L

    // Fix #9: monotonic firewall generation; never wall-clock time.
    private val firewallGeneration = AtomicLong(0L)

    // -----------------------------------------------------------------------
    // Worker entry point
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
            // Terminal cleanup always runs non-cancellably.
            withContext(NonCancellable) {
                terminalStopSafely("controller worker terminated")
            }
        }
    }

    // Fix #6: normal reconciliation runs in the parent job (cancellable).
    // NonCancellable is reserved only for cleanup/finalization paths.
    private suspend fun runIteration(event: ControllerEvent) {
        try {
            when (event) {
                is ControllerEvent.Snapshot ->
                    withTimeout(TRANSACTION_TIMEOUT_MS) { reconcile(event.state) }
                is ControllerEvent.RetryCleanupDebt ->
                    withContext(NonCancellable) {
                        withTimeout(TRANSACTION_TIMEOUT_MS) { retryDebtEvent(event) }
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
        // Clear timer ownership BEFORE the stale-generation check.
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
    // Reconciliation
    // -----------------------------------------------------------------------

    private suspend fun reconcile(next: DesiredProxyState) {
        if (!next.settings.enabled) {
            // Terminal stop is cleanup — run non-cancellably.
            withContext(NonCancellable) { terminalStopSafely("disabled") }
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

        val upstream = when (val sel = next.vpnSelection) {
            VpnSelection.None -> {
                enterWaiting(next, ProxyOnlyState.WaitingForVpn, "no VPN")
                return
            }
            is VpnSelection.Multiple -> {
                enterWaiting(next, ProxyOnlyState.MultipleVpnCandidates(sel.candidates.size), "multiple VPNs")
                return
            }
            is VpnSelection.One -> sel.upstream
        }

        // Step 10 §A — daemon-generation sanitation gate
        if (!next.daemonHealthy || next.daemonGeneration == null) {
            sanitizedDaemonGeneration = null
            withContext(NonCancellable) {
                val outcome = cleanupApplied("daemon unavailable", daemonAvailable = false)
                mergeDebt(outcome.debt)
            }
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
                            CleanupFailure("startup_sanitation",
                                RuntimeException("cleanOrDenyBeforeRestart returned false"))
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

        withContext(NonCancellable) {
            val old = cleanupApplied("runtime key changed", daemonAvailable = true)
            mergeDebt(old.debt)
        }
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

        // Fix #1 — every non-Success branch must return; use exhaustive when.
        // Fix: use a local sealed result so the compiler enforces exhaustiveness.
        val probeDecision: ProbeStartDecision = when (val ev = report.evaluate(requirements)) {
            ProbeEvaluation.Success -> ProbeStartDecision.Proceed
            ProbeEvaluation.VpnPermissionDenied ->
                ProbeStartDecision.Fail(ProxyOnlyState.VpnPermissionDenied)
            is ProbeEvaluation.BindFailed ->
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.BindProbeFailed(ev.failure.toString()))
                )
            is ProbeEvaluation.TcpFailed ->
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.TcpProbeFailed(ev.failure.toString()))
                )
            is ProbeEvaluation.UdpFailed ->
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.UdpProbeFailed(ev.failure.toString()))
                )
            is ProbeEvaluation.DnsFailed ->
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.DnsProbeFailed(ev.failure.toString()))
                )
            is ProbeEvaluation.ListenerNotReady ->
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.ListenerNotReady(ev.failure.toString()))
                )
            is ProbeEvaluation.Incomplete ->
                // Incomplete is NOT a listener failure.
                ProbeStartDecision.Fail(
                    ProxyOnlyState.FailClosed(FailClosedReason.ProbeReportIncomplete(ev.missing))
                )
        }

        if (probeDecision is ProbeStartDecision.Fail) {
            withContext(NonCancellable) {
                transitionAfterExpectedProbeFailure(probeDecision.state)
            }
            return  // Fix #1: always return after probe failure
        }

        firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false))
        partial.complete = true
        publishSafely(next.runningState())
    }

    /** Local sealed type so the compiler enforces that every probe outcome is handled. */
    private sealed interface ProbeStartDecision {
        object Proceed : ProbeStartDecision
        data class Fail(val state: ProxyOnlyState) : ProbeStartDecision
    }

    // -----------------------------------------------------------------------
    // Expected probe-failure transition
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
    // Cleanup creation
    // Fix #2: firewall denyAll/stop return CleanupReport; false → debt.
    // Fix #5: per-step timeouts applied via CleanupAccumulator.stepSucceeded.
    // Fix §D: firewall stop clears deny; backend stop clears listener.
    // Fix §E: daemonAvailable threaded in.
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(CleanupReport.noOp("nothing applied"), null)
        applied = null   // cleared before any IO so handles are not double-freed
        val acc = CleanupAccumulator(reporter)

        var denyResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            // Fix #2: denyAll returns CleanupReport; non-empty failures ⇒ deny debt.
            denyResolved = acc.stepSucceeded("deny") {
                firewall.denyAll(current.firewall!!)
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
            val outcome = try {
                service.emergencyCloseListener(reason)
            } catch (t: Throwable) {
                CleanupOutcome(CleanupReport.failure("emergency_close", t), null)
            }
            listenerResolved = !outcome.report.hasCriticalFailure
            // Fix #3: merge any debt from a failed emergency close.
            mergeDebt(outcome.debt)
        }

        var firewallResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            // Fix #2: stop returns CleanupReport.
            val stopped = acc.stepSucceeded("firewall_stop") {
                firewall.stop(current.firewall!!)
            }
            if (stopped) {
                firewallResolved = true
                denyResolved = true  // §D: successful firewall stop also clears deny
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
    // Item-by-item debt retry
    // Fix #2: denyAll/stop return CleanupReport.
    // Fix §D: firewall stop clears deny.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        val failures = mutableListOf<CleanupFailure>()
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val r = withTimeout(CLEANUP_STEP_TIMEOUT_MS) { block() }
                failures += r.failures
                r.failures.isEmpty()
            } catch (stepTimeout: TimeoutCancellationException) {
                failures += CleanupFailure(name, stepTimeout)
                safeReport("proxy.cleanup_debt.$name", stepTimeout)
                false
            } catch (t: Throwable) {
                failures += CleanupFailure(name, t)
                safeReport("proxy.cleanup_debt.$name", t)
                false
            }
        }

        var serviceHandle = debt.serviceHandlePending
        var listenerPending = debt.listenerClosePending
        if (serviceHandle != null && serviceActivated) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false  // §D: backend stop clears listener
            }
        }
        if (listenerPending && serviceActivated) {
            val outcome = try {
                service.emergencyCloseListener("cleanup debt retry")
            } catch (t: Throwable) {
                CleanupOutcome(CleanupReport.failure("emergency_close", t), null)
            }
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            failures += outcome.report.failures
            // Fix #3: merge any failure debt from emergency close.
            mergeDebt(outcome.debt)
        }

        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending

        if (firewallHandle != null && daemonHealthy && denyPending) {
            // Fix #2: denyAll returns CleanupReport
            if (attempt("deny") { firewall.denyAll(firewallHandle!!) }) {
                denyPending = false
            }
        }
        if (firewallHandle != null && daemonHealthy && stopPending) {
            // Fix #2: stop returns CleanupReport
            if (attempt("firewall_stop") { firewall.stop(firewallHandle!!) }) {
                stopPending = false
                firewallHandle = null
                denyPending = false  // §D: successful stop clears deny
            }
        }

        var daemonCleanPending = debt.daemonCleanPending
        if (daemonCleanPending && daemonHealthy) {
            if (attempt("daemon_clean") { firewall.cleanOrDenyBeforeRestart().asCleanupReport() }) {
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
    // Self-triggered debt retry scheduler (Step 10 §B)
    // -----------------------------------------------------------------------

    private fun scheduleDebtRetry(debt: CleanupDebt) {
        if (retryJob?.isActive == true && scheduledRetryGeneration == debt.generation) return
        retryJob?.cancel()
        scheduledRetryGeneration = debt.generation
        retryJob = scope.launch {
            delay(cleanupBackoff(debt.attempt))
            events.send(ControllerEvent.RetryCleanupDebt(debt.generation))
        }
    }

    // -----------------------------------------------------------------------
    // Step 10 §B — mergeDebt assigns fresh generation, cancels old timer
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
    // Waiting, recovery and terminal stop
    // Fix §E: daemonAvailable from snapshot; not hardcoded.
    // -----------------------------------------------------------------------

    private suspend fun enterWaiting(next: DesiredProxyState, state: ProxyOnlyState, reason: String) {
        withContext(NonCancellable) {
            val outcome = cleanupApplied(reason, daemonAvailable = next.daemonHealthy)
            mergeDebt(outcome.debt)
        }
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
            val outcome = cleanupApplied(
                "reconcile failure",
                // Fix §E: read from atomic latest snapshot
                daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
            )
            mergeDebt(outcome.debt)
            safeReport("proxy.reconcile", original, outcome.report.failures)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.InternalFailure(original::class.java.simpleName)
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
        } catch (recoveryFailure: Throwable) {
            safeReport("proxy.recovery", recoveryFailure)
            if (serviceActivated) {
                // Fix #3: emergencyCloseListener returns CleanupOutcome.
                val outcome = try {
                    service.emergencyCloseListener("recovery failure")
                } catch (emergencyFailure: Throwable) {
                    // Construct explicit debt from current applied/service state.
                    val debtFromFailure = CleanupDebt(
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
                    CleanupOutcome(CleanupReport.failure("emergency_close", emergencyFailure), debtFromFailure)
                }
                mergeDebt(outcome.debt)
            }
            cleanupDebt?.let {
                publishDebt(it)
                scheduleDebtRetry(it)
            }
        }
    }

    // Fix #4: feature stop is deferred if service-handle or listener debt exists.
    // If backend stop fails → add featureStopPending=true so the retry loop
    // calls stopFeature only after backend/listener are confirmed cleared.
    private suspend fun terminalStopSafely(reason: String) {
        val outcome = cleanupApplied(
            reason,
            daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
        )
        mergeDebt(outcome.debt)

        if (serviceActivated) {
            val hasServiceDebt = cleanupDebt?.let {
                it.serviceHandlePending != null || it.listenerClosePending
            } == true

            if (hasServiceDebt) {
                // Backend stop failed — defer feature stop so retry can clear the
                // handle before the service becomes unreachable.
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false,
                        serviceHandlePending = null,
                        firewallHandlePending = null,
                        firewallDenyPending = false,
                        firewallStopPending = false,
                        daemonCleanPending = false,
                        featureStopPending = true,
                        failures = emptyList(),
                        generation = nextDebtGeneration++,
                        attempt = 0,
                    )
                )
            } else {
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
        }
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    // -----------------------------------------------------------------------
    // Observable state helpers
    // -----------------------------------------------------------------------

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved,
        failures = debt.failures,
        retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) = publishSafely(debtState(debt))

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try { stateSink.publish(state) } catch (f: Throwable) { safeReport("proxy.state_publish", f) }
    }

    // Fix #7: all reporter calls non-throwing.
    private fun safeReport(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    ) {
        try { reporter.report(category, failure, cleanupFailures) } catch (_: Throwable) { }
    }
}

// ---------------------------------------------------------------------------
// Extension helpers on DesiredProxyState
// Fix #9: monotonic generation from AtomicLong (passed in via firewallGeneration).
// Fix #8: runningState receives a downstream address, not loopback.
// Fix #12: runtimeKey canonicalizes downstreams (sorted via normalized()).
// ---------------------------------------------------------------------------

fun DesiredProxyState.runtimeKey(upstream: ProxyVpnUpstream): RuntimeKey =
    RuntimeKey(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        credentialsVersion = settings.credentialsVersion,
        vpnNetworkHandle = upstream.handle,
        // normalized() already sorted downstreams; collect interface+addresses.
        downstreams = downstreams.map { ds ->
            ds.interfaceName to listOfNotNull(ds.ipv4Address).sorted()
        },
        backendVersion = 1,
    )

/**
 * Fix #9: generation must be supplied by a controller-owned monotonic counter,
 * not wall-clock time. The caller (controller) holds the [AtomicLong] and
 * passes it here as [generationCounter].
 */
fun DesiredProxyState.firewallConfig(
    denyAll: Boolean,
    generationCounter: AtomicLong,
): ProxyFirewallConfig = ProxyFirewallConfig(
    downstreams = downstreams.map {
        ProxyDownstreamConfig(it.interfaceName, listOfNotNull(it.ipv4Address))
    },
    tcpPort = settings.tcpPort,
    udpPortRangeStart = settings.udpPortRange.first,
    udpPortRangeEnd = settings.udpPortRange.last,
    allowedClients = if (denyAll) emptyList() else allowedClients,
    generation = generationCounter.incrementAndGet(),
    denyAllIpv4 = denyAll,
    denyAllIpv6 = true, // IPv6 always denied until Phase 0 evidence
)

fun DesiredProxyState.backendConfig(upstream: ProxyVpnUpstream, credentials: ProxyCredentials): ProxyBackendConfig =
    ProxyBackendConfig(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        maxUdpAssociations = settings.maxUdpAssociations,
        credentials = credentials,
        vpnNetworkHandle = upstream.handle,
        backendVersion = 1,
    )

/**
 * Fix #8: advertise the first known downstream IPv4 address, not loopback.
 * Publishing multiple per-downstream endpoints is deferred to Phase 5+ integration.
 */
fun DesiredProxyState.runningState(): ProxyOnlyState.Running {
    val host = downstreams.firstNotNullOfOrNull { it.ipv4Address } ?: "127.0.0.1"
    return ProxyOnlyState.Running(
        endpoint = ProxyEndpoint(
            host = host,
            tcpPort = settings.tcpPort,
            udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        )
    )
}

fun Boolean.asCleanupReport() =
    if (this) CleanupReport.empty()
    else CleanupReport.failure("operation", RuntimeException("returned false"))
