package be.mygod.vpnhotspot.proxy

import android.app.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
// ---------------------------------------------------------------------------

internal const val CLEANUP_STEP_TIMEOUT_MS = 10_000L

// ---------------------------------------------------------------------------
// Handle types
// ---------------------------------------------------------------------------

@JvmInline value class ProxyServiceHandle(val id: Long)
@JvmInline value class ProxyBackendHandle(val id: Long)
@JvmInline value class ProxyFirewallHandle(val id: Long)

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
// R3 fix #3: cleanOrDenyBeforeRestart returns Long? (daemon-issued epoch).
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
     * R3 fix #3: perform idempotent proxy-chain sanitation and atomically reset
     * the daemon's generation ledger.
     *
     * @return The daemon-acknowledged new epoch (starting sequence value) to use
     *         as the base for subsequent firewall-config generation numbers, or
     *         `null` if sanitation failed.
     *
     * The controller must use this epoch to initialize its generation counter.
     * Deriving the counter from a locally shifted value without an acknowledgement
     * can reuse sequences already accepted by a surviving daemon.
     */
    suspend fun cleanOrDenyBeforeRestart(): Long?
}

fun Boolean.asCleanupReport() =
    if (this) CleanupReport.empty()
    else CleanupReport.failure("operation", RuntimeException("returned false"))

// ---------------------------------------------------------------------------
// CleanupAccumulator
//
// R3 fix #2: uses withTimeoutOrNull so only the step's own deadline is absorbed.
// Parent CancellationException propagates naturally — no explicit catch needed.
// ---------------------------------------------------------------------------

class CleanupAccumulator(private val reporter: ProxyErrorReporter? = null) {

    val failures = mutableListOf<CleanupFailure>()

    /**
     * Run [block] under a per-step deadline.
     *
     * [withTimeoutOrNull] returns `null` only when the block itself exceeds its
     * own timeout. A parent or outer [CancellationException] is not caught here
     * and propagates to the caller. This correctly distinguishes inner step
     * timeouts from outer transaction timeouts.
     */
    suspend fun stepSucceeded(name: String, block: suspend () -> CleanupReport): Boolean {
        val r = withTimeoutOrNull(CLEANUP_STEP_TIMEOUT_MS) { block() }
        if (r == null) {
            // Only this step's own deadline elapsed.
            val f = CleanupFailure(name, RuntimeException("step '$name' timed out after ${CLEANUP_STEP_TIMEOUT_MS}ms"))
            failures += f
            safeReport("proxy.cleanup.$name.timeout", f.cause)
            return false
        }
        failures += r.failures
        return r.failures.isEmpty()
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
