package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupGenerationMatrixTest {
    private data class Row(
        val name: String,
        val sanitizedSession: Long,
        val sanitizedEpoch: Long,
        val sanitizedGeneration: Long,
        val latestGeneration: Long,
        val handle: ProxyFirewallHandle,
        val expectDaemonCleanPending: Boolean,
        val expectHandleIpc: Boolean,
    )

    private val rows = listOf(
        Row(
            name = "exact-current",
            sanitizedSession = 5,
            sanitizedEpoch = 2,
            sanitizedGeneration = 10,
            latestGeneration = 10,
            handle = ProxyFirewallHandle(sessionId = 5, epoch = 2, id = 1),
            expectDaemonCleanPending = false,
            expectHandleIpc = true,
        ),
        Row(
            name = "generation-match-lower-epoch-dominated",
            sanitizedSession = 5,
            sanitizedEpoch = 3,
            sanitizedGeneration = 10,
            latestGeneration = 10,
            handle = ProxyFirewallHandle(sessionId = 5, epoch = 2, id = 2),
            expectDaemonCleanPending = false,
            expectHandleIpc = false,
        ),
        Row(
            name = "generation-mismatch-lower-epoch-not-dominated",
            sanitizedSession = 5,
            sanitizedEpoch = 3,
            sanitizedGeneration = 10,
            latestGeneration = 11,
            handle = ProxyFirewallHandle(sessionId = 5, epoch = 2, id = 3),
            expectDaemonCleanPending = true,
            expectHandleIpc = false,
        ),
        Row(
            name = "generation-mismatch-exact-epoch",
            sanitizedSession = 5,
            sanitizedEpoch = 2,
            sanitizedGeneration = 10,
            latestGeneration = 11,
            handle = ProxyFirewallHandle(sessionId = 5, epoch = 2, id = 4),
            expectDaemonCleanPending = true,
            expectHandleIpc = false,
        ),
        Row(
            name = "different-session",
            sanitizedSession = 5,
            sanitizedEpoch = 2,
            sanitizedGeneration = 10,
            latestGeneration = 10,
            handle = ProxyFirewallHandle(sessionId = 6, epoch = 2, id = 5),
            expectDaemonCleanPending = true,
            expectHandleIpc = false,
        ),
        Row(
            name = "future-epoch",
            sanitizedSession = 5,
            sanitizedEpoch = 2,
            sanitizedGeneration = 10,
            latestGeneration = 10,
            handle = ProxyFirewallHandle(sessionId = 5, epoch = 3, id = 6),
            expectDaemonCleanPending = true,
            expectHandleIpc = false,
        ),
    )

    @Test
    fun cleanupApplied_generationSessionEpochMatrix() = runBlocking {
        rows.forEach { row ->
            ControllerHarness().use { harness ->
                val controller = harness.controller
                controller.seedForTrackATest(
                    latest = desiredStateForTest(row.latestGeneration),
                    sanitizedDaemonGeneration = row.sanitizedGeneration,
                    sanitizedSessionId = row.sanitizedSession,
                    sanitizedEpoch = row.sanitizedEpoch,
                    applied = AppliedProxyState(
                        key = runtimeKeyForTest(row.sanitizedGeneration),
                        firewall = row.handle,
                    ),
                )

                controller.cleanupAppliedForTrackATest(reason = "matrix:${row.name}")

                val debt = controller.cleanupDebtForTrackATest()
                assertEquals(
                    "${row.name}: daemonCleanPending",
                    row.expectDaemonCleanPending,
                    debt?.daemonCleanPending == true,
                )
                assertEquals(
                    "${row.name}: handle IPC",
                    row.expectHandleIpc,
                    harness.firewall.handleIpcCalls().isNotEmpty(),
                )
                if (row.expectDaemonCleanPending) {
                    assertTrue("${row.name}: daemon-clean resource retained", CleanupResource.DAEMON_CLEAN in debt!!.unresolved)
                    assertNull("${row.name}: stale concrete firewall handle cleared", debt.firewallHandlePending)
                } else {
                    assertNull("${row.name}: cleanup fully resolved", debt)
                }
            }
        }
    }

    @Test
    fun cleanupDebtRetry_generationSessionEpochMatrix() = runBlocking {
        rows.forEach { row ->
            ControllerHarness().use { harness ->
                val controller = harness.controller
                controller.seedForTrackATest(
                    latest = desiredStateForTest(row.latestGeneration),
                    sanitizedDaemonGeneration = row.sanitizedGeneration,
                    sanitizedSessionId = row.sanitizedSession,
                    sanitizedEpoch = row.sanitizedEpoch,
                    debt = firewallDebt(row.handle),
                )

                controller.retryCleanupDebtForTrackATest()

                val debt = controller.cleanupDebtForTrackATest()
                assertEquals(
                    "${row.name}: daemonCleanPending",
                    row.expectDaemonCleanPending,
                    debt?.daemonCleanPending == true,
                )
                assertEquals(
                    "${row.name}: handle IPC",
                    row.expectHandleIpc,
                    harness.firewall.handleIpcCalls().isNotEmpty(),
                )
                if (row.expectDaemonCleanPending) {
                    assertTrue("${row.name}: sanitation attempted", "sanitize" in harness.firewall.calls)
                    assertTrue("${row.name}: daemon-clean resource retained", CleanupResource.DAEMON_CLEAN in debt!!.unresolved)
                    assertNull("${row.name}: stale concrete firewall handle cleared", debt.firewallHandlePending)
                } else {
                    assertNull("${row.name}: retry fully resolved", debt)
                }
            }
        }
    }

    @Test
    fun cleanupDebtRetry_successfulSanitation_reestablishesMarkersAndClearsDebt() = runBlocking {
        ControllerHarness().use { harness ->
            val controller = harness.controller
            harness.firewall.sanitationResults += SanitationResult(sessionId = 9, epoch = 7)
            controller.seedForTrackATest(
                latest = desiredStateForTest(11),
                sanitizedDaemonGeneration = 10,
                sanitizedSessionId = 5,
                sanitizedEpoch = 2,
                debt = firewallDebt(ProxyFirewallHandle(sessionId = 5, epoch = 2, id = 8)),
            )

            controller.retryCleanupDebtForTrackATest()

            assertNull("successful sanitation resolves the stale firewall debt", controller.cleanupDebtForTrackATest())
            assertEquals(emptyList<String>(), harness.firewall.handleIpcCalls())
            assertEquals(listOf("sanitize"), harness.firewall.calls)
            assertEquals(11L, controller.sanitizedDaemonGenerationForTrackATest())
            assertEquals(9L, controller.sanitizedSessionIdForTrackATest())
            assertEquals(7L, controller.sanitizedEpochForTrackATest())
            assertEquals(7L, controller.firewallGenerationForTrackATest())
        }
    }

    private fun firewallDebt(handle: ProxyFirewallHandle) = CleanupDebt(
        listenerClosePending = false,
        serviceHandlePending = null,
        firewallHandlePending = handle,
        firewallDenyPending = true,
        firewallStopPending = true,
        daemonCleanPending = false,
        featureStopPending = false,
        serviceWasActivated = false,
        failures = emptyList(),
        generation = 1,
        attempt = 0,
    )
}
