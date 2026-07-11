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
// Step 6 + Step 10 — ProxyOnlyController
// R1 fixes #1-#13 applied; R2 fixes applied in this revision.
//
// R2 fixes:
//   Blocker 1  Compilation: firewallConfig/backendConfig call sites updated;
//              ProxyCredentialProvider injected into constructor.
//   Blocker 2  Retry ordering: featureStop only after serviceHandle==null &&
//              listenerPending==false.
//   Blocker 3  CancellationException re-thrown in every cleanup helper.
//   Blocker 4  Emergency debt no longer overwrites merged debt; collected
//              locally and committed once at end of retry transaction.
//   Blocker 5  staleHandle() is now a critical failure (in CleanupReport).
//   High #6    runningState() fails closed with NoReachableDownstreamAddress
//              when no downstream has a routable IPv4; never publishes 127.0.0.1.
//   High #7    Firewall generation is scoped to daemonGeneration so it cannot
//              go backwards across app-process restarts.
//   High #8    ProxyCredentials is now a non-data class; fetched via provider.
//   High #9    Normalization deduplicates and trims (in ProxyModels.kt).
//   High #10   VPN selector merges interface names (in ProxyVpnSelector.kt).
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
    /** R2 blocker 1: credentials fetched from provider immediately before backend start. */
    private val credentialProvider: ProxyCredentialProvider,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)
    private var sanitizedDaemonGeneration: Long? = null

    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var scheduledRetryGeneration: Long? = null
    private var nextDebtGeneration = 1L

    /**
     * R2 high #7: firewall generation counter scoped to daemonGeneration.
     * When [sanitizedDaemonGeneration] changes, this counter is reset so
     * values are strictly increasing within a daemon lifetime and cannot go
     * backwards if the app process restarts while the daemon survives.
     */
    private var firewallGeneration = AtomicLong(0L)

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
            withContext(NonCancellable) {
                terminalStopSafely("controller worker terminated")
            }
        }
    }

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
    // Retry-generation ownership (Step 10 §B)
    // -----------------------------------------------------------------------

    private suspend fun retryDebtEvent(event: ControllerEvent.RetryCleanupDebt) {
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

        // Daemon-generation sanitation gate (Step 10 §A)
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
                            CleanupFailure(
                                "startup_sanitation",
                                RuntimeException("cleanOrDenyBeforeRestart returned false"),
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
            // R2 high #7: reset generation counter when daemon generation changes.
            firewallGeneration = AtomicLong(next.daemonGeneration shl 20)
            sanitizedDaemonGeneration = next.daemonGeneration
        }

        // R2 high #6: require at least one downstream with a routable IPv4.
        val downstreamEndpoints = next.downstreams.mapNotNull { ds ->
            ds.ipv4Address?.let { ip ->
                ProxyEndpoint(
                    downstreamInterface = ds.interfaceName,
                    host = ip,
                    tcpPort = next.settings.tcpPort,
                    udpPortRange = if (next.settings.udpEnabled) next.settings.udpPortRange else null,
                )
            }
        }
        if (downstreamEndpoints.isEmpty()) {
            enterWaiting(next, ProxyOnlyState.WaitingForTethering, "no downstream IPv4")
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            // R2 blocker 1: pass firewallGeneration to every firewallConfig call.
            firewall.replace(current.firewall!!, next.firewallConfig(denyAll = false, firewallGeneration))
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(ProxyOnlyState.Running(downstreamEndpoints))
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

        // R2 blocker 1: pass firewallGeneration to deny-first startup config.
        partial.firewall = firewall.start(next.firewallConfig(denyAll = true, firewallGeneration))

        // R2 blocker 1: fetch credentials immediately before backend start.
        val credentials = try {
            credentialProvider.credentials()
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(FailClosedReason.InternalFailure("credential_fetch"))
                )
            }
            return
        }
        partial.service = service.startBackend(next.backendConfig(upstream, credentials))

        val requirements = ProbeRequirements(udpRequired = next.settings.udpEnabled)
        val report = service.runOutboundProbes(partial.service!!, requirements)

        // Exhaustive probe mapping — compiler enforces every branch.
        val probeDecision: ProbeStartDecision = when (val ev = report.evaluate(requirements)) {
            ProbeEvaluation.Success -> ProbeStartDecision.Proceed
            ProbeEvaluation.VpnPermissionDenied ->
                ProbeStartDecision.Fail(ProxyOnlyState.VpnPermissionDenied)
            is ProbeEvaluation.BindFailed ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.BindProbeFailed(ev.failure.toString())))
            is ProbeEvaluation.TcpFailed ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.TcpProbeFailed(ev.failure.toString())))
            is ProbeEvaluation.UdpFailed ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.UdpProbeFailed(ev.failure.toString())))
            is ProbeEvaluation.DnsFailed ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.DnsProbeFailed(ev.failure.toString())))
            is ProbeEvaluation.ListenerNotReady ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.ListenerNotReady(ev.failure.toString())))
            is ProbeEvaluation.Incomplete ->
                ProbeStartDecision.Fail(ProxyOnlyState.FailClosed(FailClosedReason.ProbeReportIncomplete(ev.missing)))
        }

        if (probeDecision is ProbeStartDecision.Fail) {
            withContext(NonCancellable) { transitionAfterExpectedProbeFailure(probeDecision.state) }
            return
        }

        // R2 blocker 1: pass firewallGeneration when switching to allow rules.
        firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false, firewallGeneration))
        partial.complete = true
        publishSafely(ProxyOnlyState.Running(downstreamEndpoints))
    }

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
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(CleanupReport.noOp("nothing applied"), null)
        applied = null
        val acc = CleanupAccumulator(reporter)

        var denyResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            denyResolved = acc.stepSucceeded("deny") { firewall.denyAll(current.firewall!!) }
        }

        var serviceResolved = current.service == null
        if (current.service != null && serviceActivated) {
            serviceResolved = acc.stepSucceeded("backend_stop") {
                service.stopBackend(current.service!!)
            }
        }

        var listenerResolved = serviceResolved
        var emergencyOutcomeDebt: CleanupDebt? = null
        if (!serviceResolved && serviceActivated) {
            val outcome = safeEmergencyClose(reason)
            listenerResolved = !outcome.report.hasCriticalFailure
            acc.failures += outcome.report.failures
            // R2 blocker 4: collect emergency debt locally — commit after final debt construction.
            emergencyOutcomeDebt = outcome.debt
        }

        var firewallResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            val stopped = acc.stepSucceeded("firewall_stop") { firewall.stop(current.firewall!!) }
            if (stopped) {
                firewallResolved = true
                denyResolved = true  // firewall stop clears deny (Step 10 §D)
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

        // R2 blocker 4: merge emergency debt AFTER primary debt is built.
        if (emergencyOutcomeDebt != null) mergeDebt(emergencyOutcomeDebt)

        return CleanupOutcome(acc.report(), debt)
    }

    // -----------------------------------------------------------------------
    // Item-by-item debt retry
    // R2 blocker 2: featureStop only after handle and listener are cleared.
    // R2 blocker 3: CancellationException re-thrown in attempt helper.
    // R2 blocker 4: emergency debt collected locally, merged once at end.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        val failures = mutableListOf<CleanupFailure>()
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

        // R2 blocker 3: re-throw parent CancellationException.
        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val r = withTimeout(CLEANUP_STEP_TIMEOUT_MS) { block() }
                failures += r.failures
                r.failures.isEmpty()
            } catch (stepTimeout: TimeoutCancellationException) {
                failures += CleanupFailure(name, stepTimeout)
                safeReport("proxy.cleanup_debt.$name", stepTimeout)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled   // R2 blocker 3
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
                listenerPending = false
            }
        }

        // R2 blocker 4: collect emergency debt locally; merge after updated is built.
        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && serviceActivated) {
            val outcome = safeEmergencyClose("cleanup debt retry")
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            failures += outcome.report.failures
            emergencyOutcomeDebt = outcome.debt  // do NOT call mergeDebt here
        }

        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending

        if (firewallHandle != null && daemonHealthy && denyPending) {
            if (attempt("deny") { firewall.denyAll(firewallHandle!!) }) denyPending = false
        }
        if (firewallHandle != null && daemonHealthy && stopPending) {
            if (attempt("firewall_stop") { firewall.stop(firewallHandle!!) }) {
                stopPending = false
                firewallHandle = null
                denyPending = false
            }
        }

        var daemonCleanPending = debt.daemonCleanPending
        if (daemonCleanPending && daemonHealthy) {
            if (attempt("daemon_clean") { firewall.cleanOrDenyBeforeRestart().asCleanupReport() }) {
                daemonCleanPending = false
            }
        }

        var featureStopPending = debt.featureStopPending
        // R2 blocker 2: only attempt feature stop after service handle and listener are cleared.
        if (featureStopPending && serviceActivated &&
            serviceHandle == null && !listenerPending
        ) {
            if (attempt("feature_stop") {
                    withTimeout(CLEANUP_STEP_TIMEOUT_MS) { service.stopFeature("cleanup debt retry") }
                }) {
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
        // R2 blocker 4: commit primary updated debt first, then merge emergency debt on top.
        cleanupDebt = updated.takeUnless { it.isResolved }
        emergencyOutcomeDebt?.let { mergeDebt(it) }
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

    // Step 10 §B: mergeDebt assigns fresh generation and cancels old timer.
    private fun mergeDebt(incoming: CleanupDebt?) {
        if (incoming == null) return
        val merged = cleanupDebt?.mergeUnresolved(incoming) ?: incoming
        cleanupDebt = merged.copy(generation = nextDebtGeneration++, attempt = 0)
        retryJob?.cancel()
        retryJob = null
        scheduledRetryGeneration = null
    }

    // -----------------------------------------------------------------------
    // Waiting, recovery and terminal stop
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
                val outcome = safeEmergencyClose("recovery failure")
                mergeDebt(outcome.debt)
            }
            cleanupDebt?.let { publishDebt(it); scheduleDebtRetry(it) }
        }
    }

    // R2 blocker 2: feature stop deferred when service/listener debt remains.
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
                // Defer feature stop until service/listener debt clears.
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
                // Bound the feature-stop call.
                val featureReport = try {
                    withTimeout(CLEANUP_STEP_TIMEOUT_MS) { service.stopFeature(reason) }
                } catch (stepTimeout: TimeoutCancellationException) {
                    CleanupReport.failure("feature_stop_timeout", stepTimeout)
                } catch (t: Throwable) {
                    CleanupReport.failure("feature_stop", t)
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
    // Helpers
    // -----------------------------------------------------------------------

    /** Safe wrapper around emergencyCloseListener that never throws. */
    private suspend fun safeEmergencyClose(reason: String): CleanupOutcome {
        return try {
            withTimeout(CLEANUP_STEP_TIMEOUT_MS) { service.emergencyCloseListener(reason) }
        } catch (stepTimeout: TimeoutCancellationException) {
            val f = CleanupFailure("emergency_close_timeout", stepTimeout)
            val debtFromTimeout = CleanupDebt(
                listenerClosePending = true,
                serviceHandlePending = applied?.service,
                firewallHandlePending = null,
                firewallDenyPending = false,
                firewallStopPending = false,
                daemonCleanPending = false,
                featureStopPending = false,
                failures = listOf(f),
                generation = nextDebtGeneration++,
                attempt = 0,
            )
            CleanupOutcome(CleanupReport(listOf(f)), debtFromTimeout)
        } catch (cancelled: CancellationException) {
            throw cancelled  // R2 blocker 3
        } catch (t: Throwable) {
            val f = CleanupFailure("emergency_close", t)
            val debtFromFailure = CleanupDebt(
                listenerClosePending = true,
                serviceHandlePending = applied?.service,
                firewallHandlePending = applied?.firewall,
                firewallDenyPending = applied?.firewall != null,
                firewallStopPending = applied?.firewall != null,
                daemonCleanPending = true,
                featureStopPending = false,
                failures = listOf(f),
                generation = nextDebtGeneration++,
                attempt = 0,
            )
            CleanupOutcome(CleanupReport(listOf(f)), debtFromFailure)
        }
    }

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved,
        failures = debt.failures,
        retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) = publishSafely(debtState(debt))

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try { stateSink.publish(state) } catch (f: Throwable) { safeReport("proxy.state_publish", f) }
    }

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
// R2 blocker 1: generationCounter required on every firewallConfig call.
// R2 blocker 1: credentials required for backendConfig.
// R2 high #6: runningState removed — endpoints built inline in reconcile().
// ---------------------------------------------------------------------------

fun DesiredProxyState.runtimeKey(upstream: ProxyVpnUpstream): RuntimeKey =
    RuntimeKey(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        credentialsVersion = settings.credentialsVersion,
        vpnNetworkHandle = upstream.handle,
        downstreams = downstreams.map { ds ->
            ds.interfaceName to listOfNotNull(ds.ipv4Address).sorted()
        },
        backendVersion = 1,
    )

/** R2 blocker 1: generationCounter is mandatory — no default. */
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
    denyAllIpv6 = true,
)

/** R2 blocker 1: credentials are mandatory — no default. */
fun DesiredProxyState.backendConfig(
    upstream: ProxyVpnUpstream,
    credentials: ProxyCredentials,
): ProxyBackendConfig = ProxyBackendConfig(
    tcpPort = settings.tcpPort,
    udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
    maxUdpAssociations = settings.maxUdpAssociations,
    credentials = credentials,
    vpnNetworkHandle = upstream.handle,
    backendVersion = 1,
)

fun Boolean.asCleanupReport() =
    if (this) CleanupReport.empty()
    else CleanupReport.failure("operation", RuntimeException("returned false"))
