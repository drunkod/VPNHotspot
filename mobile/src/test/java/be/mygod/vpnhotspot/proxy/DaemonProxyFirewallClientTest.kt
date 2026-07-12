package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallProto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonProxyFirewallClientTest {
    @Test
    fun sanitationThenStart_usesAndReturnsOnlyDaemonIssuedIdentity() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallProto.ProxyFirewallAck.Status.OK,
                session = 11,
                epoch = 4,
            ),
            ack(
                status = ProxyFirewallProto.ProxyFirewallAck.Status.OK,
                session = 11,
                epoch = 4,
                handle = 99,
            ),
        )
        val client = DaemonProxyFirewallClient(rpc)

        assertEquals(SanitationResult(11, 4), client.cleanOrDenyBeforeRestart())
        val handle = client.start(config(generation = 5, deny = true))

        assertEquals(ProxyFirewallHandle(11, 4, 99), handle)
        val start = rpc.commands.single { it.hasStart() }.start
        assertEquals(11, start.expectedSessionId)
        assertEquals(4, start.expectedEpoch)
        assertEquals(5, start.config.generation)
        assertTrue(start.config.denyAllIpv4)
        assertTrue(start.config.denyAllIpv6)
    }

    @Test
    fun staleDenyAck_isCriticalNonResolvingFailureAndCarriesHandleToken() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallProto.ProxyFirewallAck.Status.STALE_SESSION,
                session = 12,
                epoch = 1,
                detail = "old session",
            ),
        )
        val client = DaemonProxyFirewallClient(rpc)
        val handle = ProxyFirewallHandle(sessionId = 11, epoch = 4, id = 99)

        val report = client.denyAll(handle)

        assertTrue(report.hasCriticalFailure)
        assertEquals("deny_stale", report.failures.single().step)
        val failure = report.failures.single().cause as? StaleProxyFirewallTokenException
        assertNotNull(failure)
        assertEquals(12, failure!!.identity?.sessionId)
        val deny = rpc.commands.single().deny
        assertEquals(99, deny.handleId)
        assertEquals(11, deny.expectedSessionId)
        assertEquals(4, deny.expectedEpoch)
    }

    @Test
    fun failedSanitation_returnsNullAndDoesNotPermitFirstStart() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallProto.ProxyFirewallAck.Status.IO_ERROR,
                session = 7,
                epoch = 0,
                detail = "iptables failed",
            ),
        )
        val client = DaemonProxyFirewallClient(rpc)

        assertNull(client.cleanOrDenyBeforeRestart())
        val failure = runCatching { client.start(config(generation = 1, deny = true)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, rpc.commands.size)
    }

    @Test
    fun replaceSerializesPackedAddressesAndMac() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallProto.ProxyFirewallAck.Status.OK,
                session = 2,
                epoch = 3,
                handle = 4,
            ),
        )
        val client = DaemonProxyFirewallClient(rpc)
        val handle = ProxyFirewallHandle(2, 3, 4)

        client.replace(handle, config(generation = 8, deny = false))

        val replace = rpc.commands.single().replace
        assertEquals(listOf<Byte>(192.toByte(), 168.toByte(), 43, 1),
            replace.config.downstreamsList.single().ipv4AddressesList.single().toByteArray().toList())
        assertEquals(listOf<Byte>(0x02, 0, 0, 0, 0, 1),
            replace.config.allowedClientsList.single().mac.toByteArray().toList())
    }

    private class FakeRpc(vararg acks: ProxyFirewallProto.ProxyFirewallAck) : ProxyFirewallRpc {
        val commands = mutableListOf<ProxyFirewallProto.ProxyFirewallCommand>()
        private val responses = ArrayDeque(acks.toList())

        override suspend fun execute(
            command: ProxyFirewallProto.ProxyFirewallCommand,
        ): ProxyFirewallProto.ProxyFirewallAck {
            commands += command
            return responses.removeFirst()
        }
    }

    private fun ack(
        status: ProxyFirewallProto.ProxyFirewallAck.Status,
        session: Long,
        epoch: Long,
        handle: Long = 0,
        detail: String = "",
    ): ProxyFirewallProto.ProxyFirewallAck = ProxyFirewallProto.ProxyFirewallAck.newBuilder()
        .setStatus(status)
        .setIdentity(
            ProxyFirewallProto.DaemonIdentity.newBuilder()
                .setSessionId(session)
                .setEpoch(epoch)
                .setGeneration(session)
                .build(),
        )
        .setHandleId(handle)
        .setDetail(detail)
        .build()

    private fun config(generation: Long, deny: Boolean) = ProxyFirewallConfig(
        downstreams = listOf(
            ProxyDownstreamConfig(
                interfaceName = "wlan0",
                ipv4Addresses = listOf("192.168.43.1"),
            ),
        ),
        tcpPort = 1080,
        udpPortRangeStart = 20_000,
        udpPortRangeEnd = 20_100,
        allowedClients = if (deny) emptyList() else listOf(
            AllowedClient(
                mac = "02:00:00:00:00:01",
                ipv4Addresses = listOf("192.168.43.2"),
            ),
        ),
        generation = generation,
        denyAllIpv4 = deny,
        denyAllIpv6 = true,
    )
}
