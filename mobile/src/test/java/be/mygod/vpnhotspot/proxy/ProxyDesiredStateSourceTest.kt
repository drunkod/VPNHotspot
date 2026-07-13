package be.mygod.vpnhotspot.proxy

import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyDesiredStateSourceTest {
    @Test
    fun assemblesAndNormalizesAllApplicationInputs() = runBlocking {
        val settings = MutableStateFlow(
            ProxyOnlySettings(
                enabled = true,
                tcpPort = 1080,
                udpEnabled = true,
                udpPortRange = 20_000..20_100,
                maxUdpAssociations = 32,
                credentialsVersion = 3,
            ),
        )
        val grant = MutableStateFlow<ActivationGrant?>(
            ActivationGrant(UUID.randomUUID(), issuedAtElapsedRealtime = 7, ActivationSource.USER_ENABLE),
        )
        val vpn = MutableStateFlow<VpnSelection>(VpnSelection.None)
        val downstreams = MutableStateFlow(
            listOf(
                ManagedDownstream(" wlan0 ", "192.168.43.10"),
                ManagedDownstream("wlan0", "192.168.43.1"),
            ),
        )
        val clients = MutableStateFlow(
            listOf(
                AllowedClient("02-00-00-00-00-01", listOf("192.168.43.2")),
                AllowedClient("02:00:00:00:00:01", listOf("192.168.43.3")),
            ),
        )

        val state = ProxyDesiredStateSource(settings, grant, vpn, downstreams, clients).states().first()

        assertEquals(settings.value, state.settings)
        assertEquals(grant.value, state.activationGrant)
        assertEquals(VpnSelection.None, state.vpnSelection)
        assertEquals(listOf(ManagedDownstream("wlan0", "192.168.43.1")), state.downstreams)
        assertEquals(
            listOf(AllowedClient("02:00:00:00:00:01", listOf("192.168.43.2", "192.168.43.3"))),
            state.allowedClients,
        )
        assertEquals(false, state.daemonHealthy)
        assertNull(state.daemonGeneration)
    }

    @Test
    fun laterPreferenceEmissionProducesNewSnapshot() = runBlocking {
        val settings = MutableStateFlow(
            ProxyOnlySettings(false, 1080, false, 20_000..20_100, 32, 1),
        )
        val source = ProxyDesiredStateSource(
            settings,
            MutableStateFlow(null),
            MutableStateFlow(VpnSelection.None),
            MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()),
        ).states()

        assertFalse(source.first().settings.enabled)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            source.first { it.settings.enabled }
        }
        settings.value = settings.value.copy(enabled = true)

        assertEquals(true, next.await().settings.enabled)
    }
}
