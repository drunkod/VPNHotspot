package be.mygod.vpnhotspot.proxy

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeardownDominanceTest {
    @Test
    fun emergencyCloseFails_stopFeatureSucceeds_noServiceOrListenerDebtRemains() = runBlocking {
        ControllerHarness().use { harness ->
            val emergencyFailure = CleanupFailure("emergency_close", IOException("listener close failed"))
            harness.service.emergencyOutcome = CleanupOutcome(
                report = CleanupReport(failures = listOf(emergencyFailure)),
                debt = CleanupDebt(
                    listenerClosePending = true,
                    serviceHandlePending = ProxyServiceHandle(99),
                    firewallHandlePending = null,
                    firewallDenyPending = false,
                    firewallStopPending = false,
                    daemonCleanPending = false,
                    featureStopPending = false,
                    serviceWasActivated = true,
                    failures = listOf(emergencyFailure),
                    generation = 2,
                    attempt = 0,
                ),
            )
            harness.service.stopFeatureResult = CleanupReport.empty()

            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(10),
                sanitizedDaemonGeneration = 10,
                sanitizedSessionId = 5,
                sanitizedEpoch = 2,
                debt = CleanupDebt(
                    listenerClosePending = true,
                    serviceHandlePending = null,
                    firewallHandlePending = null,
                    firewallDenyPending = false,
                    firewallStopPending = false,
                    daemonCleanPending = false,
                    featureStopPending = true,
                    serviceWasActivated = true,
                    failures = emptyList(),
                    generation = 1,
                    attempt = 0,
                ),
                serviceActivated = true,
            )

            harness.controller.retryCleanupDebtForTrackATest()

            assertEquals(
                listOf("emergencyCloseListener", "stopFeature"),
                harness.service.calls.filter { it == "emergencyCloseListener" || it == "stopFeature" },
            )
            assertNull(harness.controller.cleanupDebtForTrackATest())
            assertFalse(harness.controller.serviceActivatedForTrackATest())
        }
    }

    @Test
    fun successfulFeatureStop_dominatesServiceDebt_butFirewallSanitationDebtSurvives() = runBlocking {
        ControllerHarness().use { harness ->
            val emergencyFailure = CleanupFailure("emergency_close", IOException("listener close failed"))
            harness.service.emergencyOutcome = CleanupOutcome(
                report = CleanupReport(failures = listOf(emergencyFailure)),
                debt = CleanupDebt(
                    listenerClosePending = true,
                    serviceHandlePending = ProxyServiceHandle(99),
                    firewallHandlePending = null,
                    firewallDenyPending = false,
                    firewallStopPending = false,
                    daemonCleanPending = false,
                    featureStopPending = false,
                    serviceWasActivated = true,
                    failures = listOf(emergencyFailure),
                    generation = 2,
                    attempt = 0,
                ),
            )
            harness.service.stopFeatureResult = CleanupReport.empty()

            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(10),
                sanitizedDaemonGeneration = 10,
                sanitizedSessionId = 5,
                sanitizedEpoch = 2,
                debt = CleanupDebt(
                    listenerClosePending = true,
                    serviceHandlePending = null,
                    firewallHandlePending = ProxyFirewallHandle(sessionId = 6, epoch = 2, id = 7),
                    firewallDenyPending = true,
                    firewallStopPending = true,
                    daemonCleanPending = false,
                    featureStopPending = true,
                    serviceWasActivated = true,
                    failures = emptyList(),
                    generation = 1,
                    attempt = 0,
                ),
                serviceActivated = true,
            )

            harness.controller.retryCleanupDebtForTrackATest()

            val debt = harness.controller.cleanupDebtForTrackATest()
            assertTrue(debt?.daemonCleanPending == true)
            assertNull(debt?.serviceHandlePending)
            assertFalse(debt?.listenerClosePending == true)
            assertFalse(debt?.featureStopPending == true)
            assertEquals(setOf(CleanupResource.DAEMON_CLEAN), debt?.unresolved)
            assertFalse(harness.controller.serviceActivatedForTrackATest())
            assertTrue(harness.firewall.handleIpcCalls().isEmpty())
        }
    }
}
