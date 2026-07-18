package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import be.mygod.vpnhotspot.root.daemon.DaemonTransportClosureSignal
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyDaemonTransportBridgeTest {
    @Test
    fun controllerTransportClosureContainsBackendAndRequiresFreshAcknowledgement() = runBlocking {
        val signal = DaemonTransportClosureSignal()
        val rpc = FakeRpc(
            ack(session = 11, epoch = 1, generation = 21),
            ack(session = 12, epoch = 1, generation = 22),
        )
        val composition = ProxyDaemonComposition(rpc) { config() }
        assertEquals(11L, composition.bootstrap().sessionId)

        val contained = AtomicBoolean()
        val bridge = ProxyDaemonTransportBridge(
            composition = composition,
            containBackend = {
                contained.set(true)
                CleanupReport.empty()
            },
            rebootstrap = { composition.bootstrap() },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.collect(
                closures = signal.epoch,
                initialEpoch = signal.epoch.value,
                leaseActive = { true },
            )
        }

        signal.connectionClosed(wasConnected = true, activeLeases = 1, closingAlready = false)
        val restarted = withTimeout(1_000L) {
            composition.daemonState.first { it.sessionId == 12L }
        }

        assertTrue(contained.get())
        assertTrue(restarted.healthy)
        assertEquals(22L, restarted.generation)
        assertEquals(2, rpc.commands.size)
        collector.cancelAndJoin()
    }

    @Test
    fun intentionalFinalLeaseCloseDoesNotPublishUnexpectedClosure() {
        val signal = DaemonTransportClosureSignal()
        signal.connectionClosed(wasConnected = true, activeLeases = 0, closingAlready = false)
        signal.connectionClosed(wasConnected = true, activeLeases = 1, closingAlready = true)
        assertEquals(0L, signal.epoch.value)

        signal.connectionClosed(wasConnected = true, activeLeases = 1, closingAlready = false)
        assertEquals(1L, signal.epoch.value)
    }

    private class FakeRpc(vararg acknowledgements: ProxyFirewallAck) : ProxyFirewallRpc {
        val commands = mutableListOf<ProxyFirewallCommand>()
        private val responses = ArrayDeque(acknowledgements.toList())

        override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
            commands += command
            return responses.removeFirst()
        }
    }

    private fun ack(session: Long, epoch: Long, generation: Long) = ProxyFirewallAck(
        status = ProxyFirewallAck.Status.OK,
        identity = DaemonIdentity(session_id = session, epoch = epoch, generation = generation),
    )

    private fun config() = ProxyFirewallConfig(
        downstreams = emptyList(),
        tcpPort = 1080,
        udpPortRangeStart = 20_000,
        udpPortRangeEnd = 20_100,
        allowedClients = emptyList(),
        generation = 1,
        denyAllIpv4 = true,
        denyAllIpv6 = true,
    )
}
