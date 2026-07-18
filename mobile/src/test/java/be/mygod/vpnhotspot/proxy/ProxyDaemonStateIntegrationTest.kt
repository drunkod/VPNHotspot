package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            ack(session = 11, epoch = 1, generation = 17),
            ack(session = 11, epoch = 2, generation = 17),
            ack(session = 12, epoch = 1, generation = 18),
        )
        val composition = composition(rpc)
        val composed = composition.desiredStates(rawDesired)

        val unavailable = composed.first()
        assertFalse(unavailable.daemonHealthy)
        assertNull(unavailable.daemonGeneration)

        assertEquals(
            ProxyDaemonState(healthy = true, sessionId = 11, generation = 17),
            composition.bootstrap(),
        )
        val firstBoot = withTimeout(1_000L) {
            composed.first { it.daemonGeneration == 17L }
        }
        assertTrue(firstBoot.daemonHealthy)
        assertEquals(17L, firstBoot.daemonGeneration)
        assertEquals(11L, composition.daemonState.value.sessionId)

        assertEquals(
            SanitationResult(11, 2, 17),
            composition.firewallClient.cleanOrDenyBeforeRestart(),
        )

        assertEquals(
            ProxyDaemonState(healthy = true, sessionId = 12, generation = 18),
            composition.bootstrap(),
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
        assertEquals(3, rpc.commands.count { it.sanitize != null })
    }

    @Test
    fun bootstrapAndControllerFirewallOperationsAreSerialized() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val rpc = object : ProxyFirewallRpc {
            override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
                val call = calls.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                return try {
                    if (call == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    ack(session = call.toLong(), epoch = 1, generation = call.toLong())
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val composition = composition(rpc)

        val controllerSanitation = launch {
            composition.firewallClient.cleanOrDenyBeforeRestart()
        }
        entered.await()
        val reconnectBootstrap = launch { composition.bootstrap() }
        delay(50L)

        assertEquals(1, calls.get())
        assertEquals(1, maxActive.get())

        release.complete(Unit)
        controllerSanitation.join()
        reconnectBootstrap.join()

        assertEquals(2, calls.get())
        assertEquals(1, maxActive.get())
        assertEquals(2L, composition.daemonState.value.sessionId)
    }

    @Test
    fun failedSanitationAcknowledgementNeverPublishesHealthyState() = runBlocking {
        val rawDesired = MutableStateFlow(desiredStateForTest(daemonGeneration = 999))
        val rpc = FakeRpc(
            ack(
                session = 7,
                epoch = 0,
                generation = 9,
                status = ProxyFirewallAck.Status.IO_ERROR,
            ),
        )
        val composition = composition(rpc)
        val composed = composition.desiredStates(rawDesired)

        assertEquals(ProxyDaemonState.Unavailable, composition.bootstrap())
        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
        assertFalse(composed.first().daemonHealthy)
    }

    @Test
    fun transportDisconnectClearsAcknowledgedHealthAndGeneration() = runBlocking {
        val rawDesired = MutableStateFlow(desiredStateForTest(daemonGeneration = 999))
        val rpc = FakeRpc(ack(session = 7, epoch = 1, generation = 9))
        val composition = composition(rpc)
        val composed = composition.desiredStates(rawDesired)

        assertEquals(
            ProxyDaemonState(healthy = true, sessionId = 7, generation = 9),
            composition.bootstrap(),
        )
        assertEquals(9L, composed.first { it.daemonHealthy }.daemonGeneration)

        rpc.failure = IOException("root daemon channel closed")
        assertEquals(ProxyDaemonState.Unavailable, composition.bootstrap())

        val unavailable = withTimeout(1_000L) {
            composed.first { !it.daemonHealthy }
        }
        assertNull(unavailable.daemonGeneration)
        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
    }

    @Test
    fun explicitTransportDisconnectClearsStateWithoutAnotherRpc() = runBlocking {
        val rpc = FakeRpc(ack(session = 5, epoch = 1, generation = 6))
        val composition = composition(rpc)

        composition.bootstrap()
        composition.transportDisconnected()

        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
    }

    @Test
    fun lateAcknowledgementCannotRestoreHealthAfterExplicitDisconnect() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rpc = object : ProxyFirewallRpc {
            override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
                entered.complete(Unit)
                release.await()
                return ack(session = 41, epoch = 1, generation = 51)
            }
        }
        val composition = composition(rpc)
        val bootstrap = async { composition.bootstrap() }

        entered.await()
        composition.transportDisconnected()
        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)

        release.complete(Unit)
        assertEquals(ProxyDaemonState.Unavailable, bootstrap.await())
        assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
    }

    @Test
    fun malformedAuthoritativeAcknowledgementInvalidatesPreviousHealth() = runBlocking {
        val malformed = listOf(
            ProxyFirewallAck(
                status = ProxyFirewallAck.Status.OK,
                identity = null,
            ),
            ack(session = 0, epoch = 2, generation = 9),
            ack(session = 7, epoch = 2, generation = 0),
        )

        for (acknowledgement in malformed) {
            val composition = composition(
                FakeRpc(
                    ack(session = 7, epoch = 1, generation = 9),
                    acknowledgement,
                ),
            )
            assertTrue(composition.bootstrap().healthy)

            assertEquals(ProxyDaemonState.Unavailable, composition.bootstrap())
            assertEquals(ProxyDaemonState.Unavailable, composition.daemonState.value)
        }
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
        val commands = mutableListOf<ProxyFirewallCommand>()
        private val responses = ArrayDeque(acknowledgements.toList())
        var failure: IOException? = null

        override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck {
            commands += command
            failure?.let { throw it }
            return responses.removeFirst()
        }
    }

    private fun ack(
        session: Long,
        epoch: Long,
        generation: Long,
        status: ProxyFirewallAck.Status = ProxyFirewallAck.Status.OK,
    ) = ProxyFirewallAck(
        status = status,
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
