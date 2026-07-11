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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Step 6 + Step 10 — ProxyOnlyController (R5-corrected)
//
// R5 fixes applied:
//   #1  Daemon-generation provenance on AppliedProxyState and CleanupDebt.
//       Daemon sanitation gate moved BEFORE debt retry in reconcile() so stale
//       handles are dominated before any retry attempts.
//       Old-generation firewall handles are resolved by sanitation dominance in
//       cleanupApplied() and retryCleanupDebtSafely() without IPC calls.
//   #3  Top-level failure boundary in scheduleDebtRetry(): ordinary exceptions
//       (e.g. mergeUnresolved() require() violations) preserve pre-transaction
//       debt, append a typed failure, and reschedule with backoff rather than
//       killing the retry job permanently.
//   #6  Zero MAC (00:00:00:00:00:00) rejected in ProxyModels.
//       Invalid interface names filtered before reaching firewall config.
//   #7  Retry self-cancellation eliminated: retryJob is cleared to null inside
//       stateMutex before the retry transaction begins, so scheduleDebtRetry()
//       called from within the transaction does not cancel the executing job.
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
    private val credentialProvider: ProxyCredentialProvider,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
    /**
     * Cleanup retry jobs execute directly in this scope under [stateMutex].
     *
     * Contract: this scope MUST outlive [scope] (the worker coroutine scope).
     * Typically provided by the foreground service or application lifecycle so
     * that terminal cleanup debt can be retried after the controller worker exits.
     * The owner is responsible for cancelling this scope during final teardown.
     */
    private val cleanupScope: CoroutineScope,
) {
    // -----------------------------------------------------------------------
    // All mutable state accessed only under stateMutex.
    // Exception: latestSnapshot is AtomicReference for lock-free reads by
    // the collector coroutine.
    // -----------------------------------------------------------------------

    private val stateMutex = Mutex()
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)

    /**
     * The daemon generation for which [ProxyFirewallClient.cleanOrDenyBeforeRestart]
     * was last successfully called. Non-null only after a successful sanitation.
     *
     * Firewall handles/debt from any other generation are dominated by the current
     * sanitation and must never be submitted to the current daemon.
     */
    private var sanitizedDaemonGeneration: Long? = null
    private var firewallGeneration = AtomicLong(0L)

    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var nextDebtGeneration = 1L

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
            for (event in events) {
                stateMutex.withLock { runIteration(event) }
            }
        } finally {
            collector.cancel()
            retryJob?.cancel()
            withContext(NonCancellable) {
                stateMutex.withLock {
                    terminalStopSafely("controller worker terminated")
                }
            }
        }
    }

    private suspend fun runIteration(event: ControllerEvent) {
        try {
            when (event) {
                is ControllerEvent.Snapshot ->
                    withTimeout(TRANSACTION_TIMEOUT_MS) { reconcile(event.state) }
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
    // Reconciliation
    //
    // R5 fix #1: daemon sanitation gate is now FIRST (before debt retry).
    // This ensures that by the time we retry cleanup debt, sanitizedDaemonGeneration
    // reflects the current daemon and stale handles are correctly dominated.
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

        // ── R5 fix #1: Daemon sanitation gate — BEFORE debt retry ──────────
        // Sanitizing first ensures any cleanup debt from an old daemon generation
        // can be resolved by dominance rather than submitted to the new daemon.
        if (!next.daemonHealthy || next.daemonGeneration == null) {
            // Daemon unavailable: invalidate sanitation so the next healthy snapshot
            // triggers a fresh cleanOrDenyBeforeRestart.
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
            val epoch = firewall.cleanOrDenyBeforeRestart()
            if (epoch == null) {
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false, serviceHandlePending = null,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = true,
                        featureStopPending = false, serviceWasActivated = serviceActivated,
                        firewallHandleGeneration = null,
                        failures = listOf(
                            CleanupFailure(
                                "startup_sanitation",
                                RuntimeException("cleanOrDenyBeforeRestart returned null"),
                            )
                        ),
                        generation = nextDebtGeneration++, attempt = 0,
                    )
                )
                val state = cleanupDebt?.let(::debtState)
                    ?: ProxyOnlyState.FailClosed(
                        FailClosedReason.StartupSanitationFailed("cleanOrDenyBeforeRestart returned null")
                    )
                publishSafely(state)
                cleanupDebt?.let(::scheduleDebtRetry)
                return
            }
            firewallGeneration = AtomicLong(epoch)
            // R5 fix #1: record sanitized generation BEFORE retrying debt below.
            // Stale firewall handles are now dominated.
            sanitizedDaemonGeneration = next.daemonGeneration
        }
        // ── End sanitation gate ──────────────────────────────────────────────

        // Now retry existing cleanup debt — stale handles are dominated by
        // the completed sanitation above.
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

        val downstreamEndpoints = next.downstreams.mapNotNull { ds ->
            ds.ipv4Address?.takeIf { isRoutableIpv4(it) }?.let { ip ->
                ProxyEndpoint(
                    downstreamInterface = ds.interfaceName,
                    host = normalizeIpv4(ip),
                    tcpPort = next.settings.tcpPort,
                    udpPortRange = if (next.settings.udpEnabled) next.settings.udpPortRange else null,
                )
            }
        }
        if (downstreamEndpoints.isEmpty()) {
            enterWaiting(
                next,
                ProxyOnlyState.FailClosed(FailClosedReason.NoReachableDownstreamAddress),
                "no routable downstream IPv4",
            )
            return
        }

        // R4/R5 fix: daemonGeneration in key forces rebuild on daemon restart.
        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            firewall.replace(
                current.firewall!!,
                next.firewallConfig(denyAll = false, generationCounter = firewallGeneration),
            )
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
        // R5 fix #1: record sanitizedDaemonGeneration in the applied state so
        // cleanupApplied() can detect generation changes later.
        val partial = AppliedProxyState(
            key = key,
            firewallDaemonGeneration = sanitizedDaemonGeneration ?: 0L,
        )
        applied = partial

        partial.firewall = firewall.start(
            next.firewallConfig(denyAll = true, generationCounter = firewallGeneration)
        )

        val credentials = try {
            credentialProvider.credentials()
        } catch (cancelled: CancellationException) {
            throw cancelled
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

        firewall.replace(
            partial.firewall!!,
            next.firewallConfig(denyAll = false, generationCounter = firewallGeneration),
        )
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
    // Cleanup creation — fully local transaction.
    //
    // R5 fix #1: firewall handle dominance.
    //   If current.firewallDaemonGeneration differs from sanitizedDaemonGeneration,
    //   the new daemon's sanitation has already covered the old handle. Mark deny
    //   and stop as resolved without any IPC call; carry only service/listener debt.
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(CleanupReport.noOp("nothing applied"), null)
        applied = null
        val acc = CleanupAccumulator(reporter)

        // R5 fix #1: determine whether the applied firewall handle belongs to the
        // current sanitized daemon or to a stale prior daemon generation.
        val firewallFromCurrentDaemon = sanitizedDaemonGeneration != null &&
            current.firewallDaemonGeneration == sanitizedDaemonGeneration

        var denyResolved: Boolean
        var firewallResolved: Boolean

        if (!firewallFromCurrentDaemon && current.firewall != null) {
            // Stale handle: new-daemon sanitation dominates. Do not call IPC.
            // The new daemon already installed deny-all atomically; the old handle
            // is invalid and submitting it would fail or target the wrong runtime.
            denyResolved = true
            firewallResolved = true
        } else {
            denyResolved = current.firewall == null
            firewallResolved = current.firewall == null
            if (daemonAvailable && current.firewall != null) {
                denyResolved = acc.stepSucceeded("deny") { firewall.denyAll(current.firewall!!) }
            }
        }

        var serviceResolved = current.service == null
        if (current.service != null && serviceActivated) {
            serviceResolved = acc.stepSucceeded("backend_stop") {
                service.stopBackend(current.service!!)
            }
        }

        var listenerResolved = serviceResolved
        var emergencyDebt: CleanupDebt? = null
        if (!serviceResolved && serviceActivated) {
            val outcome = safeEmergencyClose(reason)
            listenerResolved = !outcome.report.hasCriticalFailure
            emergencyDebt = outcome.debt
        }

        if (firewallFromCurrentDaemon && daemonAvailable && current.firewall != null) {
            val stopped = acc.stepSucceeded("firewall_stop") { firewall.stop(current.firewall!!) }
            if (stopped) {
                firewallResolved = true
                denyResolved = true // §D: stop subsumes deny
            }
        }

        val primaryDebt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            firewallHandlePending = if (firewallFromCurrentDaemon) {
                current.firewall.takeUnless { firewallResolved }
            } else {
                null // stale handle never carried in debt
            },
            firewallDenyPending = firewallFromCurrentDaemon && current.firewall != null && !denyResolved,
            firewallStopPending = firewallFromCurrentDaemon && current.firewall != null && !firewallResolved,
            daemonCleanPending = !firewallFromCurrentDaemon && current.firewall != null && !daemonAvailable,
            featureStopPending = false,
            serviceWasActivated = serviceActivated,
            firewallHandleGeneration = if (firewallFromCurrentDaemon) {
                current.firewallDaemonGeneration
            } else {
                null
            },
            failures = acc.failures.toList(),
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        val combinedDebt = when {
            primaryDebt != null && emergencyDebt != null ->
                primaryDebt.mergeUnresolved(emergencyDebt)
                    .copy(generation = nextDebtGeneration++, attempt = 0)
            primaryDebt != null -> primaryDebt
            emergencyDebt != null -> emergencyDebt.copy(
                generation = nextDebtGeneration++, attempt = 0,
            )
            else -> null
        }

        return CleanupOutcome(acc.report(), combinedDebt)
    }

    // -----------------------------------------------------------------------
    // Cleanup debt retry — single local transaction.
    //
    // R5 fix #1: stale firewall handles dominated by generation check.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        val failures = mutableListOf<CleanupFailure>()
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val r = withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { block() }
                if (r == null) {
                    failures += CleanupFailure(name, RuntimeException("step '$name' timed out"))
                    false
                } else {
                    failures += r.failures
                    r.failures.isEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                failures += CleanupFailure(name, t)
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

        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && serviceActivated) {
            val outcome = safeEmergencyClose("cleanup debt retry")
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            emergencyOutcomeDebt = outcome.debt
        }

        // R5 fix #1: resolve stale handles by sanitation dominance.
        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending
        val currentSanitizedGen = sanitizedDaemonGeneration
        val handleStale = debt.firewallHandleGeneration != null &&
            currentSanitizedGen != null &&
            debt.firewallHandleGeneration != currentSanitizedGen

        if (handleStale) {
            // New-daemon sanitation dominated this handle; resolve without IPC.
            firewallHandle = null
            denyPending = false
            stopPending = false
        } else {
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
        }

        var daemonCleanPending = debt.daemonCleanPending
        if (daemonCleanPending && daemonHealthy) {
            val epoch = try {
                withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { firewall.cleanOrDenyBeforeRestart() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                failures += CleanupFailure("daemon_clean", t)
                null
            }
            if (epoch != null) {
                daemonCleanPending = false
                firewallGeneration = AtomicLong(epoch)
                sanitizedDaemonGeneration = latestSnapshot.get()?.daemonGeneration
            } else if (failures.none { it.step == "daemon_clean" }) {
                failures += CleanupFailure("daemon_clean",
                    RuntimeException("cleanOrDenyBeforeRestart returned null or timed out"))
            }
        }

        var featureStopPending = debt.featureStopPending
        if (featureStopPending && serviceActivated && serviceHandle == null && !listenerPending) {
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

        // Single commit.
        val combined = if (emergencyOutcomeDebt != null && !updated.isResolved) {
            updated.mergeUnresolved(emergencyOutcomeDebt)
                .copy(generation = nextDebtGeneration++, attempt = 0)
        } else if (emergencyOutcomeDebt != null) {
            emergencyOutcomeDebt.copy(generation = nextDebtGeneration++, attempt = 0)
        } else {
            updated
        }
        cleanupDebt = combined.takeUnless { it.isResolved }
    }

    // -----------------------------------------------------------------------
    // Debt retry scheduler
    //
    // R5 fix #3: top-level failure boundary preserves debt on invariant exceptions.
    // R5 fix #7: retryJob cleared to null inside stateMutex before the transaction
    //            begins, so scheduleDebtRetry() called from within does not
    //            self-cancel the executing job.
    // -----------------------------------------------------------------------

    private fun scheduleDebtRetry(debt: CleanupDebt) {
        retryJob?.cancel()
        retryJob = cleanupScope.launch {
            delay(cleanupBackoff(debt.attempt))
            try {
                stateMutex.withLock {
                    // R5 fix #7: clear self-reference before work so that
                    // scheduleDebtRetry() called from within this block does not
                    // cancel this (already-executing, non-suspending) coroutine.
                    retryJob = null

                    try {
                        retryCleanupDebtSafely()
                        val unresolved = cleanupDebt
                        if (unresolved != null) {
                            publishDebt(unresolved)
                            scheduleDebtRetry(unresolved)
                        } else {
                            // Debt cleared: nudge reconciliation.
                            latestSnapshot.get()
                                ?.let { events.trySend(ControllerEvent.Snapshot(it)) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        // R5 fix #3: top-level failure boundary.
                        // An ordinary exception (e.g. mergeUnresolved require() violation)
                        // must not kill the retry job permanently. Preserve the
                        // pre-transaction debt, append a typed supervisor failure and
                        // reschedule with bounded backoff.
                        safeReport("proxy.cleanup_retry.transaction", t)
                        val preserved = cleanupDebt
                        if (preserved != null) {
                            val faulted = preserved.copy(
                                attempt = preserved.attempt + 1,
                                failures = preserved.failures +
                                    CleanupFailure("retry_transaction", t),
                            )
                            cleanupDebt = faulted
                            publishDebt(faulted)
                            scheduleDebtRetry(faulted)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled externally (newer scheduleDebtRetry call) — OK.
            }
        }
    }

    private fun mergeDebt(incoming: CleanupDebt?) {
        if (incoming == null) return
        val merged = cleanupDebt?.mergeUnresolved(incoming) ?: incoming
        cleanupDebt = merged.copy(generation = nextDebtGeneration++, attempt = 0)
        retryJob?.cancel()
        retryJob = null
    }

    // -----------------------------------------------------------------------
    // Waiting / recovery / terminal stop
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
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false, serviceHandlePending = null,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = false,
                        featureStopPending = true, serviceWasActivated = true,
                        firewallHandleGeneration = null,
                        failures = emptyList(), generation = nextDebtGeneration++, attempt = 0,
                    )
                )
            } else {
                val featureReport = try {
                    withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { service.stopFeature(reason) }
                        ?: CleanupReport.failure("feature_stop_timeout",
                            RuntimeException("feature_stop timed out"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    CleanupReport.failure("feature_stop", t)
                }
                if (featureReport.failures.isEmpty()) {
                    serviceActivated = false
                } else {
                    mergeDebt(
                        CleanupDebt(
                            listenerClosePending = false, serviceHandlePending = null,
                            firewallHandlePending = null, firewallDenyPending = false,
                            firewallStopPending = false, daemonCleanPending = false,
                            featureStopPending = true, serviceWasActivated = true,
                            firewallHandleGeneration = null,
                            failures = featureReport.failures,
                            generation = nextDebtGeneration++, attempt = 0,
                        )
                    )
                }
            }
        }
        // Retries run directly in cleanupScope (survive worker exit).
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun safeEmergencyClose(reason: String): CleanupOutcome {
        return try {
            withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) {
                service.emergencyCloseListener(reason)
            } ?: run {
                val f = CleanupFailure("emergency_close_timeout",
                    RuntimeException("emergencyCloseListener timed out"))
                CleanupOutcome(
                    CleanupReport(listOf(f)),
                    CleanupDebt(
                        listenerClosePending = true,
                        serviceHandlePending = applied?.service,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = false,
                        featureStopPending = false, serviceWasActivated = serviceActivated,
                        firewallHandleGeneration = null,
                        failures = listOf(f), generation = nextDebtGeneration++, attempt = 0,
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val f = CleanupFailure("emergency_close", t)
            CleanupOutcome(
                CleanupReport(listOf(f)),
                CleanupDebt(
                    listenerClosePending = true,
                    serviceHandlePending = applied?.service,
                    firewallHandlePending = null, firewallDenyPending = false,
                    firewallStopPending = false, daemonCleanPending = false,
                    featureStopPending = false, serviceWasActivated = serviceActivated,
                    firewallHandleGeneration = null,
                    failures = listOf(f), generation = nextDebtGeneration++, attempt = 0,
                )
            )
        }
    }

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved, failures = debt.failures, retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) = publishSafely(debtState(debt))

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try { stateSink.publish(state) } catch (f: Throwable) { safeReport("proxy.state_publish", f) }
    }

    private fun safeReport(
        category: String, failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    ) {
        try { reporter.report(category, failure, cleanupFailures) } catch (_: Throwable) { }
    }
}

// ---------------------------------------------------------------------------
// Extension helpers on DesiredProxyState
// ---------------------------------------------------------------------------

fun DesiredProxyState.runtimeKey(upstream: ProxyVpnUpstream): RuntimeKey =
    RuntimeKey(
        tcpPort = settings.tcpPort,
        udpPortRange = if (settings.udpEnabled) settings.udpPortRange else null,
        credentialsVersion = settings.credentialsVersion,
        vpnNetworkHandle = upstream.handle,
        downstreams = downstreams.map { ds ->
            ds.interfaceName to listOfNotNull(ds.ipv4Address).map { normalizeIpv4(it) }.sorted()
        },
        backendVersion = 1,
        daemonGeneration = daemonGeneration ?: 0L,
    )

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
