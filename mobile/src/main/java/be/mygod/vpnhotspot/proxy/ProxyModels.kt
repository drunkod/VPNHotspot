package be.mygod.vpnhotspot.proxy

import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

// ---------------------------------------------------------------------------
// Step 1 — Settings, state, activation and desired-state models
// Sketch: docs/proxy-only/sketches/01-models-and-state.md
// ---------------------------------------------------------------------------

enum class SharingMode { VPN_ROUTING, PROXY_ONLY }

/**
 * Secret container — NOT a data class.
 * No generated copy/component methods, no automatic equality over secrets,
 * always-redacted toString.
 */
class ProxyCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ProxyCredentials(username=<redacted>)"
}

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
    // Username/password are NOT stored here; fetched via ProxyCredentialProvider.
)

enum class ActivationSource { USER_ENABLE, USER_RESUME }

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
    /** Sorted list of (interfaceName, sortedCanonicalIpv4) for stable equality. */
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
    /** Published when all downstreams lack a routable, non-loopback IPv4 address. */
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
    /** One endpoint per downstream; never empty. */
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
     * Normalise to a canonical form that prevents irrelevant churn from
     * creating new runtime keys.
     *
     * R3 fix #4: merge-based deduplication instead of distinctBy.
     *   - Downstreams with the same interfaceName are merged; the first routable
     *     IPv4 across all observations is used (so a null-address observation
     *     cannot discard a later valid one).
     *   - Clients with the same canonical MAC are merged; IPv4 bindings are
     *     unioned, validated and sorted.
     *   - Malformed, loopback and unspecified addresses are rejected.
     */
    fun normalized(): DesiredProxyState = copy(
        downstreams = downstreams
            .groupBy { it.interfaceName.trim() }
            .map { (iface, group) ->
                ManagedDownstream(
                    interfaceName = iface,
                    // Take the first routable address found across all observations.
                    ipv4Address = group.mapNotNull { it.ipv4Address }
                        .firstOrNull { isRoutableIpv4(it) },
                )
            }
            .sortedBy { it.interfaceName },
        allowedClients = allowedClients
            .groupBy { canonicalizeMac(it.mac) }
            .map { (canonicalMac, group) ->
                AllowedClient(
                    mac = canonicalMac,
                    ipv4Addresses = group.flatMap { it.ipv4Addresses }
                        .filter { isRoutableIpv4(it) }
                        .map { normalizeIpv4(it) }
                        .distinct()
                        .sorted(),
                )
            }
            .sortedBy { it.mac },
    )
}

// ---------------------------------------------------------------------------
// Address validation helpers
// R3 fix #4: reject loopback, unspecified and malformed addresses.
// ---------------------------------------------------------------------------

/**
 * Returns true if [address] is a parseable, non-loopback, non-unspecified
 * unicast IPv4 address that a tethered client could plausibly reach.
 */
fun isRoutableIpv4(address: String): Boolean {
    return try {
        val inet = InetAddress.getByName(address.trim())
        inet is Inet4Address &&
            !inet.isLoopbackAddress &&
            !inet.isAnyLocalAddress &&
            !inet.isMulticastAddress &&
            !inet.isLinkLocalAddress
    } catch (_: Exception) {
        false
    }
}

/** Returns the canonical dotted-decimal form of a parseable IPv4 address. */
fun normalizeIpv4(address: String): String {
    return try {
        InetAddress.getByName(address.trim()).hostAddress ?: address.trim()
    } catch (_: Exception) {
        address.trim()
    }
}

/**
 * Returns an uppercase colon-separated MAC string for canonical comparison.
 * Accepts colon-, dash- and plain-hex formats.
 */
fun canonicalizeMac(mac: String): String {
    val hex = mac.trim().replace("[-:]".toRegex(), "").uppercase()
    return if (hex.length == 12) {
        hex.chunked(2).joinToString(":")
    } else {
        mac.trim().uppercase() // fall back to trimmed upper for unknown format
    }
}

// ---------------------------------------------------------------------------
// Stubs
// ---------------------------------------------------------------------------

sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}

data class ManagedDownstream(
    val interfaceName: String,
    val ipv4Address: String?,
)

data class AllowedClient(
    val mac: String,
    val ipv4Addresses: List<String> = emptyList(),
)

/** One endpoint per tethering downstream; host is a validated routable IPv4. */
data class ProxyEndpoint(
    val downstreamInterface: String,
    val host: String,
    val tcpPort: Int,
    val udpPortRange: IntRange?,
)
