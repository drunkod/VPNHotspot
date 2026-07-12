package be.mygod.vpnhotspot.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizationTest {
    @Test
    fun downstreamIpv4SelectionIsOrderIndependent() {
        val first = desired(
            downstreams = listOf(
                ManagedDownstream("wlan1", "192.168.43.5"),
                ManagedDownstream("wlan1", "10.0.0.9"),
            ),
        ).normalized()
        val second = desired(
            downstreams = listOf(
                ManagedDownstream("wlan1", "10.0.0.9"),
                ManagedDownstream("wlan1", "192.168.43.5"),
            ),
        ).normalized()

        assertEquals(first.downstreams, second.downstreams)
        assertEquals("10.0.0.9", first.downstreams.single().ipv4Address)
    }

    @Test
    fun invalidMacIsReportedNotSilentlyDropped() {
        val output = desired(
            clients = listOf(AllowedClient("ZZ:ZZ", listOf("10.0.0.2"))),
        ).normalizedWithReport()

        assertTrue(output.state.allowedClients.isEmpty())
        assertTrue(output.report.droppedClients.any {
            it.reason == ProxyNormalizationReport.Reason.INVALID_MAC
        })
    }

    @Test
    fun clientWithNoRoutableIpv4IsReported() {
        val output = desired(
            clients = listOf(
                AllowedClient("AA:BB:CC:DD:EE:02", listOf("127.0.0.1")),
            ),
        ).normalizedWithReport()

        assertTrue(output.state.allowedClients.isEmpty())
        assertTrue(output.report.droppedClients.any {
            it.reason == ProxyNormalizationReport.Reason.NO_ROUTABLE_IPV4
        })
    }

    @Test
    fun filteredClientAddressIsReportedEvenWhenClientSurvives() {
        val output = desired(
            clients = listOf(
                AllowedClient(
                    "AA:BB:CC:DD:EE:02",
                    listOf("10.0.0.2", "169.254.1.1"),
                ),
            ),
        ).normalizedWithReport()

        assertEquals(listOf("10.0.0.2"), output.state.allowedClients.single().ipv4Addresses)
        assertTrue(output.report.droppedClients.any {
            it.reason == ProxyNormalizationReport.Reason.NON_ROUTABLE_IPV4_FILTERED
        })
        assertFalse(output.report.isClean)
    }

    @Test
    fun invalidInterfaceNameIsReported() {
        val output = desired(
            downstreams = listOf(ManagedDownstream("bad interface", "10.0.0.1")),
        ).normalizedWithReport()

        assertTrue(output.state.downstreams.isEmpty())
        assertTrue(output.report.droppedDownstreams.any {
            it.reason == ProxyNormalizationReport.Reason.INVALID_INTERFACE_NAME
        })
    }

    @Test
    fun downstreamWithoutRoutableAddressIsReportedAndRetainedWithoutAddress() {
        val output = desired(
            downstreams = listOf(ManagedDownstream("wlan1", "127.0.0.1")),
        ).normalizedWithReport()

        assertNull(output.state.downstreams.single().ipv4Address)
        assertTrue(output.report.droppedDownstreams.any {
            it.reason == ProxyNormalizationReport.Reason.NO_ROUTABLE_IPV4
        })
    }

    @Test
    fun addressChoiceReportIsDeterministicAndAuditable() {
        val output = desired(
            downstreams = listOf(
                ManagedDownstream("wlan1", "192.168.43.5"),
                ManagedDownstream("wlan1", "10.0.0.9"),
            ),
        ).normalizedWithReport()
        val choice = output.report.downstreamAddressChoices.single()

        assertEquals("wlan1", choice.interfaceName)
        assertEquals(listOf("10.0.0.9", "192.168.43.5"), choice.candidates)
        assertEquals("10.0.0.9", choice.chosen)
        assertEquals("smallest-routable-ipv4", choice.policy)
    }

    @Test
    fun cleanInputProducesCleanReport() {
        val output = desired(
            downstreams = listOf(ManagedDownstream("wlan1", "10.0.0.1")),
            clients = listOf(
                AllowedClient("AA:BB:CC:DD:EE:02", listOf("10.0.0.2")),
            ),
        ).normalizedWithReport()

        assertTrue(output.report.isClean)
        assertEquals(output.state, desired(
            downstreams = listOf(ManagedDownstream("wlan1", "10.0.0.1")),
            clients = listOf(
                AllowedClient("AA:BB:CC:DD:EE:02", listOf("10.0.0.2")),
            ),
        ).normalized())
    }

    private fun desired(
        downstreams: List<ManagedDownstream> = emptyList(),
        clients: List<AllowedClient> = emptyList(),
    ): DesiredProxyState = desiredStateForTest(
        daemonGeneration = 1,
        downstreams = downstreams,
    ).copy(allowedClients = clients)
}
