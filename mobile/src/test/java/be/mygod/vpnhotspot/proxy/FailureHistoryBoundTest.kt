package be.mygod.vpnhotspot.proxy

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureHistoryBoundTest {
    @Test
    fun identicalFailureAcrossManyMergesCollapsesToOne() {
        var debt = debtWith(listOf(ioFailure("firewall_stop")))
        val incoming = debtWith(listOf(ioFailure("firewall_stop")))

        repeat(100) { debt = debt.mergeUnresolved(incoming) }

        assertEquals(1, debt.failures.count { it.step == "firewall_stop" })
    }

    @Test
    fun distinctFailuresAreAllRetainedUpToCap() {
        var debt = debtWith()
        repeat(10) { index ->
            debt = debt.mergeUnresolved(debtWith(listOf(ioFailure("step$index"))))
        }

        assertEquals(10, debt.failures.size)
    }

    @Test
    fun historyNeverExceedsCapAndKeepsNewestDistinctEntries() {
        var debt = debtWith()
        repeat(100) { index ->
            debt = debt.mergeUnresolved(debtWith(listOf(ioFailure("step$index"))))
        }

        assertEquals(MAX_FAILURE_HISTORY, debt.failures.size)
        assertEquals("step68", debt.failures.first().step)
        assertEquals("step99", debt.failures.last().step)
    }

    @Test
    fun conflictFailuresStillDeduplicatedPerStep() {
        var debt = debtWith(serviceHandle = ProxyServiceHandle(1))
            .mergeUnresolved(debtWith(serviceHandle = ProxyServiceHandle(2)))

        repeat(100) {
            debt = debt.mergeUnresolved(debtWith(serviceHandle = ProxyServiceHandle(3 + it)))
        }

        assertEquals(1, debt.failures.count { it.step == "service_handle_conflict" })
        assertTrue(debt.failures.size <= MAX_FAILURE_HISTORY)
    }

    @Test
    fun distinctRootCausesOnSameStepAreKept() {
        var debt = debtWith(listOf(ioFailure("firewall_stop", "eperm")))
        debt = debt.mergeUnresolved(
            debtWith(listOf(ioFailure("firewall_stop", "timeout"))),
        )

        assertEquals(2, debt.failures.count { it.step == "firewall_stop" })
    }

    @Test
    fun veryLongMessagesDoNotCreateDistinctKeysPastThePrefixCap() {
        val prefix = "x".repeat(120)
        val first = ioFailure("firewall_stop", prefix + "first")
        val second = ioFailure("firewall_stop", prefix + "second")

        val merged = debtWith(listOf(first)).mergeUnresolved(debtWith(listOf(second)))

        assertEquals(1, merged.failures.size)
    }

    private fun ioFailure(step: String, message: String = "boom") =
        CleanupFailure(step, IOException(message))

    private fun debtWith(
        failures: List<CleanupFailure> = emptyList(),
        serviceHandle: ProxyServiceHandle? = null,
    ) = CleanupDebt(
        listenerClosePending = false,
        serviceHandlePending = serviceHandle,
        firewallHandlePending = null,
        firewallDenyPending = false,
        firewallStopPending = false,
        daemonCleanPending = serviceHandle == null,
        featureStopPending = false,
        serviceWasActivated = serviceHandle != null,
        failures = failures,
        generation = 1,
        attempt = 0,
    )
}
