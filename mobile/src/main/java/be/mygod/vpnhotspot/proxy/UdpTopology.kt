package be.mygod.vpnhotspot.proxy

import java.net.InetSocketAddress

// ---------------------------------------------------------------------------
// Step 8 — UDP topology observations (Phase 0 evidence)
// Sketch: docs/proxy-only/sketches/08-udp-topology.md
//
// Evidence collection for Hev's UDP socket topology.
// The firewall return-path rule remains UNDEFINED until this report is
// correlated with packet capture and conntrack evidence (round-2 review §1).
//
// Consumed by: Step 9 (VerifiedUdpReturnPolicy field in proto).
// ---------------------------------------------------------------------------

/**
 * Role a UDP socket plays in the SOCKS5 relay path.
 *
 * SHARED_RELAY_AND_INTERNET means Hev uses a single socket for both the
 * client-facing relay and the Internet-facing relay — this affects the
 * firewall return-path rule (no separate policy needed for the Internet FD).
 */
enum class UdpSocketRole {
    CLIENT_RELAY,
    INTERNET_FACING,
    SHARED_RELAY_AND_INTERNET,
}

/**
 * One observation of a UDP socket created during an association.
 *
 * [boundNetworkHandle] is null if the socket was not bound to a specific
 * Android network (indicates a potential routing bypass — Phase 0 must flag
 * this as a security finding).
 */
data class UdpSocketObservation(
    val associationId: Long,
    val fd: Int,
    val role: UdpSocketRole,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress?,
    val boundNetworkHandle: Long?,
)

/**
 * Phase 0 UDP topology evidence report.
 *
 * This type is machine-readable and is written by the instrumented Hev fork
 * during Phase 0 testing. It must be correlated with packet-capture and
 * conntrack evidence before a [VerifiedUdpReturnPolicy] can be constructed
 * and placed in the firewall proto (Step 9 field 9).
 *
 * The firewall return rule remains absent until this report plus external
 * evidence proves the exact socket topology.
 */
data class UdpTopologyReport(
    /** All socket observations across all associations during the test run. */
    val observations: List<UdpSocketObservation>,

    /**
     * BND.ADDR / BND.PORT values returned to SOCKS5 clients. Correlate with
     * [observations] to identify which socket backs each binding.
     */
    val returnedBindAddresses: List<InetSocketAddress>,

    /**
     * Network interfaces on which reply packets were observed arriving.
     * Expected to be the VPN TUN interface only; any physical interface name
     * here is a security finding.
     */
    val replyIngressInterfaces: Set<String>,

    /**
     * conntrack state strings observed for reply flows (e.g. "ESTABLISHED",
     * "RELATED"). Used to decide whether an ESTABLISHED-only return rule is
     * sufficient or whether RELATED must also be allowed.
     */
    val replyConntrackStates: Set<String>,
)

/**
 * Placeholder for the firewall return-path policy derived from Phase 0 evidence.
 * Populated only after [UdpTopologyReport] + packet capture + conntrack analysis
 * confirm the exact semantics. Never instantiated before Phase 0 passes.
 */
data class VerifiedUdpReturnPolicy(
    /** Human-readable summary of the evidence that justifies this policy. */
    val evidenceSummary: String,
    /** Whether to allow RELATED conntrack state for UDP return flows. */
    val allowRelated: Boolean,
)
