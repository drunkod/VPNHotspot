package be.mygod.vpnhotspot.proxy

import android.app.Service
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

// ---------------------------------------------------------------------------
// Step 3 — Service/backend ownership and the persistent foreground service
// Sketch: docs/proxy-only/sketches/03-service-and-backend-ownership.md
//
// Fixes applied:
//   #3  emergencyCloseListener returns CleanupOutcome (not just CleanupReport)
//       so the controller can merge real listener/handle debt on failure.
//   #5  CleanupAccumulator.stepSucceeded applies CLEANUP_STEP_TIMEOUT per step.
//   #7  Reporter calls inside the accumulator are non-throwing.
//   #10 ProxyBackendConfig takes ProxyCredentials, not raw strings.
// ---------------------------------------------------------------------------

internal const val CLEANUP_STEP_TIMEOUT_MS = 10_000L

// ---------------------------------------------------------------------------
// Handle types
// ---------------------------------------------------------------------------

@JvmInline value class ProxyServiceHandle(val id: Long)
@JvmInline value class ProxyBackendHandle(val id: Long)
@JvmInline value class ProxyFirewallHandle(val id: Long)

// ---------------------------------------------------------------------------
// Report / accumulator types
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
         * R2 fix #5: a mismatched/stale handle is a CRITICAL unresolved failure, not
         * a context-only success. Debt must not be cleared while another backend lives.
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
// Service activation result
// ---------------------------------------------------------------------------

sealed interface ServiceActivation {
    data object Active : ServiceActivation
    data object ForegroundStartNotAllowed : ServiceActivation
}

// ---------------------------------------------------------------------------
// Backend configuration
// Fix #10: credentials travel as ProxyCredentials, not raw strings
// ---------------------------------------------------------------------------

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
// Fix #3: emergencyCloseListener returns CleanupOutcome so controller can
//         merge real debt when the emergency close itself fails.
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
     * Returns a [CleanupOutcome] so the controller can merge debt when this
     * operation itself fails (fix #3). The service handle is NEVER cleared
     * by emergency close alone — only a successful [stopBackend] removes it.
     */
    suspend fun emergencyCloseListener(reason: String): CleanupOutcome
    suspend fun stopFeature(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}

// ---------------------------------------------------------------------------
// ProxyBackend interface (owned exclusively by ProxyService)
// ---------------------------------------------------------------------------

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyBackendHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun emergencyCloseListener(
        handle: ProxyBackendHandle,
        reason: String,
    ): CleanupReport
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}

// ---------------------------------------------------------------------------
// ProxyFirewallClient interface
// Fix #2: denyAll/stop return Boolean → CleanupReport to make failures visible
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
    /** Returns [CleanupReport] so a failed deny creates FIREWALL_DENY debt. */
    suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport
    /** Returns [CleanupReport] so a failed stop retains FIREWALL_RUNTIME debt. */
    suspend fun stop(handle: ProxyFirewallHandle): CleanupReport
    suspend fun cleanOrDenyBeforeRestart(): Boolean
}

// ---------------------------------------------------------------------------
// CleanupAccumulator
// Fix #5: applies CLEANUP_STEP_TIMEOUT_MS per step via withTimeout.
// Fix #7: reporter calls are non-throwing (catch-all inside report()).
// ---------------------------------------------------------------------------

class CleanupAccumulator(private val reporter: ProxyErrorReporter? = null) {

    val failures = mutableListOf<CleanupFailure>()

    /**
     * Run [block] with a per-step timeout.
     *
     * R2 fix #3: catch order is TimeoutCancellationException → CancellationException (re-throw)
     * → Throwable. A parent coroutine cancellation must propagate; only a step-level timeout
     * is classified as a cleanup failure.
     */
    suspend fun stepSucceeded(name: String, block: suspend () -> CleanupReport): Boolean {
        return try {
            val r = withTimeout(CLEANUP_STEP_TIMEOUT_MS) { block() }
            failures += r.failures
            r.failures.isEmpty()
        } catch (stepTimeout: TimeoutCancellationException) {
            // Step-level timeout: record failure, keep accumulating other steps.
            failures += CleanupFailure(name, stepTimeout)
            safeReport("proxy.cleanup.$name", stepTimeout)
            false
        } catch (cancelled: CancellationException) {
            // R2 fix #3: parent cancellation must not be swallowed.
            throw cancelled
        } catch (t: Throwable) {
            failures += CleanupFailure(name, t)
            safeReport("proxy.cleanup.$name", t)
            false
        }
    }

    /** Fix #7: reporter must not throw into the cleanup path. */
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
// ProxyService skeleton (sole backend owner)
// Fix #3: emergencyCloseListener returns CleanupOutcome with handle info.
// Fix #4: stopFeature cannot mark service inactive while backend debt exists.
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
        // Never discard the only native handle before stop is proven complete.
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }

    /**
     * Fix #3: returns [CleanupOutcome] so the controller knows whether listener
     * containment succeeded. The backend handle is NEVER cleared here — only a
     * successful [stopBackend] removes it.
     */
    suspend fun emergencyCloseListener(reason: String): CleanupOutcome {
        if (!featureActive) return CleanupOutcome(CleanupReport.noOp("service inactive"), null)
        val current = backendHandle
            ?: return CleanupOutcome(CleanupReport.noOp("backend absent"), null)

        val report = backend.emergencyCloseListener(current, reason)
            .withContext("ProxyService.emergencyCloseListener")

        // Listener containment failure → return listener debt with the retained handle.
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
                generation = 0L, // controller assigns generation after merge
                attempt = 0,
            )
        } else null

        // backendHandle is intentionally NOT cleared (listener ≠ full backend stop).
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
        val report = backend.stop(current)
            .withContext("ProxyService.closeCurrentBackend", reason)
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }

    protected abstract fun validateForegroundGrant(grant: ActivationGrant)
    protected abstract fun waitingNotification(state: ProxyOnlyState): android.app.Notification
    protected abstract fun updateNotification(notification: android.app.Notification)
}
