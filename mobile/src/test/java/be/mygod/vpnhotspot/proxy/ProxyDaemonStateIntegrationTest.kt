package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyDaemonStateIntegrationTest {
    @Test
    fun daemonRestartAcknowledgementReplacesIndependentDesiredGeneration() = runBlocking {
        val rawDesired = MutableStateFlow(
            desiredStateForTest(daemonGeneration = 999).copy(daemonHealthy = true),
        )
        val rpc = FakeRpc(
            ack(session = 11, epoch = 4, generation = 17),
            ack(session = 12, epoch = 1, generation = 18),
        )
        val composition = composition(rpc)
        val composed = composition.desiredStates(rawDesired)

        val unavailable = composed.first()
        assertFalse(unavailable.daemonHealthy)
        assertNull(unavailable.daemonGeneration)

        assertEquals(
            SanitationResult(11, 4, 17),
            composition.firewallClient.cleanOrDenyBeforeRestart(),
        )
        val firstBoot = withTimeout(1_000L) {
            composed.first { it.daemonGeneration == 17L }
        }
        assertTrue(firstBoot.daemonHealthy)
        assertEquals(17L, firstBoot.daemonGeneration)
        assertEquals(11L, composition.daemonState.value.sessionId)

        assertEquals(
            SanitationResult(12, 1, 18),
            composition.firewallClient.cleanOrDenyBeforeRestart(),
        )
        val restarted = withTimeout(1_000L) {
            composed.first { it.daemonGeneration == 18L }
        }
        assertTrue(restarted.daemonHealthy)
        assertEquals(18L, restarted.daemonGeneration)
        assertEquals(12L, composition.daemonState.value.sessionId)
        assertEquals(
            "raw desired-state generation must never become the controller clock",
            999L,
            rawDesired.value.daemonGeneration,
        )
    }

    @Test
    fun transportDisconnectClearsAcknowledgedHealthAndGeneration() = runBlocking {
        val rawDesired = MutableStateFlow(desiredStateForTest(daemonGeneration = 999))
        val rpc = FakeRpc(ack(session = 7, epoch = 2, generation = 9))
        val composition = composition(rpc)
        val composed = composition.desiredStates(rawDesired)

        assertEquals(
            SanitationResult(7, 2, 9),
            composition.firewallClient.cleanOrDenyBeforeRestart(),
        )
        assertEquals(9L, composed.first { it.daemonHealthy }.daemonGeneration)

        rpc.failure = IOException("root daemon channel closed")
        assertNull(composition.firewallClient.cleanOrDenyBeforeRestart())

        val unavailable = withTimeout(1_000L) {
            composed.first { !it.daemonHealthy }
        }
        assertNull(unavailable.daemonGeneration)
        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
    }

    @Test
    fun partialOrZeroAcknowledgementIdentityIsRejected() {
        val partial = runCatching {
            ProxyDaemonState(healthy = false, sessionId = 1, generation = null)
        }.exceptionOrNull()
        val zero = runCatching {
            ProxyDaemonState(healthy = true, sessionId = 0, generation = 1)
        }.exceptionOrNull()

        assertTrue(partial is IllegalArgumentException)
        assertTrue(zero is IllegalArgumentException)
    }

    private fun composition(rpc: ProxyFirewallRpc) = ProxyDaemonComposition(
        rpc = rpc,
        containmentConfig = { config() },
    )

    private class FakeRpc(vararg acknowledgements: ProxyFirewallAck) : ProxyFirewallRpc {
        private val responses = ArrayDeque(acknowledgements.toList())
        var failure: IOException? = null

        override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
            failure?.let { throw it }
            return responses.removeFirst()
        }
    }

    private fun ack(
        session: Long,
        epoch: Long,
        generation: Long,
    ) = ProxyFirewallAck(
        status = ProxyFirewallAck.Status.OK,
        identity = DaemonIdentity(
            session_id = session,
            epoch = epoch,
            generation = generation,
        ),
    )

    private fun config() = ProxyFirewallConfig(
        downstreams = listOf(
            ProxyDownstreamConfig(
                interfaceName = "wlan0",
                ipv4Addresses = listOf("192.168.43.1"),
            ),
        ),
        tcpPort = 1080,
        udpPortRangeStart = 20_000,
        udpPortRangeEnd = 20_100,
        allowedClients = emptyList(),
        generation = 1,
        denyAllIpv4 = true,
        denyAllIpv6 = true,
    )
}
