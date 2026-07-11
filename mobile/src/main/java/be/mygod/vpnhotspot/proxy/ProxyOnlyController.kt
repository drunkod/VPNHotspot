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
// Step 6 + Step 10 — ProxyOnlyController (R7-corrected)
//
// R7 fixes applied over R6 baseline:
//
//   Blocker #1 (epoch not overwritten): firewall.start() return value is
//     validated against (sanitizedSessionId, sanitizedEpoch) rather than
//     rewritten via .copy(). If the daemon returns a mismatched handle the
//     controller fails closed without starting the backend.
//
//   Blocker #2 (exact epoch equality): cleanupApplied() and retryCleanupDebt-
//     Safely() use == comparison for both sessionId and epoch. A handle with
//     a future (>) or conflicting epoch is treated identically to an unknown-
//     provenance handle: no IPC, daemonCleanPending = true.
//
//   Blocker #3 (collision-safe handles): ProxyFirewallHandle(sessionId, epoch,
//     id) is the authoritative identity triple. cleanOrDenyBeforeRestart()
//     returns SanitationResult(sessionId, epoch). The controller tracks both
//     sanitizedSessionId and sanitizedEpoch so that a restarted daemon with
//     the same epoch number cannot validate old handles.
//
//   Blocker #4 (applied cleared too early): applied = null is moved to AFTER
//     the full CleanupOutcome is constructed. If mergeUnresolved() throws, the
//     applied state is still set so recoverSafely() → cleanupApplied() retries
//     with the same handles. Second-attempt IPC calls are idempotent.
//
//   High #8 (service notification after sanitation failure): sanitation null-
//     return path now calls enterWaitingIfActive(state) before returning.
//
//   High #9 (./ .. interface names): isValidInterfaceName() rejects "." and
//     ".." (fixed in ProxyModels.kt).
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
     * Which controller-observed daemon generation was last sanitized.
     * Used to detect when the daemon restarts (different generation → re-sanitize).
     * Not the same as [sanitizedSessionId]: this is the Kotlin-side generation
     * counter from [DesiredProxyState.daemonGeneration]; [sanitizedSessionId] is
     * the authoritative identity from the daemon's [SanitationResult].
     */
    private var sanitizedDaemonGeneration: Long? = null

    /**
     * R7: daemon-issued session identity from the last successful
     * [ProxyFirewallClient.cleanOrDenyBeforeRestart] call.
     *
     * A [ProxyFirewallHandle] is current iff its sessionId == this value AND
     * its epoch == [sanitizedEpoch]. Any other combination is either dominated
     * (same session, lower epoch) or a conflict requiring re-sanitation.
     *
     * Reset to null when the daemon becomes unavailable or a new daemon
     * generation is observed (before IPC, so handles become unknown-provenance).
     */
    private var sanitizedSessionId: Long? = null

    /**
     * R6/R7: daemon-acknowledged sanitation epoch from the last successful
     * [ProxyFirewallClient.cleanOrDenyBeforeRestart] call.
     *
     * Together with [sanitizedSessionId] this forms the complete handle identity.
     * Reset to null in the same situations as [sanitizedSessionId].
     */
    private var sanitizedEpoch: Long? = null

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
    // Ordering:
    //   1. Daemon sanitation gate (before debt retry).
    //   2. Existing cleanup debt retry.
    //   3. New runtime startup (deny-first → probe → allow).
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

        // ── Daemon sanitation gate — BEFORE debt retry ──────────────────────
        if (!next.daemonHealthy || next.daemonGeneration == null) {
            // Daemon unavailable: invalidate all sanitation markers.
            sanitizedDaemonGeneration = null
            sanitizedSessionId = null
            sanitizedEpoch = null
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
            // R6/R7: Reset epoch AND session ID BEFORE the IPC call so that any
            // handle currently in applied state is unknown-provenance (requires
            // daemonCleanPending) during the cleanupApplied on failure.
            sanitizedSessionId = null
            sanitizedEpoch = null

            val result = firewall.cleanOrDenyBeforeRestart()
            if (result == null) {
                // R6 blocker #2 / R7 High #8:
                //   1. Contain any active listener first (cleanupApplied handles this).
                //   2. Update the foreground notification (enterWaitingIfActive).
                //   3. Publish failure state.
                withContext(NonCancellable) {
                    val outcome = cleanupApplied("sanitation failed", daemonAvailable = false)
                    mergeDebt(outcome.debt)
                }
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
                // R7 High #8: move foreground notification to waiting before returning.
                enterWaitingIfActive(state)
                publishSafely(state)
                cleanupDebt?.let(::scheduleDebtRetry)
                return
            }

            firewallGeneration = AtomicLong(result.epoch)
            sanitizedDaemonGeneration = next.daemonGeneration
            sanitizedSessionId = result.sessionId
            sanitizedEpoch = result.epoch
        }
        // ── End sanitation gate ──────────────────────────────────────────────

        // Retry existing cleanup debt. Stale handles are dominated or conflict-
        // detected against the committed (sanitizedSessionId, sanitizedEpoch).
        cleanupDebt?.let {
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

        // R7 blocker #4: applied = null happens inside cleanupApplied() AFTER
        // the debt is successfully constructed. Set applied BEFORE IPC so that
        // if an exception escapes, cleanupApplied() will see the partial state.
        val partial = AppliedProxyState(key = key)
        applied = partial

        // R7 blocker #1: Do NOT manufacture handle provenance with .copy().
        // Call start() and validate the returned (sessionId, epoch) against the
        // controller's committed sanitation markers. Both must match exactly.
        val returnedHandle = firewall.start(
            next.firewallConfig(denyAll = true, generationCounter = firewallGeneration)
        )
        if (returnedHandle.sessionId != sanitizedSessionId || returnedHandle.epoch != sanitizedEpoch) {
            withContext(NonCancellable) {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.StartupSanitationFailed(
                            "firewall.start returned (session=${returnedHandle.sessionId}, " +
                                "epoch=${returnedHandle.epoch}) but expected " +
                                "(session=$sanitizedSessionId, epoch=$sanitizedEpoch)"
                        )
                    )
                )
            }
            return
        }
        partial.firewall = returnedHandle

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
    // R7 blocker #2: exact (sessionId, epoch) equality determines whether a
    // handle is current, dominated, or a conflict. See cases A–D below.
    //
    // R7 blocker #4: applied = null is set ONLY after the full CleanupOutcome
    // has been constructed (after mergeUnresolved). If mergeUnresolved() throws,
    // applied is still set so a retry of this function (from recoverSafely) can
    // attempt cleanup again. Second-attempt IPC calls are idempotent.
    //
    // Handle disposition:
    //   A. (sessionId == sId && epoch == sEpoch) → current: IPC allowed.
    //   B. (sessionId == sId && epoch <  sEpoch) → dominated: resolved, no IPC.
    //   C. (sessionId == sId && epoch >  sEpoch) → future conflict: no IPC,
    //        daemonCleanPending = true.
    //   D. (sessionId != sId || sId == null)     → wrong session: no IPC,
    //        daemonCleanPending = true.
    //   (B = "safely dominated by sanitation of the same session")
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied
            ?: return CleanupOutcome(CleanupReport.noOp("nothing applied"), null)
        // R7 blocker #4: do NOT clear applied here. It is cleared after the
        // CleanupOutcome is fully constructed (see bottom of this function).

        val acc = CleanupAccumulator(reporter)
        val fw = current.firewall
        val sId = sanitizedSessionId
        val sEpoch = sanitizedEpoch

        val sameSession = fw != null && sId != null && fw.sessionId == sId
        val firewallCurrent = sameSession && sEpoch != null && fw!!.epoch == sEpoch    // case A
        val firewallDominated = sameSession && sEpoch != null && fw!!.epoch < sEpoch   // case B
        // Cases C and D: conflict or wrong session → cannot safely IPC
        val firewallNeedsClean = fw != null && !firewallCurrent && !firewallDominated

        var denyResolved = fw == null || firewallDominated
        var firewallResolved = fw == null || firewallDominated

        // Case A only: attempt denyAll IPC before stopping backend.
        if (firewallCurrent && daemonAvailable) {
            val denied = acc.stepSucceeded("deny") { firewall.denyAll(fw!!) }
            if (denied) denyResolved = true
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

        // Case A only: stop IPC after listener is contained.
        if (firewallCurrent && daemonAvailable) {
            val stopped = acc.stepSucceeded("firewall_stop") { firewall.stop(fw!!) }
            if (stopped) {
                firewallResolved = true
                denyResolved = true  // §D: stop subsumes deny
            }
        }

        val primaryDebt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            // Carry handle in debt only for case A (current epoch) + not yet resolved.
            firewallHandlePending = if (firewallCurrent && !firewallResolved) fw else null,
            firewallDenyPending = firewallCurrent && !denyResolved,
            firewallStopPending = firewallCurrent && !firewallResolved,
            // Cases C, D: conflict/unknown session → require daemon sanitation.
            // Case B: dominated → already safe, no pending needed.
            daemonCleanPending = firewallNeedsClean,
            featureStopPending = false,
            serviceWasActivated = serviceActivated,
            failures = acc.failures.toList(),
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        // This may throw (mergeUnresolved require() violation). applied is still
        // set at this point; any exception will be caught by recoverSafely().
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

        // R7 blocker #4: clear applied only after a complete, non-throwing result.
        applied = null
        return CleanupOutcome(acc.report(), combinedDebt)
    }

    // -----------------------------------------------------------------------
    // Cleanup debt retry — single local transaction with atomic state commit.
    //
    // R7 blocker #2: exact (sessionId, epoch) equality for current handles;
    //   dominated = same session, epoch < sanitizedEpoch (resolved without IPC);
    //   conflict/wrong session = daemonCleanPending required.
    //
    // R6 High #9: local accumulators committed atomically after combined-debt
    //   computation. Controller state is unchanged if mergeUnresolved() throws.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        val failures = mutableListOf<CleanupFailure>()
        val daemonHealthy = latestSnapshot.get()?.daemonHealthy == true

        // Local accumulators — committed atomically at the bottom.
        var newServiceActivated = serviceActivated
        var newFirewallGenerationEpoch: Long? = null
        var newSanitizedSessionId: Long? = sanitizedSessionId
        var newSanitizedEpoch: Long? = sanitizedEpoch
        var newSanitizedDaemonGen: Long? = sanitizedDaemonGeneration

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

        if (serviceHandle != null && newServiceActivated) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false
            }
        }

        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && newServiceActivated) {
            val outcome = safeEmergencyClose("cleanup debt retry")
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            emergencyOutcomeDebt = outcome.debt
        }

        // R7 blocker #2: classify handle by (sessionId, epoch) against
        // the LOCAL accumulators (pre-daemon_clean values at this point).
        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending

        val handleSameSession = firewallHandle != null &&
            newSanitizedSessionId != null &&
            firewallHandle!!.sessionId == newSanitizedSessionId
        val handleCurrent = handleSameSession &&
            newSanitizedEpoch != null &&
            firewallHandle!!.epoch == newSanitizedEpoch
        // Dominated = same session, epoch strictly less than current epoch.
        // The sanitation that produced the current epoch installed deny-all
        // atomically for the old handle; no further IPC is needed.
        val handleDominated = handleSameSession &&
            newSanitizedEpoch != null &&
            firewallHandle!!.epoch < newSanitizedEpoch
        val handleStale = firewallHandle != null && !handleCurrent

        var daemonCleanPending = debt.daemonCleanPending

        if (handleStale) {
            // Conflict or wrong/unknown session: cannot prove containment without
            // a new sanitation cycle. Dominated (same session, older epoch) is
            // the only case where IPC is unnecessary and sanitation is not required.
            if (!handleDominated) daemonCleanPending = true
            firewallHandle = null
            denyPending = false
            stopPending = false
        } else if (firewallHandle != null) {
            // Handle is current — (sessionId, epoch) == (sanitizedSessionId, sanitizedEpoch).
            if (daemonHealthy && denyPending) {
                if (attempt("deny") { firewall.denyAll(firewallHandle!!) }) denyPending = false
            }
            if (daemonHealthy && stopPending) {
                if (attempt("firewall_stop") { firewall.stop(firewallHandle!!) }) {
                    stopPending = false
                    firewallHandle = null
                    denyPending = false
                }
            }
        }

        if (daemonCleanPending && daemonHealthy) {
            val result = try {
                withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { firewall.cleanOrDenyBeforeRestart() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                failures += CleanupFailure("daemon_clean", t)
                null
            }
            if (result != null) {
                daemonCleanPending = false
                newFirewallGenerationEpoch = result.epoch
                newSanitizedEpoch = result.epoch
                newSanitizedSessionId = result.sessionId
                newSanitizedDaemonGen = latestSnapshot.get()?.daemonGeneration
            } else if (failures.none { it.step == "daemon_clean" }) {
                failures += CleanupFailure("daemon_clean",
                    RuntimeException("cleanOrDenyBeforeRestart returned null or timed out"))
            }
        }

        var featureStopPending = debt.featureStopPending
        if (featureStopPending && newServiceActivated && serviceHandle == null && !listenerPending) {
            if (attempt("feature_stop") { service.stopFeature("cleanup debt retry") }) {
                featureStopPending = false
                newServiceActivated = false
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

        // Compute combined debt BEFORE committing state (mergeUnresolved may throw).
        val combined = if (emergencyOutcomeDebt != null && !updated.isResolved) {
            updated.mergeUnresolved(emergencyOutcomeDebt)
                .copy(generation = nextDebtGeneration++, attempt = 0)
        } else if (emergencyOutcomeDebt != null) {
            emergencyOutcomeDebt.copy(generation = nextDebtGeneration++, attempt = 0)
        } else {
            updated
        }

        // Atomic commit: all controller-state mutations happen together.
        cleanupDebt = combined.takeUnless { it.isResolved }
        serviceActivated = newServiceActivated
        newFirewallGenerationEpoch?.let { firewallGeneration = AtomicLong(it) }
        sanitizedEpoch = newSanitizedEpoch
        sanitizedSessionId = newSanitizedSessionId
        sanitizedDaemonGeneration = newSanitizedDaemonGen
    }

    // -----------------------------------------------------------------------
    // Debt retry scheduler
    // -----------------------------------------------------------------------

    private fun scheduleDebtRetry(debt: CleanupDebt) {
        retryJob?.cancel()
        retryJob = cleanupScope.launch {
            delay(cleanupBackoff(debt.attempt))
            try {
                stateMutex.withLock {
                    // R5 fix #7: clear self-reference before work so that
                    // scheduleDebtRetry() called from within does not cancel
                    // this (already-executing) coroutine.
                    retryJob = null

                    try {
                        retryCleanupDebtSafely()
                        val unresolved = cleanupDebt
                        if (unresolved != null) {
                            publishDebt(unresolved)
                            scheduleDebtRetry(unresolved)
                        } else {
                            latestSnapshot.get()
                                ?.let { events.trySend(ControllerEvent.Snapshot(it)) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        // Top-level failure boundary: preserve pre-transaction debt.
                        // R7 High #9: cleanupDebt is only committed at the bottom
                        // of retryCleanupDebtSafely(); pre-transaction value is intact.
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
            } catch (_: CancellationException) { }
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
                    firewallHandlePending = null, firewallDenyPending = false,
                    firewallStopPending = false, daemonCleanPending = false,
                    featureStopPending = false, serviceWasActivated = serviceActivated,
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
