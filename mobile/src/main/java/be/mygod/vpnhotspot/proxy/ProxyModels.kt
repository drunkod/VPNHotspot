package be.mygod.vpnhotspot.proxy

import android.net.Network
import java.util.UUID

// ---------------------------------------------------------------------------
// Step 1 — Settings, state, activation and desired-state models
// Sketch: docs/proxy-only/sketches/01-models-and-state.md
// Fix #10: credentials isolated in ProxyCredentials with redacted toString
// ---------------------------------------------------------------------------

enum class SharingMode { VPN_ROUTING, PROXY_ONLY }

/**
 * Credential pair with a redacted [toString] so username/password never
 * appear in logs, crash breadcrumbs or state dumps.
 */
data class ProxyCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ProxyCredentials(username=<redacted>)"
}

data class ProxyOnlySettings(
    val enabled: Boolean,
    val tcpPort: Int,
    val udpEnabled: Boolean,
    val udpPortRange: IntRange,
    val maxUdpAssociations: Int,
    val credentialsVersion: Long,
    // Credentials are NOT included here; passed separately at backend start.
)

enum class ActivationSource {
    USER_ENABLE,
    USER_RESUME,
}

data class ActivationGrant(
    val id: UUID,
    val issuedAtElapsedRealtime: Long,
    val source: ActivationSource,
)

interface ActivationGrantConsumer {
    suspend fun consume(grantId: UUID)
}

data class ProxyVpnUpstream(
    val network: Network,
    val handle: Long,
    val interfaces: Set<String>,
)

data class RuntimeKey(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val credentialsVersion: Long,
    val vpnNetworkHandle: Long,
    /** Sorted list of (interfaceName, sortedIpv4Addresses) pairs for stable equality. */
    val downstreams: List<Pair<String, List<String>>>,
    val backendVersion: Int,
)

sealed interface FailClosedReason {
    data object RootDaemonUnavailable : FailClosedReason
    data class BindProbeFailed(val detail: String) : FailClosedReason
    data class TcpProbeFailed(val detail: String) : FailClosedReason
    data class UdpProbeFailed(val detail: String) : FailClosedReason
    data class DnsProbeFailed(val detail: String) : FailClosedReason
    data class ListenerNotReady(val detail: String) : FailClosedReason
    data class ProbeReportIncomplete(val missing: Set<ProbeKind>) : FailClosedReason
    data class StartupSanitationFailed(val detail: String) : FailClosedReason
    data class InternalFailure(val category: String) : FailClosedReason
}

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object ActivationRequired : ProxyOnlyState
    data object ServiceStarting : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data class MultipleVpnCandidates(val count: Int) : ProxyOnlyState
    data object VpnPermissionDenied : ProxyOnlyState
    data object StartingBackend : ProxyOnlyState
    data class Running(val endpoint: ProxyEndpoint) : ProxyOnlyState
    data class FailClosed(val reason: FailClosedReason) : ProxyOnlyState
    data class CleanupDegraded(
        val unresolved: Set<CleanupResource>,
        val failures: List<CleanupFailure>,
        val retryAttempt: Int,
    ) : ProxyOnlyState
}

// ---------------------------------------------------------------------------
// 1.2 Desired state and controller events
// ---------------------------------------------------------------------------

data class DesiredProxyState(
    val settings: ProxyOnlySettings,
    val activationGrant: ActivationGrant?,
    val vpnSelection: VpnSelection,
    val downstreams: List<ManagedDownstream>,
    val allowedClients: List<AllowedClient>,
    val daemonHealthy: Boolean,
    val daemonGeneration: Long?,
) {
    /**
     * Normalise to prevent irrelevant churn from creating new runtime keys.
     * Fix #12: canonicalize downstreams, client IPv4 lists and client order.
     */
    fun normalized(): DesiredProxyState = copy(
        downstreams = downstreams
            .map { it.copy(ipv4Address = it.ipv4Address) }
            .sortedBy { it.interfaceName },
        allowedClients = allowedClients
            .map { it.copy(ipv4Addresses = it.ipv4Addresses.sorted()) }
            .sortedBy { it.mac },
    )
}

sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}

// ---------------------------------------------------------------------------
// Placeholder stubs for types referenced above but defined in other files
// ---------------------------------------------------------------------------

/** Downstream tethering interface with optional IPv4 address. */
data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Address: String?,
)

/** Client identified by MAC with optional IPv4 bindings. */
data class AllowedClient(
    val mac: String,
    val ipv4Addresses: List<String> = emptyList(),
)

/** Proxy endpoint advertised to clients. */
data class ProxyEndpoint(
    val host: String,
    val tcpPort: Int,
    val udpPortRange: IntRange?,
)
