package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
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
            ack(ProxyFirewallAck.Status.OK, session = 11, epoch = 4),
            ack(ProxyFirewallAck.Status.OK, session = 11, epoch = 4, handle = 99),
        )
        val client = client(rpc)

        assertEquals(SanitationResult(11, 4), client.cleanOrDenyBeforeRestart())
        val handle = client.start(config(generation = 5, deny = true))

        assertEquals(ProxyFirewallHandle(11, 4, 99), handle)
        val sanitize = rpc.commands.single { it.sanitize != null }.sanitize!!
        assertNotNull(sanitize.containment_config)
        assertTrue(sanitize.containment_config!!.deny_all_ipv4)
        assertTrue(sanitize.containment_config!!.deny_all_ipv6)
        assertEquals(0, sanitize.containment_config!!.allowed_clients.size)

        val start = rpc.commands.single { it.start != null }.start!!
        assertEquals(11L, start.expected_session_id)
        assertEquals(4L, start.expected_epoch)
        assertEquals(5L, start.config!!.generation)
        assertTrue(start.config!!.deny_all_ipv4)
        assertTrue(start.config!!.deny_all_ipv6)
    }

    @Test
    fun staleDenyAck_isCriticalNonResolvingFailureAndCarriesHandleToken() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallAck.Status.STALE_SESSION,
                session = 12,
                epoch = 1,
                detail = "old session",
            ),
        )
        val client = client(rpc)
        val handle = ProxyFirewallHandle(sessionId = 11, epoch = 4, id = 99)

        val report = client.denyAll(handle)

        assertTrue(report.hasCriticalFailure)
        assertEquals("deny_stale", report.failures.single().step)
        val failure = report.failures.single().cause as? StaleProxyFirewallTokenException
        assertNotNull(failure)
        assertEquals(12L, failure!!.identity?.session_id)
        val deny = rpc.commands.single().deny!!
        assertEquals(99L, deny.handle_id)
        assertEquals(11L, deny.expected_session_id)
        assertEquals(4L, deny.expected_epoch)
    }

    @Test
    fun failedSanitation_returnsNullAndDoesNotPermitFirstStart() = runBlocking {
        val rpc = FakeRpc(
            ack(
                status = ProxyFirewallAck.Status.IO_ERROR,
                session = 7,
                epoch = 0,
                detail = "iptables failed",
            ),
        )
        val client = client(rpc)

        assertNull(client.cleanOrDenyBeforeRestart())
        val failure = runCatching { client.start(config(generation = 1, deny = true)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, rpc.commands.size)
    }

    @Test
    fun secondFailedSanitation_clearsPreviouslySuccessfulToken() = runBlocking {
        val rpc = FakeRpc(
            ack(ProxyFirewallAck.Status.OK, session = 7, epoch = 1),
            ack(ProxyFirewallAck.Status.IO_ERROR, session = 7, epoch = 1),
        )
        val client = client(rpc)

        assertEquals(SanitationResult(7, 1), client.cleanOrDenyBeforeRestart())
        assertNull(client.cleanOrDenyBeforeRestart())
        val failure = runCatching { client.start(config(generation = 2, deny = true)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(2, rpc.commands.size)
    }

    @Test
    fun replaceSerializesPackedAddressesAndMac() = runBlocking {
        val rpc = FakeRpc(
            ack(ProxyFirewallAck.Status.OK, session = 2, epoch = 3, handle = 4),
        )
        val client = client(rpc)
        val handle = ProxyFirewallHandle(2, 3, 4)

        client.replace(handle, config(generation = 8, deny = false))

        val replace = rpc.commands.single().replace!!
        assertEquals(
            listOf<Byte>(192.toByte(), 168.toByte(), 43, 1),
            replace.config!!.downstreams.single().ipv4_addresses.single().toByteArray().toList(),
        )
        assertEquals(
            listOf<Byte>(0x02, 0, 0, 0, 0, 1),
            replace.config!!.allowed_clients.single().mac.toByteArray().toList(),
        )
    }

    private fun client(rpc: FakeRpc) = DaemonProxyFirewallClient(rpc) {
        config(generation = 1, deny = true)
    }

    private class FakeRpc(vararg acks: ProxyFirewallAck) : ProxyFirewallRpc {
        val commands = mutableListOf<ProxyFirewallCommand>()
        private val responses = ArrayDeque(acks.toList())

        override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
            commands += command
            return responses.removeFirst()
        }
    }

    private fun ack(
        status: ProxyFirewallAck.Status,
        session: Long,
        epoch: Long,
        handle: Long = 0,
        detail: String = "",
    ): ProxyFirewallAck = ProxyFirewallAck(
        status = status,
        identity = DaemonIdentity(
            session_id = session,
            epoch = epoch,
            generation = session,
        ),
        handle_id = handle,
        detail = detail,
    )

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
