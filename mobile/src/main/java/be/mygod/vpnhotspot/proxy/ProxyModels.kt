package be.mygod.vpnhotspot.proxy

import android.net.Network
import java.util.UUID

// ---------------------------------------------------------------------------
// Step 1 — Settings, state, activation and desired-state models
// Sketch: docs/proxy-only/sketches/01-models-and-state.md
// R1 fix #10 / R2 fix #8: ProxyCredentials is NOT a data class — no generated
//   copy/component methods, no plaintext equality, redacted toString.
// ---------------------------------------------------------------------------

enum class SharingMode { VPN_ROUTING, PROXY_ONLY }

/**
 * Secret container for SOCKS5 credentials.
 *
 * Intentionally NOT a data class:
 *   - no generated copy(), component1(), component2()
 *   - no automatic equality/hashCode over secret values
 *   - toString() is always redacted
 *
 * Credentials must be fetched immediately before backend start from a
 * [ProxyCredentialProvider]; they must not live in long-lived state objects.
 */
class ProxyCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ProxyCredentials(username=<redacted>)"
    // Equality is identity — two separate fetches are not considered equal.
}

/**
 * Fetches credentials at backend-start time. The controller holds only this
 * provider reference, not the credentials themselves.
 */
interface ProxyCredentialProvider {
    /** Returns current credentials, or throws if unavailable. */
    fun credentials(): ProxyCredentials
}

data class ProxyOnlySettings(
    val enabled: Boolean,
    val tcpPort: Int,
    val udpEnabled: Boolean,
    val udpPortRange: IntRange,
    val maxUdpAssociations: Int,
    val credentialsVersion: Long,
    // Username and password are NOT stored here; fetched via ProxyCredentialProvider.
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
    /** Sorted list of (interfaceName, sortedIpv4Addresses) for stable equality. */
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
    /** R2 fix #6: published only when all downstreams lack a routable IPv4 address. */
    data object NoReachableDownstreamAddress : FailClosedReason
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
    data class Running(val endpoints: List<ProxyEndpoint>) : ProxyOnlyState
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
     * R1 fix #12 / R2 fix #9: sort, deduplicate and validate entries.
     */
    fun normalized(): DesiredProxyState = copy(
        downstreams = downstreams
            .sortedBy { it.interfaceName }
            .distinctBy { it.interfaceName },
        allowedClients = allowedClients
            .map { client ->
                client.copy(
                    ipv4Addresses = client.ipv4Addresses.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted()
                )
            }
            .sortedBy { it.mac.uppercase() }
            .distinctBy { it.mac.uppercase() },
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

/**
 * Proxy endpoint advertised to a specific tethering downstream.
 * R2 fix #6: one endpoint per downstream; never published as loopback.
 */
data class ProxyEndpoint(
    val downstreamInterface: String,
    val host: String,
    val tcpPort: Int,
    val udpPortRange: IntRange?,
)
