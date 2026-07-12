package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonRestartRaceTest {
    @Test
    fun fastPath_cachedG1Handle_latestSnapshotG2_containsRuntimeWithoutReplace() = runBlocking {
        ControllerHarness().use { harness ->
            val upstream = fakeVpnUpstream(handle = 42)
            val desiredG1 = desiredStateForTest(
                daemonGeneration = 100,
                vpnSelection = VpnSelection.One(upstream),
                downstreams = listOf(ManagedDownstream("wlan0", "192.168.43.1")),
            )
            val latestG2 = desiredG1.copy(daemonGeneration = 101)
            val handleG1 = ProxyFirewallHandle(sessionId = 7, epoch = 4, id = 9)

            harness.controller.seedForTrackATest(
                latest = latestG2,
                sanitizedDaemonGeneration = 100,
                sanitizedSessionId = 7,
                sanitizedEpoch = 4,
                applied = AppliedProxyState(
                    key = desiredG1.runtimeKey(upstream),
                    firewall = handleG1,
                    service = ProxyServiceHandle(3),
                    complete = true,
                ),
                serviceActivated = true,
            )

            harness.controller.reconcileForTrackATest(desiredG1)

            val debt = harness.controller.cleanupDebtForTrackATest()
            assertTrue(debt?.daemonCleanPending == true)
            assertNull(debt?.firewallHandlePending)
            assertTrue("no stale handle IPC", harness.firewall.handleIpcCalls().isEmpty())
            assertFalse("replace must not be issued", harness.firewall.calls.any { it.startsWith("replace") })
            assertFalse(
                "Running must not be published for the stale G1 snapshot",
                harness.stateSink.states.any { it is ProxyOnlyState.Running },
            )
        }
    }

    @Test
    fun cleanupPaths_latestG2AndSanitationG1_requireSanitationWithNoHandleIpc() = runBlocking {
        val handleG1 = ProxyFirewallHandle(sessionId = 7, epoch = 3, id = 10)

        ControllerHarness().use { harness ->
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(101),
                sanitizedDaemonGeneration = 100,
                sanitizedSessionId = 7,
                sanitizedEpoch = 4,
                applied = AppliedProxyState(
                    key = runtimeKeyForTest(100),
                    firewall = handleG1,
                ),
            )

            harness.controller.cleanupAppliedForTrackATest()

            assertTrue(harness.controller.cleanupDebtForTrackATest()?.daemonCleanPending == true)
            assertTrue(harness.firewall.handleIpcCalls().isEmpty())
        }

        ControllerHarness().use { harness ->
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(101),
                sanitizedDaemonGeneration = 100,
                sanitizedSessionId = 7,
                sanitizedEpoch = 4,
                debt = CleanupDebt(
                    listenerClosePending = false,
                    serviceHandlePending = null,
                    firewallHandlePending = handleG1,
                    firewallDenyPending = true,
                    firewallStopPending = true,
                    daemonCleanPending = false,
                    featureStopPending = false,
                    serviceWasActivated = false,
                    failures = emptyList(),
                    generation = 1,
                    attempt = 0,
                ),
            )

            harness.controller.retryCleanupDebtForTrackATest()

            assertTrue(harness.controller.cleanupDebtForTrackATest()?.daemonCleanPending == true)
            assertTrue(harness.firewall.handleIpcCalls().isEmpty())
        }
    }
}
