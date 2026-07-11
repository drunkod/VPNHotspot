package be.mygod.vpnhotspot.proxy

import android.net.Network
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

/**
 * Stable identity of one running proxy backend.
 *
 * R4 fix #3: [daemonGeneration] is included so that a daemon restart always
 * produces a different key even when all other fields are unchanged. This forces
 * the controller to destroy any existing firewall runtime that was issued by the
 * previous daemon generation instead of calling replace() on a stale handle.
 */
data class RuntimeKey(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val credentialsVersion: Long,
    val vpnNetworkHandle: Long,
    /** Sorted list of (interfaceName, sortedCanonicalIpv4) for stable equality. */
    val downstreams: List<Pair<String, List<String>>>,
    val backendVersion: Int,
    /** Daemon restart epoch; changes on every new daemon generation. */
    val daemonGeneration: Long,
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
    /**
     * R4 fix #9: published when tethering interfaces are present but none has a
     * reachable, non-loopback, non-unspecified unicast IPv4 address.
     * Distinct from WaitingForTethering (no interfaces at all).
     */
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
     * R3/R4 normalization rules:
     *   Downstreams
     *     - Grouped by trimmed interface name.
     *     - First strictly-routable IPv4 literal across the group is kept.
     *     - Malformed, loopback, unspecified, multicast and link-local addresses
     *       are rejected without DNS (strict literal parser).
     *     - ipv4Address is normalised to dotted-decimal if accepted.
     *     - ManagedDownstream.ipv4Address is a single nullable field: the
     *       production adapter must ensure at most one routable IPv4 per
     *       interface name (see kdoc on ManagedDownstream).
     *   Clients
     *     - MAC canonicalized from colon-, dash- or plain-hex by strict byte
     *       parser; multicast-bit and broadcast MACs are rejected (null return).
     *     - Invalid MAC → entire client record is dropped.
     *     - IPv4 addresses filtered with the same strict literal parser.
     *     - Clients with no remaining valid IPv4 binding are dropped (R4 #7).
     *     - Duplicate MACs are merged; addresses are unioned, deduplicated, sorted.
     */
    fun normalized(): DesiredProxyState = copy(
        downstreams = downstreams
            .filter { isValidInterfaceName(it.interfaceName.trim()) }
            .groupBy { it.interfaceName.trim() }
            .map { (iface, group) ->
                ManagedDownstream(
                    interfaceName = iface,
                    ipv4Address = group.mapNotNull { it.ipv4Address }
                        .firstOrNull { isRoutableIpv4(it) }
                        ?.let { normalizeIpv4(it) },
                )
            }
            .sortedBy { it.interfaceName },
        allowedClients = allowedClients
            .mapNotNull { client ->
                val mac = canonicalizeMac(client.mac) ?: return@mapNotNull null
                val ips = client.ipv4Addresses
                    .filter { isRoutableIpv4(it) }
                    .map { normalizeIpv4(it) }
                    .distinct()
                    .sorted()
                // R4 fix #7: drop clients with no valid IPv4 binding.
                // An empty list is NOT a wildcard; drop to avoid ambiguous ACL semantics.
                if (ips.isEmpty()) return@mapNotNull null
                AllowedClient(mac = mac, ipv4Addresses = ips)
            }
            .groupBy { it.mac }
            .map { (mac, group) ->
                AllowedClient(
                    mac = mac,
                    ipv4Addresses = group.flatMap { it.ipv4Addresses }.distinct().sorted(),
                )
            }
            .sortedBy { it.mac },
    )
}

// ---------------------------------------------------------------------------
// Address validation helpers
//
// R4 fix #5: strict IPv4 literal parser — no DNS, no hostname resolution.
// InetAddress.getByName() is deliberately NOT used; it may perform DNS on the
// calling thread and produce nondeterministic, blocking normalisation.
// ---------------------------------------------------------------------------

/** Four decimal octets, each in 0..255; nothing else. */
private val IPV4_RE = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/**
 * Parse [address] as a dotted-decimal IPv4 literal.
 * Returns a 4-element IntArray [o0, o1, o2, o3] on success, null on any parse
 * or range error. Never performs DNS.
 */
private fun parseIpv4Literal(address: String): IntArray? {
    val m = IPV4_RE.matchEntire(address.trim()) ?: return null
    val octets = IntArray(4)
    for (i in 0..3) {
        val v = m.groupValues[i + 1].toIntOrNull() ?: return null
        if (v > 255) return null
        octets[i] = v
    }
    return octets
}

/**
 * Returns true if [address] is a strict IPv4 literal that a tethered client
 * could plausibly reach: not loopback, unspecified, multicast, reserved,
 * link-local or broadcast. No DNS is performed.
 */
fun isRoutableIpv4(address: String): Boolean {
    val o = parseIpv4Literal(address) ?: return false
    if (o[0] == 0) return false                    // 0.0.0.0/8 — unspecified
    if (o[0] == 127) return false                  // 127.0.0.0/8 — loopback
    if (o[0] >= 224) return false                  // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved
    if (o[0] == 169 && o[1] == 254) return false  // 169.254.0.0/16 — link-local
    if (o[0] == 255 && o[1] == 255 && o[2] == 255 && o[3] == 255) return false // broadcast
    return true
}

/**
 * Returns the canonical dotted-decimal form of a parseable IPv4 literal.
 * Leading zeros are normalised (e.g. "010.001.002.003" → "10.1.2.3").
 * Returns the original trimmed string unchanged if parsing fails.
 * Never performs DNS.
 */
fun normalizeIpv4(address: String): String {
    val o = parseIpv4Literal(address) ?: return address.trim()
    return "${o[0]}.${o[1]}.${o[2]}.${o[3]}"
}

// ---------------------------------------------------------------------------
// MAC validation helper
//
// R4 fix #6: strict 6-byte parser; multicast-bit and broadcast MACs are
// rejected; malformed input returns null so the entire client record is dropped
// rather than retained as an uppercase fallback with undefined semantics.
// ---------------------------------------------------------------------------

private val HEX_UPPER = "0123456789ABCDEF"

/**
 * Parse and canonicalize a MAC address from colon-separated (AA:BB:CC:DD:EE:FF),
 * dash-separated (AA-BB-...) or plain-hex (AABBCCDDEEFF) form.
 *
 * Returns an uppercase colon-separated 6-byte string on success, or null when:
 *   - The input cannot be parsed as exactly 6 hex bytes.
 *   - The least-significant bit of the first byte (multicast/broadcast flag) is set.
 *   - All bytes are 0xFF (broadcast).
 *
 * Callers MUST drop the entire client record on null return.
 */
fun canonicalizeMac(mac: String): String? {
    val hex = mac.trim().replace("[-:]".toRegex(), "").uppercase()
    if (hex.length != 12) return null
    if (hex.any { it !in HEX_UPPER }) return null
    val bytes = (0 until 6).map { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16)
    }
    // Reject multicast (LSB of first byte set — covers broadcast-as-group-address).
    if (bytes[0] and 0x01 != 0) return null
    // Reject broadcast FF:FF:FF:FF:FF:FF explicitly.
    if (bytes.all { it == 0xFF }) return null
    // R5 fix #6: reject all-zero MAC (00:00:00:00:00:00) — not a valid unicast source.
    if (bytes.all { it == 0x00 }) return null
    return bytes.joinToString(":") { "%02X".format(it) }
}

// ---------------------------------------------------------------------------
// Interface name validation
//
// R5 fix #6: reject empty or oversized interface names before they reach
// firewall configuration. Linux IFNAMSIZ is 16 (including NUL terminator),
// so valid names are 1–15 characters.
// ---------------------------------------------------------------------------

/**
 * Returns true if [name] is a plausible Linux network interface name:
 * non-empty, at most 15 characters, and composed only of printable ASCII
 * that does not include whitespace or shell-sensitive characters.
 *
 * This is a conservative allow-list; production adapters should validate
 * against the actual system interface list before constructing DesiredProxyState.
 */
fun isValidInterfaceName(name: String): Boolean {
    if (name.isEmpty() || name.length > 15) return false
    // R6 fix #7: explicit ASCII ranges — isLetterOrDigit() accepts Unicode which
    // violates the byte-based Linux IFNAMSIZ contract and could allow names that
    // pass the 15-char check while exceeding the byte limit.
    return name.all { c ->
        (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c in "_-.:@"
    }
}

// ---------------------------------------------------------------------------
// Controller events
// ---------------------------------------------------------------------------

sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    // R4: RetryCleanupDebt removed — cleanup retries are executed directly in
    // cleanupScope under stateMutex, not via the events channel which has no
    // consumer after the worker exits.
}

// ---------------------------------------------------------------------------
// Supporting domain types
// ---------------------------------------------------------------------------

/**
 * One tethering downstream interface.
 *
 * [ipv4Address] is a single nullable field by deliberate Phase-0 design.
 * At the IP routing layer, a tethering interface has exactly one active
 * DHCP-server IPv4 address at any moment. The production DesiredProxyState
 * adapter must enforce this one-address invariant; [DesiredProxyState.normalized]
 * selects the first routable address across duplicate observations of the same
 * interface and discards the rest without data loss at the routing level.
 */
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
