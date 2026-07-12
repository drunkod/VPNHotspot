package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.DenyRequest
import be.mygod.vpnhotspot.proxy.proto.ProxyClient
import be.mygod.vpnhotspot.proxy.proto.ProxyDownstream
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallConfig as WireProxyFirewallConfig
import be.mygod.vpnhotspot.proxy.proto.ReplaceRequest
import be.mygod.vpnhotspot.proxy.proto.SanitizeRequest
import be.mygod.vpnhotspot.proxy.proto.StartRequest
import be.mygod.vpnhotspot.proxy.proto.StopRequest
import java.io.IOException
import okio.ByteString.Companion.toByteString

/**
 * Transport seam for the existing root-daemon request/reply channel.
 * Implementations wrap [ProxyFirewallCommand] in the daemon ClientEnvelope and
 * return the typed [ProxyFirewallAck] reply payload.
 */
interface ProxyFirewallRpc {
    suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck
}

class StaleProxyFirewallTokenException(
    val status: ProxyFirewallAck.Status,
    val identity: DaemonIdentity?,
    detail: String,
) : IOException(
    buildString {
        append("proxy firewall token rejected: ")
        append(status)
        identity?.let {
            append(" current=")
            append(it.session_id)
            append('/')
            append(it.epoch)
        }
        if (detail.isNotBlank()) {
            append(" (")
            append(detail)
            append(')')
        }
    }
)

/**
 * Track B client mapping. Session IDs, epochs and runtime IDs are populated
 * only from daemon acknowledgements; no local `.copy()` or inferred token is
 * permitted. Every handle mutation sends the exact daemon-issued token.
 *
 * [containmentConfig] is evaluated immediately before sanitation. Its result
 * must describe the current downstream/listener ports with both deny flags set
 * and no allowed clients. This provider is the integration seam for the future
 * service-owned controller composition (Track C); the controller's reviewed
 * no-argument [ProxyFirewallClient.cleanOrDenyBeforeRestart] contract remains
 * unchanged.
 */
class DaemonProxyFirewallClient(
    private val rpc: ProxyFirewallRpc,
    private val containmentConfig: suspend () -> ProxyFirewallConfig,
) : ProxyFirewallClient {
    override suspend fun cleanOrDenyBeforeRestart(): SanitationResult? {
        latestSanitation = null
        val config = containmentConfig()
        require(config.denyAllIpv4 && config.denyAllIpv6) {
            "sanitation requires explicit IPv4 and IPv6 deny containment"
        }
        require(config.allowedClients.isEmpty()) {
            "sanitation containment must not include allowed clients"
        }
        val ack = rpc.execute(
            ProxyFirewallCommand(
                sanitize = SanitizeRequest(
                    reason = "controller sanitation gate",
                    containment_config = config.toProto(),
                ),
            ),
        )
        val identity = ack.identity
        if (ack.status != ProxyFirewallAck.Status.OK || identity == null) return null
        return recordSanitation(
            SanitationResult(
                sessionId = identity.session_id,
                epoch = identity.epoch,
            ),
        )
    }

    override suspend fun start(config: ProxyFirewallConfig): ProxyFirewallHandle {
        val sanitation = requireSanitationToken()
        val ack = rpc.execute(
            ProxyFirewallCommand(
                start = StartRequest(
                    config = config.toProto(),
                    expected_session_id = sanitation.sessionId,
                    expected_epoch = sanitation.epoch,
                ),
            ),
        )
        requireOk("start", ack)
        require(ack.handle_id != 0L) { "daemon returned zero proxy firewall handle" }
        val identity = requireIdentity(ack)
        return ProxyFirewallHandle(
            sessionId = identity.session_id,
            epoch = identity.epoch,
            id = ack.handle_id,
        )
    }

    override suspend fun replace(handle: ProxyFirewallHandle, config: ProxyFirewallConfig) {
        val ack = rpc.execute(
            ProxyFirewallCommand(
                replace = ReplaceRequest(
                    handle_id = handle.id,
                    config = config.toProto(),
                    expected_session_id = handle.sessionId,
                    expected_epoch = handle.epoch,
                ),
            ),
        )
        requireOk("replace", ack)
    }

    override suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport = cleanupAck(
        step = "deny",
        ack = rpc.execute(
            ProxyFirewallCommand(
                deny = DenyRequest(
                    handle_id = handle.id,
                    expected_session_id = handle.sessionId,
                    expected_epoch = handle.epoch,
                ),
            ),
        ),
    )

    override suspend fun stop(handle: ProxyFirewallHandle): CleanupReport = cleanupAck(
        step = "firewall_stop",
        ack = rpc.execute(
            ProxyFirewallCommand(
                stop = StopRequest(
                    handle_id = handle.id,
                    expected_session_id = handle.sessionId,
                    expected_epoch = handle.epoch,
                ),
            ),
        ),
    )

    private var latestSanitation: SanitationResult? = null

    private fun requireSanitationToken(): SanitationResult = latestSanitation
        ?: throw IllegalStateException("proxy firewall start requires successful sanitation")

    private fun cleanupAck(step: String, ack: ProxyFirewallAck): CleanupReport = when (ack.status) {
        ProxyFirewallAck.Status.OK -> CleanupReport.empty()
        ProxyFirewallAck.Status.STALE_SESSION,
        ProxyFirewallAck.Status.STALE_EPOCH -> CleanupReport.failure(
            "${step}_stale",
            staleException(ack),
        )
        ProxyFirewallAck.Status.INVALID -> CleanupReport.failure(
            "${step}_invalid",
            IllegalStateException(ack.detail.ifBlank { "daemon rejected proxy firewall command" }),
        )
        ProxyFirewallAck.Status.IO_ERROR -> CleanupReport.failure(
            "${step}_io",
            IOException(ack.detail.ifBlank { "proxy firewall kernel mutation failed" }),
        )
        is ProxyFirewallAck.Status.Unrecognized -> CleanupReport.failure(
            "${step}_protocol",
            IOException("unrecognized proxy firewall acknowledgement status ${ack.status.value}"),
        )
    }

    private fun requireOk(step: String, ack: ProxyFirewallAck) {
        when (ack.status) {
            ProxyFirewallAck.Status.OK -> Unit
            ProxyFirewallAck.Status.STALE_SESSION,
            ProxyFirewallAck.Status.STALE_EPOCH -> throw staleException(ack)
            ProxyFirewallAck.Status.INVALID -> throw IllegalStateException(
                ack.detail.ifBlank { "$step rejected by proxy firewall daemon" },
            )
            ProxyFirewallAck.Status.IO_ERROR -> throw IOException(
                ack.detail.ifBlank { "$step proxy firewall mutation failed" },
            )
            is ProxyFirewallAck.Status.Unrecognized -> throw IOException(
                "unrecognized proxy firewall acknowledgement status ${ack.status.value}",
            )
        }
    }

    private fun requireIdentity(ack: ProxyFirewallAck): DaemonIdentity =
        checkNotNull(ack.identity) { "proxy firewall acknowledgement missing daemon identity" }

    private fun staleException(ack: ProxyFirewallAck) = StaleProxyFirewallTokenException(
        status = ack.status,
        identity = ack.identity,
        detail = ack.detail,
    )

    private fun ProxyFirewallConfig.toProto(): WireProxyFirewallConfig = WireProxyFirewallConfig(
        downstreams = downstreams.map { downstream ->
            ProxyDownstream(
                interface_name = downstream.interfaceName,
                ipv4_addresses = downstream.ipv4Addresses.map { parseIpv4(it).toByteString() },
            )
        },
        tcp_port = tcpPort,
        udp_port_range_start = udpPortRangeStart,
        udp_port_range_end = udpPortRangeEnd,
        allowed_clients = allowedClients.map { client ->
            ProxyClient(
                mac = parseMac(client.mac).toByteString(),
                ipv4 = client.ipv4Addresses.map { parseIpv4(it).toByteString() },
            )
        },
        generation = generation,
        deny_all_ipv4 = denyAllIpv4,
        deny_all_ipv6 = denyAllIpv6,
    )

    private fun parseIpv4(value: String): ByteArray {
        val parts = value.split('.')
        require(parts.size == 4) { "invalid IPv4 literal $value" }
        return ByteArray(4) { index ->
            val octet = parts[index].toIntOrNull()
            require(octet != null && octet in 0..255) { "invalid IPv4 literal $value" }
            octet.toByte()
        }
    }

    private fun parseMac(value: String): ByteArray {
        val compact = value.replace(":", "").replace("-", "")
        require(compact.length == 12) { "invalid MAC address $value" }
        return ByteArray(6) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun recordSanitation(result: SanitationResult): SanitationResult {
        latestSanitation = result
        return result
    }
}
