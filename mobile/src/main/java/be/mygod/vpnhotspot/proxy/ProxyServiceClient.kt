package be.mygod.vpnhotspot.proxy

import android.app.Service

// ---------------------------------------------------------------------------
// Step 3 — Service/backend ownership and the persistent foreground service
// Sketch: docs/proxy-only/sketches/03-service-and-backend-ownership.md
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Handle types
// ---------------------------------------------------------------------------

/** Opaque handle issued by ProxyService when a backend is started. */
@JvmInline
value class ProxyServiceHandle(val id: Long)

/** Opaque handle issued by ProxyBackend when it starts. */
@JvmInline
value class ProxyBackendHandle(val id: Long)

/** Opaque handle issued by the firewall client. */
@JvmInline
value class ProxyFirewallHandle(val id: Long)

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
        fun staleHandle(id: Long) = CleanupReport(context = "stale handle $id")
    }
}

/** Debt produced by an emergency CleanupReport when the service is unavailable. */
val CleanupReport.debt: CleanupDebt? get() = null // resolved by the controller

// ---------------------------------------------------------------------------
// Service activation result
// ---------------------------------------------------------------------------

sealed interface ServiceActivation {
    data object Active : ServiceActivation
    data object ForegroundStartNotAllowed : ServiceActivation
}

// ---------------------------------------------------------------------------
// Backend configuration
// ---------------------------------------------------------------------------

data class ProxyBackendConfig(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val maxUdpAssociations: Int,
    val username: String,
    val password: String,
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

/**
 * Client-side view of [ProxyService].
 *
 * Pre-activation: [enterWaiting], [stopBackend], [emergencyCloseListener] and
 * [stopFeature] return no-op [CleanupReport]s without throwing.
 *
 * [ProxyOnlyController] never owns a backend/native handle; only [ProxyService] does.
 */
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
    suspend fun emergencyCloseListener(reason: String): CleanupReport
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
    suspend fun denyAll(handle: ProxyFirewallHandle): Boolean
    suspend fun stop(handle: ProxyFirewallHandle): Boolean
    suspend fun cleanOrDenyBeforeRestart(): Boolean
}

fun Boolean.asCleanupReport() =
    if (this) CleanupReport.empty()
    else CleanupReport.failure("operation", RuntimeException("returned false"))

// ---------------------------------------------------------------------------
// ProxyService skeleton
// Sole backend owner; keeps the FGS alive in waiting/fail-closed states.
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

    suspend fun emergencyCloseListener(reason: String): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        // Emergency listener closure is fail-closed containment, not proof that the
        // backend handle and all native resources were destroyed.
        return backend.emergencyCloseListener(current, reason)
            .withContext("ProxyService.emergencyCloseListener")
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

    // Abstract helpers — implemented by the concrete service subclass.
    protected abstract fun validateForegroundGrant(grant: ActivationGrant)
    protected abstract fun waitingNotification(state: ProxyOnlyState): android.app.Notification
    protected abstract fun updateNotification(notification: android.app.Notification)
}
