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
// Step 6 + Step 10 — ProxyOnlyController (R4-corrected)
//
// R4 fixes applied:
//   #1  Ordinary cleanup exceptions now record debt and continue (stepSucceeded
//       in CleanupAccumulator; attempt() in retryCleanupDebtSafely) rather than
//       escaping and leaving handles untracked after applied=null.
//   #2  cleanupScope retries execute directly under stateMutex rather than via
//       the events channel, which has no consumer after the worker exits.
//       RetryCleanupDebt event type removed.
//   #3  RuntimeKey includes daemonGeneration so any daemon restart invalidates the
//       fast path and forces deny-first recreation of the firewall runtime.
//   #5  Strict literal IPv4 parser (ProxyModels.kt, no DNS).
//   #6  Strict MAC parser, null → drop client (ProxyModels.kt).
//   #7  Clients with empty IPv4 lists dropped in normalization (ProxyModels.kt).
//   #9  Zero valid endpoints after normalization publishes FailClosed
//       (NoReachableDownstreamAddress) rather than WaitingForTethering.
//   #10 Emergency close failures NOT added to acc.failures; they are carried
//       exclusively in the returned emergency debt to avoid duplication.
//   #11 retryCleanupDebtSafely builds one combined debt locally and commits once.
//   #12 Successful daemon-clean retry updates sanitizedDaemonGeneration so the
//       next reconciliation does not re-sanitize the same daemon generation.
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
     * R4 fix #2: cleanup retry jobs execute directly in this scope under
     * [stateMutex], NOT by sending events to the worker channel.
     *
     * Must outlive [scope] so terminal cleanup retries survive after the worker
     * coroutine's finally block has completed. Production callers must supply a
     * scope tied to the service/application lifecycle.
     */
    private val cleanupScope: CoroutineScope,
) {
    // -----------------------------------------------------------------------
    // All mutable state is accessed only under stateMutex.
    // Exception: latestSnapshot is AtomicReference for lock-free snapshot reads
    // from the collector coroutine.
    // -----------------------------------------------------------------------

    /**
     * R4 fix #2: serializes all state mutations between the worker coroutine and
     * the cleanup retry coroutines launched in cleanupScope.
     */
    private val stateMutex = Mutex()
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)
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
            // R4 fix #2: final cleanup under stateMutex so it doesn't race with
            // any in-flight cleanup retry that cleanupScope may be executing.
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
            // Attempt to clear outstanding debt before making forward progress.
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
            val epoch = firewall.cleanOrDenyBeforeRestart()
            if (epoch == null) {
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false, serviceHandlePending = null,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = true,
                        featureStopPending = false, serviceWasActivated = serviceActivated,
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
            // R4 fix #3: daemon generation change forces a full key change via
            // RuntimeKey.daemonGeneration, so any existing Applied handle from
            // the old daemon will be torn down through the key-mismatch path.
            firewallGeneration = AtomicLong(epoch)
            sanitizedDaemonGeneration = next.daemonGeneration
        }

        // Compute downstream endpoints; require at least one routable address.
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
        // R4 fix #9: distinguish "no tethering interfaces" from "interfaces present
        // but no routable address". Publish FailClosed for the latter.
        if (downstreamEndpoints.isEmpty()) {
            enterWaiting(
                next,
                ProxyOnlyState.FailClosed(FailClosedReason.NoReachableDownstreamAddress),
                "no routable downstream IPv4",
            )
            return
        }

        // R4 fix #3: daemonGeneration is in the key; daemon restart forces full rebuild.
        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            // Fast path — runtime key unchanged; replace ACL and firewall config only.
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
        val partial = AppliedProxyState(key)
        applied = partial

        partial.firewall = firewall.start(
            next.firewallConfig(denyAll = true, generationCounter = firewallGeneration)
        )

        // R3 fix B6: credential-fetch CancellationException rethrown.
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
    // Cleanup creation — fully local transaction; no mergeDebt calls.
    //
    // R4 fix #10: emergency close failures are NOT added to acc.failures.
    //   They are carried exclusively in the returned emergency debt. Adding them
    //   to acc.failures AND including them in the merged debt duplicated failures.
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
        // R4 fix #10: collect emergency debt locally; do NOT add its failures to
        // acc.failures. The emergency debt carries its own failure list; merging
        // them into acc would duplicate them in the combined debt's failure list.
        var emergencyDebt: CleanupDebt? = null
        if (!serviceResolved && serviceActivated) {
            val outcome = safeEmergencyClose(reason)
            listenerResolved = !outcome.report.hasCriticalFailure
            // outcome.report.failures intentionally NOT added to acc.failures
            emergencyDebt = outcome.debt
        }

        var firewallResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            val stopped = acc.stepSucceeded("firewall_stop") { firewall.stop(current.firewall!!) }
            if (stopped) {
                firewallResolved = true
                denyResolved = true  // §D: successful stop subsumes deny
            }
        }

        val primaryDebt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            firewallHandlePending = current.firewall.takeUnless { firewallResolved },
            firewallDenyPending = current.firewall != null && !denyResolved,
            firewallStopPending = current.firewall != null && !firewallResolved,
            daemonCleanPending = current.firewall != null && !daemonAvailable,
            featureStopPending = false,
            serviceWasActivated = serviceActivated,
            failures = acc.failures.toList(),
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        // R4 fix #11 (partial): combine primary and emergency debt into one outcome
        // locally so the caller merges exactly once.
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
    // Cleanup debt retry — fully local transaction (R4 fix #11).
    //
    // All steps record failures without aborting.
    // R4 fix #1: attempt() catches ordinary exceptions so independent steps run.
    // R4 fix #12: successful daemon-clean updates sanitizedDaemonGeneration.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        val failures = mutableListOf<CleanupFailure>()
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

        // R4 fix #1: attempt() catches ordinary exceptions so independent steps run.
        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val r = withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { block() }
                if (r == null) {
                    failures += CleanupFailure(name, RuntimeException("step '$name' timed out"))
                    safeReport("proxy.cleanup_debt.$name.timeout", RuntimeException("step '$name' timed out"))
                    false
                } else {
                    failures += r.failures
                    r.failures.isEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

        // R4 fix #11: collect emergency outcome locally; merge after updated is built.
        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && serviceActivated) {
            val outcome = safeEmergencyClose("cleanup debt retry")
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            // R4 fix #10: DO NOT add outcome.report.failures to failures here.
            // They live in emergencyOutcomeDebt and will appear exactly once
            // in the combined debt after merge.
            emergencyOutcomeDebt = outcome.debt
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
                denyPending = false // §D: successful stop subsumes deny
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
                // R4 fix #12: record the sanitized daemon generation so the next
                // reconciliation does not repeat cleanOrDenyBeforeRestart unnecessarily.
                sanitizedDaemonGeneration = latestSnapshot.get()?.daemonGeneration
            } else if (epoch == null) {
                // null without exception = timeout; already covered by the
                // outer try/catch block; add a diagnostic failure.
                if (failures.none { it.step == "daemon_clean" }) {
                    failures += CleanupFailure("daemon_clean",
                        RuntimeException("cleanOrDenyBeforeRestart returned null (timed out)"))
                }
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

        // R4 fix #11: single combined commit — build the merged result locally,
        // then assign cleanupDebt exactly once.
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
    // R4 fix #2: cleanup retries execute directly in cleanupScope under
    // stateMutex — not via events.send() which routes to a dead channel after
    // the worker exits.
    // -----------------------------------------------------------------------

    private fun scheduleDebtRetry(debt: CleanupDebt) {
        retryJob?.cancel()
        retryJob = cleanupScope.launch {
            delay(cleanupBackoff(debt.attempt))
            try {
                stateMutex.withLock {
                    retryCleanupDebtSafely()
                    val unresolved = cleanupDebt
                    if (unresolved != null) {
                        publishDebt(unresolved)
                        scheduleDebtRetry(unresolved)
                    } else {
                        // Debt cleared: nudge reconciliation if worker may still be alive.
                        // trySend on CONFLATED never blocks; harmless if worker has exited.
                        latestSnapshot.get()?.let { events.trySend(ControllerEvent.Snapshot(it)) }
                    }
                }
            } catch (_: CancellationException) {
                // Retry job was cancelled (e.g. by a newer scheduleDebtRetry call) — OK.
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
                // Defer feature stop until service handle and listener are resolved.
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false, serviceHandlePending = null,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = false,
                        featureStopPending = true, serviceWasActivated = true,
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
                            failures = featureReport.failures,
                            generation = nextDebtGeneration++, attempt = 0,
                        )
                    )
                }
            }
        }
        // R4 fix #2: scheduleDebtRetry executes directly in cleanupScope, so
        // terminal debt retries survive after the worker finally block completes.
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
                    firewallHandlePending = applied?.firewall,
                    firewallDenyPending = applied?.firewall != null,
                    firewallStopPending = applied?.firewall != null,
                    daemonCleanPending = true, featureStopPending = false,
                    serviceWasActivated = serviceActivated,
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
        // R4 fix #3: include daemon generation so key changes on every restart.
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
