package be.mygod.vpnhotspot.proxy

import android.app.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// ---------------------------------------------------------------------------
// Step 3 — Service/backend ownership and the persistent foreground service
// Sketch: docs/proxy-only/sketches/03-service-and-backend-ownership.md
//
// R3 fixes:
//   B1  Added missing import kotlinx.coroutines.CancellationException
//   B2  CleanupAccumulator.stepSucceeded uses withTimeoutOrNull so only the
//       step's own timeout is absorbed; outer/parent CancellationException
//       propagates naturally through withTimeoutOrNull without being caught.
//   B3  cleanOrDenyBeforeRestart() returns Long? (null = failure; non-null =
//       daemon-acknowledged new epoch) so the controller can initialize its
//       generation counter from the epoch rather than deriving it locally.
//
// R4 fix #1: CleanupAccumulator.stepSucceeded catches ordinary (non-cancellation)
// exceptions and records them as debt rather than propagating and leaving applied
// handles untracked after applied=null is already cleared.
// ---------------------------------------------------------------------------

internal const val CLEANUP_STEP_TIMEOUT_MS = 10_000L

// ---------------------------------------------------------------------------
// Handle types
// ---------------------------------------------------------------------------

@JvmInline value class ProxyServiceHandle(val id: Long)
@JvmInline value class ProxyBackendHandle(val id: Long)
/**
 * R6/R7: epoch-qualified, session-qualified firewall runtime handle.
 *
 * All three fields must be returned by the daemon in the start-acknowledgement
 * response; the controller MUST NOT manufacture them locally (no `.copy()`).
 *
 * Identity invariant:
 *   - [sessionId] uniquely identifies the daemon boot session. It changes on
 *     every daemon process restart and must never repeat (in practice: the daemon
 *     issues a monotonically increasing, crash-persistent counter).
 *   - [epoch] is the sanitation epoch within a session, issued by
 *     [ProxyFirewallClient.cleanOrDenyBeforeRestart] and returned in every
 *     subsequent start-acknowledgement for that session.
 *   - [id] is the runtime-specific identity within one epoch.
 *
 * A handle is current iff (sessionId, epoch) == controller's (sanitizedSessionId,
 * sanitizedEpoch). Any other (sessionId, epoch) combination is either dominated
 * (same session, lower epoch → sanitation already covered it) or a conflict
 * requiring re-sanitation (different session, or epoch > sanitizedEpoch).
 *
 * Not an @JvmInline value class because value classes may not have multiple fields.
 */
data class ProxyFirewallHandle(val sessionId: Long, val epoch: Long, val id: Long)

/**
 * Result of [ProxyFirewallClient.cleanOrDenyBeforeRestart].
 *
 * [sessionId] is an opaque daemon-session identity that must change on every
 * daemon process restart and never repeat within a lifetime.
 *
 * [epoch] is the ledger epoch reset by this sanitation. Handles created by
 * [ProxyFirewallClient.start] after a successful sanitation carry the same
 * (sessionId, epoch) pair.
 *
 * [daemonGeneration] is the daemon's authoritative transport/boot generation
 * from the same acknowledgement. The controller must compare it with the
 * observed desired-state generation before committing sanitation; it must never
 * substitute a locally inferred generation for this value.
 */
data class SanitationResult(
    val sessionId: Long,
    val epoch: Long,
    val daemonGeneration: Long,
)

// ---------------------------------------------------------------------------
// Report types
// ---------------------------------------------------------------------------

data class CleanupFailure(val step: String, val cause: Throwable)

data class CleanupReport(
    val failures: List<CleanupFailure> = emptyList(),
    val context: String = "",
) {
    val hasCriticalFailure: Boolean get() = failures.isNotEmpty()

    fun withContext(vararg ctx: String): CleanupReport =
        copy(context = (listOf(context) + ctx).filter { it.isNotEmpty() }.joinToString(" > "))

    companion object {
        fun noOp(reason: String) = CleanupReport(context = "no-op: $reason")
        fun empty() = CleanupReport()
        fun failure(step: String, cause: Throwable) =
            CleanupReport(failures = listOf(CleanupFailure(step, cause)))

        /**
         * R2 fix #5: stale handle is a CRITICAL failure — not context-only.
         * Mismatched handles must never clear SERVICE_HANDLE or LISTENER debt.
         */
        fun staleHandle(id: Long) = CleanupReport(
            failures = listOf(
                CleanupFailure(
                    "stale_handle",
                    IllegalStateException("mismatched backend handle id=$id — backend may still be active"),
                )
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Service activation / backend config
// ---------------------------------------------------------------------------

sealed interface ServiceActivation {
    data object Active : ServiceActivation
    data object ForegroundStartNotAllowed : ServiceActivation
}

data class ProxyBackendConfig(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val maxUdpAssociations: Int,
    val credentials: ProxyCredentials,
    val vpnNetworkHandle: Long,
    val backendVersion: Int,
)

data class ProxyBackendStats(
    val activeTcpConnections: Int,
    val activeUdpAssociations: Int,
)

// ---------------------------------------------------------------------------
// ProxyServiceClient interface
// ---------------------------------------------------------------------------

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

    /**
     * Attempt fail-closed listener containment.
     * Returns [CleanupOutcome] so the controller can merge debt on failure.
     * The backend handle is NEVER cleared by emergency close alone.
     */
    suspend fun emergencyCloseListener(reason: String): CleanupOutcome

    suspend fun stopFeature(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}

// ---------------------------------------------------------------------------
// ProxyBackend interface
// ---------------------------------------------------------------------------

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyBackendHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun emergencyCloseListener(handle: ProxyBackendHandle, reason: String): CleanupReport
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}

// ---------------------------------------------------------------------------
// ProxyFirewallClient interface
// R3 fix #3: cleanOrDenyBeforeRestart returns daemon-issued sanitation identity.
// ---------------------------------------------------------------------------

data class ProxyFirewallConfig(
    val downstreams: List<ProxyDownstreamConfig>,
    val tcpPort: Int,
    val udpPortRangeStart: Int,
    val udpPortRangeEnd: Int,
    val allowedClients: List<AllowedClient>,
    val generation: Long,
    val denyAllIpv4: Boolean,
    val denyAllIpv6: Boolean,
)

data class ProxyDownstreamConfig(
    val interfaceName: String,
    val ipv4Addresses: List<String>,
)

interface ProxyFirewallClient {
    suspend fun start(config: ProxyFirewallConfig): ProxyFirewallHandle
    suspend fun replace(handle: ProxyFirewallHandle, config: ProxyFirewallConfig)
    suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport
    suspend fun stop(handle: ProxyFirewallHandle): CleanupReport

    /**
     * R3/R4/R7: perform idempotent proxy-chain sanitation and atomically reset
     * the daemon's generation ledger.
     *
     * @return [SanitationResult] carrying the daemon-acknowledged session ID,
     *   epoch and authoritative daemon generation. Returns `null` on failure;
     *   the controller treats null as a fatal startup gate that prevents any new
     *   runtime from being started.
     *
     * The daemon atomically: (1) installs deny-all, (2) resets its ledger to
     * reject any command from prior sessions or epochs, and (3) returns the new
     * identity. Only a successful non-null return proves containment.
     *
     * The [SanitationResult.sessionId] and [SanitationResult.daemonGeneration]
     * must come from the same acknowledgement. The controller must reject a
     * result whose daemon generation differs from the currently observed daemon
     * generation instead of committing two independent clocks.
     */
    suspend fun cleanOrDenyBeforeRestart(): SanitationResult?
}

fun Boolean.asCleanupReport() =
    if (this) CleanupReport.empty()
    else CleanupReport.failure("operation", RuntimeException("returned false"))

// ---------------------------------------------------------------------------
// CleanupAccumulator
//
// R3 fix #2: uses withTimeoutOrNull so only the step's own deadline is absorbed.
// R4 fix #1: catches ordinary exceptions so independent steps still execute.
//
// Failure taxonomy:
//   • Step timeout      → record failure, return false, continue
//   • Ordinary exception → record failure, return false, continue
//   • CancellationException → rethrow immediately (outer scope cancelled)
// ---------------------------------------------------------------------------

class CleanupAccumulator(private val reporter: ProxyErrorReporter? = null) {

    val failures = mutableListOf<CleanupFailure>()

    /**
     * Run [block] under a per-step deadline.
     *
     * Returns true iff the step completed within its timeout and produced zero
     * [CleanupFailure]s. All other outcomes — timeout, ordinary exception, or
     * non-empty failure list — return false after recording the failure. This
     * ensures that every independent cleanup step is attempted regardless of
     * whether a previous step threw.
     *
     * A parent [CancellationException] is never caught and always propagates.
     */
    suspend fun stepSucceeded(name: String, block: suspend () -> CleanupReport): Boolean {
        return try {
            val r = withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { block() }
            if (r == null) {
                val f = CleanupFailure(
                    name,
                    RuntimeException("step '$name' timed out after ${CLEANUP_STEP_TIMEOUT_MS}ms"),
                )
                failures += f
                safeReport("proxy.cleanup.$name.timeout", f.cause)
                false
            } else {
                failures += r.failures
                r.failures.isEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled   // outer scope cancellation — never absorb
        } catch (t: Throwable) {
            // Ordinary (non-cancellation) exception: record and continue.
            val f = CleanupFailure(name, t)
            failures += f
            safeReport("proxy.cleanup.$name", t)
            false
        }
    }

    private fun safeReport(category: String, failure: Throwable) {
        try { reporter?.report(category, failure, emptyList()) } catch (_: Throwable) { }
    }

    fun report(): CleanupReport = CleanupReport(failures = failures.toList())
}

// ---------------------------------------------------------------------------
// ProxyErrorReporter / ProxyStateSink
// ---------------------------------------------------------------------------

interface ProxyErrorReporter {
    fun report(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    )
}

interface ProxyStateSink {
    suspend fun publish(state: ProxyOnlyState)
}

// ---------------------------------------------------------------------------
// ProxyService skeleton
// ---------------------------------------------------------------------------

abstract class ProxyService : Service() {

    protected abstract val backend: ProxyBackend
    protected abstract val notificationId: Int

    private var backendHandle: ProxyBackendHandle? = null
    private var featureActive = false

    suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation {
        check(!featureActive)
        validateForegroundGrant(grant)
        startForeground(notificationId, waitingNotification(initialState))
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
        val report = backend.stop(current).withContext("ProxyService.stopBackend")
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }

    suspend fun emergencyCloseListener(reason: String): CleanupOutcome {
        if (!featureActive) return CleanupOutcome(CleanupReport.noOp("service inactive"), null)
        val current = backendHandle
            ?: return CleanupOutcome(CleanupReport.noOp("backend absent"), null)
        val report = backend.emergencyCloseListener(current, reason)
            .withContext("ProxyService.emergencyCloseListener")
        val debt = if (report.hasCriticalFailure) {
            CleanupDebt(
                listenerClosePending = true,
                serviceHandlePending = ProxyServiceHandle(current.id),
                firewallHandlePending = null,
                firewallDenyPending = false,
                firewallStopPending = false,
                daemonCleanPending = false,
                featureStopPending = false,
                serviceWasActivated = true,
                failures = report.failures,
                generation = 0L,
                attempt = 0,
            )
        } else null
        // backendHandle intentionally NOT cleared — listener ≠ full backend stop.
        return CleanupOutcome(report, debt)
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
        val report = backend.stop(current).withContext("ProxyService.closeCurrentBackend", reason)
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }

    protected abstract fun validateForegroundGrant(grant: ActivationGrant)
    protected abstract fun waitingNotification(state: ProxyOnlyState): android.app.Notification
    protected abstract fun updateNotification(notification: android.app.Notification)
}
