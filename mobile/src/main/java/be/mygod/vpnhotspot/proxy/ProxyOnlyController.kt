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
// Step 6 + Step 10 — ProxyOnlyController (R10-corrected)
//
// R8 fixes (retained):
//   #1 partial.firewall assigned before session/epoch validation.
//   #2 cleanupApplied() merges global debt before clearing applied.
//   #3 debt-retry sanitation captures generation before IPC.
//   #4 fast-path replace() requires exact (sessionId, epoch) match.
//
// R9 fixes (retained):
//   #1 service-handle conflict discards both handles; feature_stop reachable.
//   #2 primary sanitation gate captures + revalidates daemon generation.
//   #3 deny-to-allow replace() checks generation after probes.
//
// R10 fixes applied over R9 baseline:
//
//   Blocker #1 (successful feature_stop resurrects stale emergency debt):
//     retryCleanupDebtSafely() now discards emergencyOutcomeDebt on successful
//     feature_stop. Authoritative service teardown dominates all service/listener
//     observations from the same transaction; emergency debt is not merged back.
//
//   Blocker #2 (fast-path crosses daemon restart): fast-path replace() now also
//     requires latestSnapshot.daemonGeneration == next.daemonGeneration ==
//     sanitizedDaemonGeneration. A generation mismatch nulls session/epoch markers
//     and triggers cleanupApplied(daemonAvailable=false) → daemonCleanPending.
//
//   Blocker #3 (cleanup/retry IPC can target a restarted daemon):
//     cleanupApplied() classifies a handle as current only when the latest observed
//     generation equals sanitizedDaemonGeneration in addition to session/epoch match.
//     retryCleanupDebtSafely() classifies a handle as current only when
//     capturedDaemonGeneration == newSanitizedDaemonGen in addition to epoch match.
//     Generation mismatch falls through to the handleStale path → daemonCleanPending.
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
    // Mutable state — all accessed only under stateMutex.
    // Exception: latestSnapshot is AtomicReference for lock-free reads by the
    // collector coroutine.
    // -----------------------------------------------------------------------

    private val stateMutex = Mutex()
    private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)

    /**
     * Controller-observed daemon generation that was last sanitized.
     * Compared to [DesiredProxyState.daemonGeneration] to detect daemon restarts.
     * Not nulled on firewall.start() mismatch — only on actual daemon
     * unavailability or daemon restart. Mismatch re-sanitation happens via
     * [CleanupDebt.daemonCleanPending].
     */
    private var sanitizedDaemonGeneration: Long? = null

    /**
     * Daemon-issued session identity from the last successful
     * [ProxyFirewallClient.cleanOrDenyBeforeRestart]. Nulled whenever the
     * daemon becomes unavailable or a firewall.start() provenance mismatch
     * is detected. A handle is current iff its sessionId == this value.
     */
    private var sanitizedSessionId: Long? = null

    /**
     * Daemon-acknowledged sanitation epoch from the last successful
     * [ProxyFirewallClient.cleanOrDenyBeforeRestart]. Together with
     * [sanitizedSessionId] forms the complete handle identity. Nulled in the
     * same situations as [sanitizedSessionId].
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
            sanitizedDaemonGeneration = null
            sanitizedSessionId = null
            sanitizedEpoch = null
            withContext(NonCancellable) {
                cleanupApplied("daemon unavailable", daemonAvailable = false)
            }
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(FailClosedReason.RootDaemonUnavailable)
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }

        if (sanitizedDaemonGeneration != next.daemonGeneration) {
            sanitizedSessionId = null
            sanitizedEpoch = null

            // R9 blocker #2: capture the expected daemon generation before IPC so
            // a generation change while the call is suspended can be detected.
            val capturedDaemonGeneration = next.daemonGeneration

            val result = firewall.cleanOrDenyBeforeRestart()

            // Recheck: did the collector observe a new daemon generation while the
            // IPC was in flight? If so, the acknowledgement belongs to the old
            // daemon and must not be committed as sanitation for the new one.
            val currentDaemonGeneration = latestSnapshot.get()?.daemonGeneration
            if (currentDaemonGeneration != capturedDaemonGeneration) {
                val raceMsg = "daemon generation changed from $capturedDaemonGeneration to " +
                    "$currentDaemonGeneration during primary cleanOrDenyBeforeRestart; " +
                    "acknowledgement discarded"
                withContext(NonCancellable) {
                    cleanupApplied("primary sanitation race", daemonAvailable = false)
                }
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false, serviceHandlePending = null,
                        firewallHandlePending = null, firewallDenyPending = false,
                        firewallStopPending = false, daemonCleanPending = true,
                        featureStopPending = false, serviceWasActivated = serviceActivated,
                        failures = listOf(
                            CleanupFailure(
                                "primary_sanitation_race",
                                RuntimeException(raceMsg),
                            )
                        ),
                        generation = nextDebtGeneration++, attempt = 0,
                    )
                )
                val state = cleanupDebt?.let(::debtState)
                    ?: ProxyOnlyState.FailClosed(
                        FailClosedReason.StartupSanitationFailed("primary sanitation race")
                    )
                enterWaitingIfActive(state)
                publishSafely(state)
                cleanupDebt?.let(::scheduleDebtRetry)
                return
            }

            if (result == null) {
                withContext(NonCancellable) {
                    cleanupApplied("sanitation failed", daemonAvailable = false)
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
                enterWaitingIfActive(state)
                publishSafely(state)
                cleanupDebt?.let(::scheduleDebtRetry)
                return
            }

            firewallGeneration = AtomicLong(result.epoch)
            sanitizedDaemonGeneration = capturedDaemonGeneration
            sanitizedSessionId = result.sessionId
            sanitizedEpoch = result.epoch
        }
        // ── End sanitation gate ──────────────────────────────────────────────

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
        val currentFw = current?.firewall

        // R8 blocker #4: fast path requires exact (sessionId, epoch) provenance
        // in addition to RuntimeKey equality. A surviving applied runtime whose
        // firewall handle has wrong provenance (after recovery) falls through to
        // the key-change cleanup path below.
        // R10 blocker #2: also require that latestSnapshot.daemonGeneration still equals
        // both the reconciliation snapshot's generation and sanitizedDaemonGeneration.
        // The collector updates latestSnapshot outside stateMutex, so G2 can be observed
        // while G1 reconciliation owns the mutex. A stale G1 handle must not be submitted
        // via replace() to a transport now connected to G2.
        val latestGenFast = latestSnapshot.get()?.daemonGeneration
        if (current?.complete == true && current.key == key &&
            currentFw != null &&
            currentFw.sessionId == sanitizedSessionId &&
            currentFw.epoch == sanitizedEpoch &&
            latestGenFast == next.daemonGeneration &&
            latestGenFast == sanitizedDaemonGeneration) {
            firewall.replace(
                currentFw,
                next.firewallConfig(denyAll = false, generationCounter = firewallGeneration),
            )
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(ProxyOnlyState.Running(downstreamEndpoints))
            return
        }
        // Fast-path generation mismatch: treat handle as unknown provenance.
        if (current?.complete == true && current.key == key &&
            currentFw != null &&
            currentFw.sessionId == sanitizedSessionId &&
            currentFw.epoch == sanitizedEpoch &&
            (latestGenFast != next.daemonGeneration || latestGenFast != sanitizedDaemonGeneration)) {
            sanitizedSessionId = null
            sanitizedEpoch = null
            withContext(NonCancellable) {
                cleanupApplied(
                    "fast-path daemon generation mismatch: snapshot=$latestGenFast " +
                        "reconciliation=${next.daemonGeneration} " +
                        "sanitized=$sanitizedDaemonGeneration",
                    daemonAvailable = false,
                )
            }
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.StartupSanitationFailed("fast-path daemon generation mismatch")
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }

        withContext(NonCancellable) {
            cleanupApplied("runtime key changed", daemonAvailable = true)
        }
        cleanupDebt?.let { unresolved ->
            enterWaitingIfActive(debtState(unresolved))
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }

        publishSafely(ProxyOnlyState.StartingBackend)

        val partial = AppliedProxyState(key = key)
        applied = partial

        // R8 blocker #1: assign the returned handle to partial BEFORE validation.
        // If the acknowledgement is mismatched, cleanupApplied() will see the
        // handle as unknown-provenance (we null the sanitation markers first) and
        // create daemonCleanPending debt. No handle-specific IPC on mismatched
        // provenance; re-sanitation is forced via the debt.
        val returnedHandle = firewall.start(
            next.firewallConfig(denyAll = true, generationCounter = firewallGeneration)
        )
        partial.firewall = returnedHandle  // assigned BEFORE check

        if (returnedHandle.sessionId != sanitizedSessionId || returnedHandle.epoch != sanitizedEpoch) {
            val expectedSession = sanitizedSessionId
            val expectedEpoch = sanitizedEpoch
            // Null session/epoch markers: cleanupApplied() classifies the handle as
            // unknown-provenance → firewallNeedsClean = true → daemonCleanPending = true.
            // sanitizedDaemonGeneration is NOT nulled: re-sanitation happens through
            // daemonCleanPending debt rather than re-triggering the gate.
            sanitizedSessionId = null
            sanitizedEpoch = null
            withContext(NonCancellable) {
                cleanupApplied(
                    "firewall.start() session/epoch mismatch: got " +
                        "(${returnedHandle.sessionId}/${returnedHandle.epoch}), " +
                        "expected ($expectedSession/$expectedEpoch)",
                    daemonAvailable = false,  // do not IPC against mismatched handle
                )
            }
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.StartupSanitationFailed("firewall.start() provenance mismatch")
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }

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

        // R9 blocker #3: validate daemon generation has not changed while backend
        // startup and probing were suspended. If it changed, the firewall handle
        // is no longer current for the new daemon; force cleanup and re-sanitation.
        if (latestSnapshot.get()?.daemonGeneration != next.daemonGeneration) {
            withContext(NonCancellable) {
                cleanupApplied(
                    "daemon generation changed during startup/probe",
                    daemonAvailable = false,
                )
            }
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.StartupSanitationFailed("daemon generation changed during startup")
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
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
        // R8: cleanupApplied() now calls mergeDebt() internally.
        cleanupApplied("startup probe failed", daemonAvailable = true)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    // -----------------------------------------------------------------------
    // Cleanup creation — fully local transaction.
    //
    // R8 blocker #2: mergeDebt() is called inside this function, BEFORE
    // applied = null. Since CleanupDebt.mergeUnresolved() is non-throwing
    // (typed conflict resolution), mergeDebt() always succeeds and applied
    // is always cleared in the same call. Callers do NOT call mergeDebt()
    // separately after this function.
    //
    // Handle disposition:
    //   A. (sessionId==sId && epoch==sEpoch && latestGen==sanitizedDaemonGen)
    //      → current: IPC allowed when daemonAvailable.
    //   B. (sessionId==sId && epoch<sEpoch)
    //      → dominated: resolved without IPC (sanitation covered it).
    //   C. any other combination, including generation mismatch
    //      → conflict/unknown: daemonCleanPending, no IPC.
    //
    // R10 blocker #3: firewall handle IPC (denyAll, stop) is only issued when
    // the latest observed daemon generation equals sanitizedDaemonGeneration.
    // A mismatched generation classifies even a session/epoch-matched handle as
    // unknown-provenance, preventing G1 IPC from reaching G2.
    // -----------------------------------------------------------------------

    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupReport {
        val current = applied ?: return CleanupReport.noOp("nothing applied")
        // applied is NOT cleared yet. It is cleared after mergeDebt() succeeds.

        val acc = CleanupAccumulator(reporter)
        val fw = current.firewall
        val sId = sanitizedSessionId
        val sEpoch = sanitizedEpoch

        // R10 blocker #3: snapshot generation before any IPC.
        val latestGen = latestSnapshot.get()?.daemonGeneration
        val generationSafe = latestGen != null && latestGen == sanitizedDaemonGeneration

        val sameSession = fw != null && sId != null && fw.sessionId == sId
        // Case A: current handle — session+epoch match AND generation is safe.
        val firewallCurrent = sameSession && sEpoch != null && fw!!.epoch == sEpoch && generationSafe
        // Case B: dominated — same session, older epoch, AND generation is safe.
        // R11: generationSafe is required here too. A lower-epoch handle from a different
        // daemon generation is not dominated by the current sanitation — the daemon restart
        // invalidates all prior handles regardless of epoch ordering. Without generationSafe,
        // a G2 session/G1 handle combination would fall into case B and skip daemonCleanPending.
        val firewallDominated = generationSafe && sameSession && sEpoch != null && fw!!.epoch < sEpoch
        // Case C: conflict/unknown or any generation mismatch — requires re-sanitation.
        val firewallNeedsClean = fw != null && !firewallCurrent && !firewallDominated

        var denyResolved = fw == null || firewallDominated
        var firewallResolved = fw == null || firewallDominated

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

        if (firewallCurrent && daemonAvailable) {
            val stopped = acc.stepSucceeded("firewall_stop") { firewall.stop(fw!!) }
            if (stopped) {
                firewallResolved = true
                denyResolved = true
            }
        }

        val primaryDebt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            firewallHandlePending = if (firewallCurrent && !firewallResolved) fw else null,
            firewallDenyPending = firewallCurrent && !denyResolved,
            firewallStopPending = firewallCurrent && !firewallResolved,
            daemonCleanPending = firewallNeedsClean,
            featureStopPending = false,
            serviceWasActivated = serviceActivated,
            failures = acc.failures.toList(),
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        val localDebt = when {
            primaryDebt != null && emergencyDebt != null ->
                primaryDebt.mergeUnresolved(emergencyDebt)
                    .copy(generation = nextDebtGeneration++, attempt = 0)
            primaryDebt != null -> primaryDebt
            emergencyDebt != null -> emergencyDebt.copy(
                generation = nextDebtGeneration++, attempt = 0,
            )
            else -> null
        }

        // R8 blocker #2: merge into global debt first (non-throwing).
        mergeDebt(localDebt)

        // Only clear applied AFTER the global merge succeeds.
        applied = null
        return acc.report()
    }

    // -----------------------------------------------------------------------
    // Cleanup debt retry — single local transaction with atomic state commit.
    //
    // R8 blocker #3: captures daemon generation before sanitation IPC and
    //   validates it has not changed after the call. A generation change during
    //   the call discards the acknowledgement and retains daemonCleanPending.
    // -----------------------------------------------------------------------

    private suspend fun retryCleanupDebtSafely() {
        val debt = cleanupDebt ?: return
        // R8 blocker #3: capture snapshot atomically before any IPC.
        val snapshot = latestSnapshot.get()
        val daemonHealthy = snapshot?.daemonHealthy == true
        val capturedDaemonGeneration = snapshot?.daemonGeneration

        var newServiceActivated = serviceActivated
        var newFirewallGenerationEpoch: Long? = null
        var newSanitizedSessionId: Long? = sanitizedSessionId
        var newSanitizedEpoch: Long? = sanitizedEpoch
        var newSanitizedDaemonGen: Long? = sanitizedDaemonGeneration

        val failures = mutableListOf<CleanupFailure>()

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

        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending

        val handleSameSession = firewallHandle != null &&
            newSanitizedSessionId != null &&
            firewallHandle!!.sessionId == newSanitizedSessionId
        // R10/R11: generation safety — the generation captured before this retry must equal
        // the generation that produced the current sanitation acknowledgement. A mismatch
        // means the daemon restarted since the last sanitation; all handles from the prior
        // generation are invalid regardless of session/epoch values.
        val generationSafe = capturedDaemonGeneration != null &&
            capturedDaemonGeneration == newSanitizedDaemonGen
        val handleEpochCurrent = handleSameSession &&
            newSanitizedEpoch != null &&
            firewallHandle!!.epoch == newSanitizedEpoch
        // R11: handleDominated also requires generationSafe. A lower-epoch handle from a
        // different daemon generation is not dominated by current sanitation — the daemon
        // restart invalidates all prior handles regardless of epoch ordering. Without
        // generationSafe here, a generation-mismatched handle would suppress daemonCleanPending.
        val handleDominated = generationSafe && handleSameSession &&
            newSanitizedEpoch != null &&
            firewallHandle!!.epoch < newSanitizedEpoch
        val handleCurrent = handleEpochCurrent && generationSafe
        val handleStale = firewallHandle != null && !handleCurrent

        var daemonCleanPending = debt.daemonCleanPending

        if (handleStale) {
            if (!handleDominated) daemonCleanPending = true
            firewallHandle = null
            denyPending = false
            stopPending = false
        } else if (firewallHandle != null) {
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
                // R8 blocker #3: validate generation hasn't changed during the call.
                val currentGeneration = latestSnapshot.get()?.daemonGeneration
                if (currentGeneration == capturedDaemonGeneration) {
                    daemonCleanPending = false
                    newFirewallGenerationEpoch = result.epoch
                    newSanitizedEpoch = result.epoch
                    newSanitizedSessionId = result.sessionId
                    newSanitizedDaemonGen = capturedDaemonGeneration
                } else {
                    // Generation changed while IPC was in flight. The acknowledgement
                    // belongs to the old generation and must not be stored as sanitation
                    // for the new one. Retain daemonCleanPending; reconcile will
                    // re-sanitize the new daemon via the sanitation gate.
                    failures += CleanupFailure(
                        "daemon_clean_race",
                        RuntimeException(
                            "daemon generation changed from $capturedDaemonGeneration to " +
                                "$currentGeneration during cleanOrDenyBeforeRestart; " +
                                "acknowledgement discarded"
                        )
                    )
                }
            } else if (failures.none { it.step == "daemon_clean" }) {
                failures += CleanupFailure("daemon_clean",
                    RuntimeException("cleanOrDenyBeforeRestart returned null or timed out"))
            }
        }

        var featureStopPending = debt.featureStopPending
        // R9 blocker #1 / R10 blocker #1:
        // feature_stop is allowed whenever serviceHandlePending is null; ProxyService
        // .stopFeature() uses its own internal backend handle authoritatively.
        // R10: on successful feature_stop, authoritative teardown dominates all earlier
        // emergency service/listener debt from this transaction — emergencyOutcomeDebt is
        // discarded so stale handles and listener flags are not reintroduced by the later
        // merge. A failed feature_stop retains emergencyOutcomeDebt for further retry.
        if (featureStopPending && newServiceActivated && serviceHandle == null) {
            if (attempt("feature_stop") { service.stopFeature("cleanup debt retry") }) {
                featureStopPending = false
                listenerPending = false
                newServiceActivated = false
                // Authoritative teardown dominates: discard emergency debt so it is not
                // merged back and cannot resurrect stale service/listener items.
                emergencyOutcomeDebt = null
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

        // Non-throwing merge (CleanupDebt.mergeUnresolved is non-throwing).
        val combined = if (emergencyOutcomeDebt != null && !updated.isResolved) {
            updated.mergeUnresolved(emergencyOutcomeDebt)
                .copy(generation = nextDebtGeneration++, attempt = 0)
        } else if (emergencyOutcomeDebt != null) {
            emergencyOutcomeDebt.copy(generation = nextDebtGeneration++, attempt = 0)
        } else {
            updated
        }

        // Atomic commit.
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
            cleanupApplied(reason, daemonAvailable = next.daemonHealthy)
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
            val report = cleanupApplied(
                "reconcile failure",
                daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
            )
            safeReport("proxy.reconcile", original, report.failures)
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
        cleanupApplied(
            reason,
            daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
        )

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
