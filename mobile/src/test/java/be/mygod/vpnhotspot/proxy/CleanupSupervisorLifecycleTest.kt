package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSupervisorLifecycleTest {
    @Test
    fun retryRunsAfterControllerWorkerScopeCancelled() = runBlocking {
        ControllerHarness(cleanupDelayMillis = { 25L }).use { harness ->
            val debt = serviceDebt(serviceWasActivated = true)
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(11),
                sanitizedDaemonGeneration = 11,
                sanitizedSessionId = 9,
                sanitizedEpoch = 7,
                debt = debt,
                serviceActivated = false,
            )

            harness.controller.scheduleDebtRetryForTrackCTest(debt)
            harness.cancelWorker()

            withTimeout(2_000L) {
                while (harness.service.calls.none { it == "stopBackend(41)" }) delay(10L)
            }
            assertNull(harness.controller.cleanupDebtForTrackATest())
            assertTrue(harness.cleanupOwner.cleanupSupervisor.isActive)
        }
    }

    @Test
    fun shutdownCancelsInFlightRetries() = runBlocking {
        ControllerHarness(cleanupDelayMillis = { 60_000L }).use { harness ->
            val debt = serviceDebt(serviceWasActivated = true)
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(11),
                sanitizedDaemonGeneration = 11,
                sanitizedSessionId = 9,
                sanitizedEpoch = 7,
                debt = debt,
                serviceActivated = false,
            )

            harness.controller.scheduleDebtRetryForTrackCTest(debt)
            harness.cleanupOwner.close()
            delay(50L)

            assertFalse(harness.cleanupOwner.cleanupSupervisor.isActive)
            assertFalse(harness.service.calls.any { it.startsWith("stopBackend") })
            assertNotNull(harness.controller.cleanupDebtForTrackATest())
        }
    }

    @Test
    fun serviceDebtWithServiceWasActivatedFalse_resolvesAsVoid() = runBlocking {
        ControllerHarness().use { harness ->
            val debt = serviceDebt(
                serviceWasActivated = false,
                listenerClosePending = true,
                featureStopPending = true,
            )
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(11),
                sanitizedDaemonGeneration = 11,
                sanitizedSessionId = 9,
                sanitizedEpoch = 7,
                debt = debt,
                // Deliberate drift: mutable local state must not authorize this debt.
                serviceActivated = true,
            )

            harness.controller.retryCleanupDebtForTrackATest()

            assertNull(harness.controller.cleanupDebtForTrackATest())
            assertTrue(harness.service.calls.isEmpty())
            assertTrue(
                "newer controller-local activation must not be clobbered by old void debt",
                harness.controller.serviceActivatedForTrackATest(),
            )
        }
    }

    @Test
    fun serviceDebtWithServiceWasActivatedTrue_retriesServiceIpcDespiteLocalDrift() = runBlocking {
        ControllerHarness().use { harness ->
            val debt = serviceDebt(
                serviceWasActivated = true,
                listenerClosePending = true,
                featureStopPending = true,
            )
            harness.controller.seedForTrackATest(
                latest = desiredStateForTest(11),
                sanitizedDaemonGeneration = 11,
                sanitizedSessionId = 9,
                sanitizedEpoch = 7,
                debt = debt,
                // Deliberate drift: debt remains the authoritative IPC eligibility source.
                serviceActivated = false,
            )

            harness.controller.retryCleanupDebtForTrackATest()

            assertEquals(
                listOf("stopBackend(41)", "stopFeature"),
                harness.service.calls.filter {
                    it.startsWith("stopBackend") || it == "stopFeature"
                },
            )
            assertNull(harness.controller.cleanupDebtForTrackATest())
            assertFalse(harness.controller.serviceActivatedForTrackATest())
        }
    }

    private fun serviceDebt(
        serviceWasActivated: Boolean,
        listenerClosePending: Boolean = false,
        featureStopPending: Boolean = false,
    ) = CleanupDebt(
        listenerClosePending = listenerClosePending,
        serviceHandlePending = ProxyServiceHandle(41),
        firewallHandlePending = null,
        firewallDenyPending = false,
        firewallStopPending = false,
        daemonCleanPending = false,
        featureStopPending = featureStopPending,
        serviceWasActivated = serviceWasActivated,
        failures = emptyList(),
        generation = 1,
        attempt = 0,
    )
}
