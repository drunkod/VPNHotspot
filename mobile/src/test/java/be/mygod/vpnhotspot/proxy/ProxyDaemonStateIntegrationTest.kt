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
        val tracker = ProxyDaemonStateTracker()
        val rawDesired = MutableStateFlow(
            desiredStateForTest(daemonGeneration = 999).copy(daemonHealthy = true),
        )
        val composed = rawDesired.withAcknowledgedDaemonState(tracker.state)
        val rpc = FakeRpc(
            ack(session = 11, epoch = 4, generation = 17),
            ack(session = 12, epoch = 1, generation = 18),
        )
        val client = client(rpc, tracker)

        val unavailable = composed.first()
        assertFalse(unavailable.daemonHealthy)
        assertNull(unavailable.daemonGeneration)

        assertEquals(SanitationResult(11, 4, 17), client.cleanOrDenyBeforeRestart())
        val firstBoot = withTimeout(1_000L) {
            composed.first { it.daemonGeneration == 17L }
        }
        assertTrue(firstBoot.daemonHealthy)
        assertEquals(17L, firstBoot.daemonGeneration)
        assertEquals(11L, tracker.state.value.sessionId)

        assertEquals(SanitationResult(12, 1, 18), client.cleanOrDenyBeforeRestart())
        val restarted = withTimeout(1_000L) {
            composed.first { it.daemonGeneration == 18L }
        }
        assertTrue(restarted.daemonHealthy)
        assertEquals(18L, restarted.daemonGeneration)
        assertEquals(12L, tracker.state.value.sessionId)
        assertEquals(
            "raw desired-state generation must never become the controller clock",
            999L,
            rawDesired.value.daemonGeneration,
        )
    }

    @Test
    fun transportDisconnectClearsAcknowledgedHealthAndGeneration() = runBlocking {
        val tracker = ProxyDaemonStateTracker()
        val rawDesired = MutableStateFlow(desiredStateForTest(daemonGeneration = 999))
        val composed = rawDesired.withAcknowledgedDaemonState(tracker.state)
        val rpc = FakeRpc(ack(session = 7, epoch = 2, generation = 9))
        val client = client(rpc, tracker)

        assertEquals(SanitationResult(7, 2, 9), client.cleanOrDenyBeforeRestart())
        assertEquals(9L, composed.first { it.daemonHealthy }.daemonGeneration)

        rpc.failure = IOException("root daemon channel closed")
        assertNull(client.cleanOrDenyBeforeRestart())

        val unavailable = withTimeout(1_000L) {
            composed.first { !it.daemonHealthy }
        }
        assertNull(unavailable.daemonGeneration)
        assertEquals(ProxyDaemonState.Unavailable, tracker.state.value)
    }

    private fun client(
        rpc: ProxyFirewallRpc,
        tracker: ProxyDaemonStateTracker,
    ) = DaemonProxyFirewallClient(
        rpc = TrackingProxyFirewallRpc(rpc, tracker),
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
