package be.mygod.vpnhotspot.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupDebtMergeTest {
    @Test
    fun conflictingServiceHandles_requireAuthoritativeFeatureStop() {
        val merged = debt(serviceHandle = ProxyServiceHandle(1)).mergeUnresolved(
            debt(serviceHandle = ProxyServiceHandle(2)),
        )

        assertNull(merged.serviceHandlePending)
        assertTrue(merged.listenerClosePending)
        assertTrue(merged.featureStopPending)
        assertEquals(1, merged.failures.count { it.step == "service_handle_conflict" })
    }

    @Test
    fun repeatedServiceConflict_doesNotReadoptConcreteHandle() {
        val first = debt(serviceHandle = ProxyServiceHandle(1)).mergeUnresolved(
            debt(serviceHandle = ProxyServiceHandle(2)),
        )
        val repeated = first.mergeUnresolved(
            debt(serviceHandle = ProxyServiceHandle(3)),
        )

        assertNull(repeated.serviceHandlePending)
        assertTrue(repeated.listenerClosePending)
        assertTrue(repeated.featureStopPending)
        assertEquals(1, repeated.failures.count { it.step == "service_handle_conflict" })
    }

    @Test
    fun authoritativeFeatureStop_dropsConcreteHandleRegardlessOfMergeSide() {
        val authoritative = debt(featureStopPending = true)
        val concrete = debt(serviceHandle = ProxyServiceHandle(3))

        assertNull(authoritative.mergeUnresolved(concrete).serviceHandlePending)
        assertNull(concrete.mergeUnresolved(authoritative).serviceHandlePending)
        assertTrue(authoritative.mergeUnresolved(concrete).featureStopPending)
        assertTrue(concrete.mergeUnresolved(authoritative).featureStopPending)
    }

    @Test
    fun conflictingFirewallHandles_dropConcreteIpcAndRequireSanitation() {
        val merged = debt(
            firewallHandle = ProxyFirewallHandle(1, 1, 1),
            firewallDenyPending = true,
            firewallStopPending = true,
        ).mergeUnresolved(
            debt(
                firewallHandle = ProxyFirewallHandle(1, 1, 2),
                firewallDenyPending = true,
                firewallStopPending = true,
            ),
        )

        assertNull(merged.firewallHandlePending)
        assertFalse(merged.firewallDenyPending)
        assertFalse(merged.firewallStopPending)
        assertTrue(merged.daemonCleanPending)
        assertEquals(1, merged.failures.count { it.step == "firewall_handle_conflict" })
    }

    private fun debt(
        serviceHandle: ProxyServiceHandle? = null,
        firewallHandle: ProxyFirewallHandle? = null,
        firewallDenyPending: Boolean = false,
        firewallStopPending: Boolean = false,
        featureStopPending: Boolean = false,
    ) = CleanupDebt(
        listenerClosePending = false,
        serviceHandlePending = serviceHandle,
        firewallHandlePending = firewallHandle,
        firewallDenyPending = firewallDenyPending,
        firewallStopPending = firewallStopPending,
        daemonCleanPending = false,
        featureStopPending = featureStopPending,
        serviceWasActivated = serviceHandle != null || featureStopPending,
        failures = emptyList(),
        generation = 1,
        attempt = 0,
    )
}
