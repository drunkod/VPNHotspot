package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallProto
import com.google.protobuf.ByteString
import java.io.IOException

/**
 * Transport seam for the existing root-daemon request/reply channel.
 * Implementations wrap [ProxyFirewallProto.ProxyFirewallCommand] in the daemon
 * ClientEnvelope and return the typed ProxyFirewallAck reply payload.
 */
interface ProxyFirewallRpc {
    suspend fun execute(
        command: ProxyFirewallProto.ProxyFirewallCommand,
    ): ProxyFirewallProto.ProxyFirewallAck
}

class StaleProxyFirewallTokenException(
    val status: ProxyFirewallProto.ProxyFirewallAck.Status,
    val identity: ProxyFirewallProto.DaemonIdentity?,
    detail: String,
) : IOException(
    buildString {
        append("proxy firewall token rejected: ")
        append(status)
        identity?.let {
            append(" current=")
            append(it.sessionId)
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
 */
class DaemonProxyFirewallClient(
    private val rpc: ProxyFirewallRpc,
) : ProxyFirewallClient {
    override suspend fun cleanOrDenyBeforeRestart(): SanitationResult? {
        val ack = rpc.execute(
            ProxyFirewallProto.ProxyFirewallCommand.newBuilder()
                .setSanitize(
                    ProxyFirewallProto.SanitizeRequest.newBuilder()
                        .setReason("controller sanitation gate")
                        .build(),
                )
                .build(),
        )
        if (ack.status != ProxyFirewallProto.ProxyFirewallAck.Status.OK || !ack.hasIdentity()) {
            return null
        }
        return recordSanitation(
            SanitationResult(
                sessionId = ack.identity.sessionId,
                epoch = ack.identity.epoch,
            ),
        )
    }

    override suspend fun start(config: ProxyFirewallConfig): ProxyFirewallHandle {
        val sanitation = requireSanitationToken()
        val ack = rpc.execute(
            ProxyFirewallProto.ProxyFirewallCommand.newBuilder()
                .setStart(
                    ProxyFirewallProto.StartRequest.newBuilder()
                        .setConfig(config.toProto())
                        .setExpectedSessionId(sanitation.sessionId)
                        .setExpectedEpoch(sanitation.epoch)
                        .build(),
                )
                .build(),
        )
        requireOk("start", ack)
        require(ack.handleId != 0L) { "daemon returned zero proxy firewall handle" }
        val identity = requireIdentity(ack)
        return ProxyFirewallHandle(
            sessionId = identity.sessionId,
            epoch = identity.epoch,
            id = ack.handleId,
        )
    }

    override suspend fun replace(handle: ProxyFirewallHandle, config: ProxyFirewallConfig) {
        val ack = rpc.execute(
            ProxyFirewallProto.ProxyFirewallCommand.newBuilder()
                .setReplace(
                    ProxyFirewallProto.ReplaceRequest.newBuilder()
                        .setHandleId(handle.id)
                        .setConfig(config.toProto())
                        .setExpectedSessionId(handle.sessionId)
                        .setExpectedEpoch(handle.epoch)
                        .build(),
                )
                .build(),
        )
        requireOk("replace", ack)
    }

    override suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport = cleanupAck(
        step = "deny",
        ack = rpc.execute(
            ProxyFirewallProto.ProxyFirewallCommand.newBuilder()
                .setDeny(
                    ProxyFirewallProto.DenyRequest.newBuilder()
                        .setHandleId(handle.id)
                        .setExpectedSessionId(handle.sessionId)
                        .setExpectedEpoch(handle.epoch)
                        .build(),
                )
                .build(),
        ),
    )

    override suspend fun stop(handle: ProxyFirewallHandle): CleanupReport = cleanupAck(
        step = "firewall_stop",
        ack = rpc.execute(
            ProxyFirewallProto.ProxyFirewallCommand.newBuilder()
                .setStop(
                    ProxyFirewallProto.StopRequest.newBuilder()
                        .setHandleId(handle.id)
                        .setExpectedSessionId(handle.sessionId)
                        .setExpectedEpoch(handle.epoch)
                        .build(),
                )
                .build(),
        ),
    )

    /**
     * The controller always sanitizes before start and validates the returned
     * handle afterwards. The transport retains the most recent successful
     * sanitation acknowledgement solely to populate Start's expected token.
     */
    private var latestSanitation: SanitationResult? = null

    private fun requireSanitationToken(): SanitationResult = latestSanitation
        ?: throw IllegalStateException("proxy firewall start requires successful sanitation")

    private fun cleanupAck(
        step: String,
        ack: ProxyFirewallProto.ProxyFirewallAck,
    ): CleanupReport = when (ack.status) {
        ProxyFirewallProto.ProxyFirewallAck.Status.OK -> CleanupReport.empty()
        ProxyFirewallProto.ProxyFirewallAck.Status.STALE_SESSION,
        ProxyFirewallProto.ProxyFirewallAck.Status.STALE_EPOCH -> CleanupReport.failure(
            "${step}_stale",
            staleException(ack),
        )
        ProxyFirewallProto.ProxyFirewallAck.Status.INVALID -> CleanupReport.failure(
            "${step}_invalid",
            IllegalStateException(ack.detail.ifBlank { "daemon rejected proxy firewall command" }),
        )
        ProxyFirewallProto.ProxyFirewallAck.Status.IO_ERROR -> CleanupReport.failure(
            "${step}_io",
            IOException(ack.detail.ifBlank { "proxy firewall kernel mutation failed" }),
        )
        ProxyFirewallProto.ProxyFirewallAck.Status.UNRECOGNIZED -> CleanupReport.failure(
            "${step}_protocol",
            IOException("unrecognized proxy firewall acknowledgement status ${ack.statusValue}"),
        )
    }

    private fun requireOk(
        step: String,
        ack: ProxyFirewallProto.ProxyFirewallAck,
    ) {
        when (ack.status) {
            ProxyFirewallProto.ProxyFirewallAck.Status.OK -> Unit
            ProxyFirewallProto.ProxyFirewallAck.Status.STALE_SESSION,
            ProxyFirewallProto.ProxyFirewallAck.Status.STALE_EPOCH -> throw staleException(ack)
            ProxyFirewallProto.ProxyFirewallAck.Status.INVALID -> throw IllegalStateException(
                ack.detail.ifBlank { "$step rejected by proxy firewall daemon" },
            )
            ProxyFirewallProto.ProxyFirewallAck.Status.IO_ERROR -> throw IOException(
                ack.detail.ifBlank { "$step proxy firewall mutation failed" },
            )
            ProxyFirewallProto.ProxyFirewallAck.Status.UNRECOGNIZED -> throw IOException(
                "unrecognized proxy firewall acknowledgement status ${ack.statusValue}",
            )
        }
    }

    private fun requireIdentity(
        ack: ProxyFirewallProto.ProxyFirewallAck,
    ): ProxyFirewallProto.DaemonIdentity {
        check(ack.hasIdentity()) { "proxy firewall acknowledgement missing daemon identity" }
        return ack.identity
    }

    private fun staleException(
        ack: ProxyFirewallProto.ProxyFirewallAck,
    ) = StaleProxyFirewallTokenException(
        status = ack.status,
        identity = ack.identity.takeIf { ack.hasIdentity() },
        detail = ack.detail,
    )

    private fun ProxyFirewallConfig.toProto(): ProxyFirewallProto.ProxyFirewallConfig {
        val builder = ProxyFirewallProto.ProxyFirewallConfig.newBuilder()
            .setTcpPort(tcpPort)
            .setUdpPortRangeStart(udpPortRangeStart)
            .setUdpPortRangeEnd(udpPortRangeEnd)
            .setGeneration(generation)
            .setDenyAllIpv4(denyAllIpv4)
            .setDenyAllIpv6(denyAllIpv6)
        downstreams.forEach { downstream ->
            builder.addDownstreams(
                ProxyFirewallProto.ProxyDownstream.newBuilder()
                    .setInterfaceName(downstream.interfaceName)
                    .addAllIpv4Addresses(
                        downstream.ipv4Addresses.map { ByteString.copyFrom(parseIpv4(it)) },
                    )
                    .build(),
            )
        }
        allowedClients.forEach { client ->
            builder.addAllowedClients(
                ProxyFirewallProto.ProxyClient.newBuilder()
                    .setMac(ByteString.copyFrom(parseMac(client.mac)))
                    .addAllIpv4(client.ipv4Addresses.map { ByteString.copyFrom(parseIpv4(it)) })
                    .build(),
            )
        }
        return builder.build()
    }

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
