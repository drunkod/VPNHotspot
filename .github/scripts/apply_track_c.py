from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1))


controller_path = "mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyController.kt"
replace_once(
    controller_path,
    '''    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
    /**
     * Cleanup retry jobs execute directly in this scope under [stateMutex].
     *
     * Contract: this scope MUST outlive [scope] (the worker coroutine scope).
     * Typically provided by the foreground service or application lifecycle so
     * that terminal cleanup debt can be retried after the controller worker exits.
     * The owner is responsible for cancelling this scope during final teardown.
     */
    private val cleanupScope: CoroutineScope,
) {
''',
    '''    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
    private val cleanupSupervisor: ProxyCleanupSupervisor,
    private val cleanupDelayMillis: (Int) -> Long = ::cleanupBackoff,
) {
''',
)
replace_once(
    controller_path,
    '''    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var nextDebtGeneration = 1L
''',
    '''    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var scheduledRetryGeneration: Long? = null
    private var nextDebtGeneration = 1L
''',
)
replace_once(
    controller_path,
    '''            collector.cancel()
            retryJob?.cancel()
            withContext(NonCancellable) {
''',
    '''            collector.cancel()
            retryJob?.cancel()
            retryJob = null
            scheduledRetryGeneration = null
            withContext(NonCancellable) {
''',
)
replace_once(
    controller_path,
    '''        var serviceHandle = debt.serviceHandlePending
        var listenerPending = debt.listenerClosePending

        if (serviceHandle != null && newServiceActivated) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false
            }
        }

        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && newServiceActivated) {
            val outcome = safeEmergencyClose("cleanup debt retry")
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            emergencyOutcomeDebt = outcome.debt
        }
''',
    '''        // Track C: retry eligibility belongs to the immutable debt snapshot, not
        // mutable controller-local activation state that may drift after worker exit.
        val serviceIpcValid = debt.serviceWasActivated
        var serviceHandle = debt.serviceHandlePending
        var listenerPending = debt.listenerClosePending

        if (!serviceIpcValid) {
            // No foreground-service activation existed for this debt. Service-side
            // resources are therefore void rather than retriable forever.
            serviceHandle = null
            listenerPending = false
        } else if (serviceHandle != null) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false
            }
        }

        var emergencyOutcomeDebt: CleanupDebt? = null
        if (listenerPending && serviceIpcValid) {
            val outcome = safeEmergencyClose(
                reason = "cleanup debt retry",
                serviceWasActivated = serviceIpcValid,
                serviceHandle = serviceHandle,
            )
            if (!outcome.report.hasCriticalFailure) listenerPending = false
            emergencyOutcomeDebt = outcome.debt
        }
''',
)
replace_once(
    controller_path,
    '''        var featureStopPending = debt.featureStopPending
        // R9 blocker #1 / R10 blocker #1:
''',
    '''        var featureStopPending = debt.featureStopPending
        if (!serviceIpcValid) featureStopPending = false
        // R9 blocker #1 / R10 blocker #1 / Track C:
''',
)
replace_once(
    controller_path,
    '''        if (featureStopPending && newServiceActivated && serviceHandle == null) {
''',
    '''        if (featureStopPending && serviceIpcValid && serviceHandle == null) {
''',
)
replace_once(
    controller_path,
    '''    private fun scheduleDebtRetry(debt: CleanupDebt) {
        retryJob?.cancel()
        retryJob = cleanupScope.launch {
            delay(cleanupBackoff(debt.attempt))
            try {
                stateMutex.withLock {
                    retryJob = null
                    try {
                        retryCleanupDebtSafely()
                        val unresolved = cleanupDebt
                        if (unresolved != null) {
                            publishDebt(unresolved)
                            scheduleDebtRetry(unresolved)
                        } else {
                            latestSnapshot.get()
                                ?.let { events.trySend(ControllerEvent.Snapshot(it)) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        safeReport("proxy.cleanup_retry.transaction", t)
                        val preserved = cleanupDebt
                        if (preserved != null) {
                            val faulted = preserved.copy(
                                attempt = preserved.attempt + 1,
                                failures = preserved.failures +
                                    CleanupFailure("retry_transaction", t),
                            )
                            cleanupDebt = faulted
                            publishDebt(faulted)
                            scheduleDebtRetry(faulted)
                        }
                    }
                }
            } catch (_: CancellationException) { }
        }
    }
''',
    '''    private fun scheduleDebtRetry(debt: CleanupDebt) {
        if (!cleanupSupervisor.isActive) {
            retryJob?.cancel()
            retryJob = null
            scheduledRetryGeneration = null
            safeReport(
                "proxy.cleanup_supervisor.inactive",
                IllegalStateException(
                    "cleanup supervisor inactive; debt generation ${debt.generation} retained"
                ),
                debt.failures,
            )
            return
        }
        if (retryJob?.isActive == true && scheduledRetryGeneration == debt.generation) return

        retryJob?.cancel()
        scheduledRetryGeneration = debt.generation
        retryJob = cleanupSupervisor.retryScope.launch {
            try {
                delay(cleanupDelayMillis(debt.attempt))
                stateMutex.withLock {
                    if (scheduledRetryGeneration != debt.generation) return@withLock
                    retryJob = null
                    scheduledRetryGeneration = null
                    try {
                        retryCleanupDebtSafely()
                        val unresolved = cleanupDebt
                        if (unresolved != null) {
                            publishDebt(unresolved)
                            scheduleDebtRetry(unresolved)
                        } else {
                            latestSnapshot.get()
                                ?.let { events.trySend(ControllerEvent.Snapshot(it)) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        safeReport("proxy.cleanup_retry.transaction", t)
                        val preserved = cleanupDebt
                        if (preserved != null) {
                            val faulted = preserved.copy(
                                attempt = preserved.attempt + 1,
                                failures = preserved.failures +
                                    CleanupFailure("retry_transaction", t),
                            )
                            cleanupDebt = faulted
                            publishDebt(faulted)
                            scheduleDebtRetry(faulted)
                        }
                    }
                }
            } catch (_: CancellationException) { }
        }
    }
''',
)
replace_once(
    controller_path,
    '''        cleanupDebt = merged.copy(generation = nextDebtGeneration++, attempt = 0)
        retryJob?.cancel()
        retryJob = null
''',
    '''        cleanupDebt = merged.copy(generation = nextDebtGeneration++, attempt = 0)
        retryJob?.cancel()
        retryJob = null
        scheduledRetryGeneration = null
''',
)

controller = Path(controller_path)
text = controller.read_text()
start = text.index("    private suspend fun safeEmergencyClose")
end = text.index("\n    private fun debtState", start)
section = text[start:end]
old_section = section
section = section.replace(
    '''    private suspend fun safeEmergencyClose(reason: String): CleanupOutcome {
''',
    '''    private suspend fun safeEmergencyClose(
        reason: String,
        serviceWasActivated: Boolean = serviceActivated,
        serviceHandle: ProxyServiceHandle? = applied?.service,
    ): CleanupOutcome {
''',
    1,
)
section = section.replace("serviceHandlePending = applied?.service", "serviceHandlePending = serviceHandle")
section = section.replace("serviceWasActivated = serviceActivated", "serviceWasActivated = serviceWasActivated")
if section == old_section:
    raise SystemExit("safeEmergencyClose section was not changed")
if section.count("serviceHandlePending = serviceHandle") != 2:
    raise SystemExit("safeEmergencyClose service handle replacement count mismatch")
if section.count("serviceWasActivated = serviceWasActivated") != 2:
    raise SystemExit("safeEmergencyClose activation replacement count mismatch")
controller.write_text(text[:start] + section + text[end:])

Path("mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyCleanupSupervisor.kt").write_text('''package be.mygod.vpnhotspot.proxy

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the scope in which terminal cleanup-debt retries execute.
 *
 * The foreground-service/application owner creates this object, hands it to every
 * controller worker it owns, and calls [shutdown] only at authoritative final teardown.
 * Controller workers may launch retry work but cannot cancel the supervisor lifecycle.
 */
class ProxyCleanupSupervisor private constructor(
    private val supervisorJob: CompletableJob,
    val retryScope: CoroutineScope,
) {
    private val active = AtomicBoolean(true)

    val isActive: Boolean get() = active.get() && supervisorJob.isActive

    /** Cancel all in-flight retries. Idempotent; only the lifecycle owner calls this. */
    fun shutdown(reason: String) {
        if (active.compareAndSet(true, false)) {
            supervisorJob.cancel(
                CancellationException("cleanup supervisor shutdown: $reason")
            )
        }
    }

    companion object {
        fun create(
            parent: CoroutineContext,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            name: String = "proxy-cleanup",
        ): ProxyCleanupSupervisor {
            val job = SupervisorJob(parent[Job])
            val scope = CoroutineScope(parent + job + dispatcher + CoroutineName(name))
            return ProxyCleanupSupervisor(job, scope)
        }
    }
}
''')

Path("mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyService.kt").write_text('''package be.mygod.vpnhotspot.proxy

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Phase-0 foreground-service lifecycle holder.
 *
 * The production Android service will own one instance for its full lifecycle. Keeping
 * ownership in a named object now makes it impossible to pass an anonymous raw scope to
 * [ProxyOnlyController]. [close] is the single authoritative teardown site.
 */
class ProxyServiceCleanupOwner(
    parent: CoroutineContext,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    val cleanupSupervisor: ProxyCleanupSupervisor = ProxyCleanupSupervisor.create(
        parent = parent,
        dispatcher = dispatcher,
        name = "proxy-service-cleanup",
    )

    override fun close() {
        cleanupSupervisor.shutdown("proxy service owner closed")
    }
}
''')

fake_path = "mobile/src/test/java/be/mygod/vpnhotspot/proxy/FakeProxyClients.kt"
replace_once(
    fake_path,
    '''import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
''',
    '''import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
''',
)
replace_once(
    fake_path,
    '''internal class ControllerHarness(
    val firewall: FakeFirewallClient = FakeFirewallClient(),
    val service: FakeServiceClient = FakeServiceClient(),
) : Closeable {
    private val workerJob = SupervisorJob()
    private val cleanupJob = SupervisorJob()
    private val workerScope = CoroutineScope(workerJob + Dispatchers.Unconfined)
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.Unconfined)
''',
    '''internal class ControllerHarness(
    val firewall: FakeFirewallClient = FakeFirewallClient(),
    val service: FakeServiceClient = FakeServiceClient(),
    cleanupDelayMillis: (Int) -> Long = ::cleanupBackoff,
) : Closeable {
    private val workerJob = SupervisorJob()
    private val workerScope = CoroutineScope(workerJob + Dispatchers.Unconfined)
    val cleanupOwner = ProxyServiceCleanupOwner(
        parent = EmptyCoroutineContext,
        dispatcher = Dispatchers.Unconfined,
    )
''',
)
replace_once(
    fake_path,
    '''        reporter = reporter,
        scope = workerScope,
        cleanupScope = cleanupScope,
    )

    override fun close() {
        workerScope.cancel()
        cleanupScope.cancel()
    }
''',
    '''        reporter = reporter,
        scope = workerScope,
        cleanupSupervisor = cleanupOwner.cleanupSupervisor,
        cleanupDelayMillis = cleanupDelayMillis,
    )

    fun cancelWorker() {
        workerScope.cancel()
    }

    override fun close() {
        workerScope.cancel()
        cleanupOwner.close()
    }
''',
)
replace_once(
    fake_path,
    '''internal suspend fun ProxyOnlyController.reconcileForTrackATest(state: DesiredProxyState) {
    invokePrivateSuspend<Unit>("reconcile", state)
}
''',
    '''internal suspend fun ProxyOnlyController.reconcileForTrackATest(state: DesiredProxyState) {
    invokePrivateSuspend<Unit>("reconcile", state)
}

internal fun ProxyOnlyController.scheduleDebtRetryForTrackCTest(debt: CleanupDebt) {
    val method = javaClass.declaredMethods.singleOrNull { candidate ->
        candidate.name == "scheduleDebtRetry" && candidate.parameterCount == 1
    } ?: error("No method 'scheduleDebtRetry' with one argument on ${javaClass.name}")
    method.isAccessible = true
    try {
        method.invoke(this, debt)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}
''',
)

contract_path = "mobile/src/test/java/be/mygod/vpnhotspot/proxy/ControllerHarnessContractTest.kt"
replace_once(
    contract_path,
    '''import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
''',
    '''import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
''',
)
replace_once(
    contract_path,
    ''' * It also invokes these private suspend methods:
 * cleanupApplied(reason, daemonAvailable), retryCleanupDebtSafely(), reconcile(state).
''',
    ''' * It also invokes these private methods:
 * cleanupApplied(reason, daemonAvailable), retryCleanupDebtSafely(), reconcile(state),
 * and scheduleDebtRetry(debt).
''',
)
replace_once(
    contract_path,
    '''            "cleanupDebt",
            "serviceActivated",
            "nextDebtGeneration",
''',
    '''            "cleanupDebt",
            "serviceActivated",
            "scheduledRetryGeneration",
            "nextDebtGeneration",
''',
)
replace_once(
    contract_path,
    '''        fields.forEach { name ->
            assertNotNull("missing ProxyOnlyController field '$name'", ProxyOnlyController::class.java.findField(name))
        }

        assertSuspendMethod("cleanupApplied", argumentCount = 2)
''',
    '''        fields.forEach { name ->
            assertNotNull("missing ProxyOnlyController field '$name'", ProxyOnlyController::class.java.findField(name))
        }
        assertNotNull(
            "missing concrete cleanup supervisor field",
            ProxyOnlyController::class.java.findField("cleanupSupervisor"),
        )
        assertNull(
            "raw cleanupScope contract must be removed",
            ProxyOnlyController::class.java.findField("cleanupScope"),
        )

        assertSuspendMethod("cleanupApplied", argumentCount = 2)
''',
)
replace_once(
    contract_path,
    '''        assertSuspendMethod("retryCleanupDebtSafely", argumentCount = 0)
        assertSuspendMethod("reconcile", argumentCount = 1)
''',
    '''        assertSuspendMethod("retryCleanupDebtSafely", argumentCount = 0)
        assertSuspendMethod("reconcile", argumentCount = 1)
        assertTrue(
            "missing private scheduleDebtRetry(debt)",
            ProxyOnlyController::class.java.declaredMethods.any { method ->
                method.name == "scheduleDebtRetry" && method.parameterCount == 1
            },
        )
''',
)

Path("mobile/src/test/java/be/mygod/vpnhotspot/proxy/CleanupSupervisorLifecycleTest.kt").write_text('''package be.mygod.vpnhotspot.proxy

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
''')

# Source invariants before committing.
controller_text = Path(controller_path).read_text()
checks = {
    "raw cleanupScope removed": "cleanupScope" not in controller_text,
    "concrete supervisor consumed": "private val cleanupSupervisor: ProxyCleanupSupervisor" in controller_text,
    "debt activation authoritative": "val serviceIpcValid = debt.serviceWasActivated" in controller_text,
    "supervisor scheduler used": "cleanupSupervisor.retryScope.launch" in controller_text,
    "lifecycle test created": Path(
        "mobile/src/test/java/be/mygod/vpnhotspot/proxy/CleanupSupervisorLifecycleTest.kt"
    ).exists(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit(f"Track C source invariants failed: {failed}")

# Restore the normal least-privilege workflow and remove this one-shot patcher in
# the same source commit. Final verification therefore runs on a clean tree.
Path(".github/workflows/test.yml").write_text('''name: Test

on:
  push:
  pull_request:
  workflow_dispatch:

concurrency:
  group: test-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - name: Check out repository
        uses: actions/checkout@v7
        with:
          submodules: true

      - name: Set up Java
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Install Android SDK packages
        run: sdkmanager "platforms;android-36.1" "build-tools;36.0.0" "ndk;28.2.13676358"

      - name: Install Android Rust targets
        run: rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

      - name: Install cargo-ndk
        run: cargo install cargo-ndk --locked

      - name: Check Rust daemon
        working-directory: mobile/src/main/rust/vpnhotspotd
        run: cargo check --locked --all-targets

      - name: Test Rust daemon library
        working-directory: mobile/src/main/rust/vpnhotspotd
        run: cargo test --locked --lib

      - name: Run Rust daemon clippy
        working-directory: mobile/src/main/rust/vpnhotspotd
        run: |
          mkdir -p ../../../../build/reports
          cargo clippy --locked --all-targets -- -D warnings 2>&1 | tee ../../../../build/reports/clippy.log
          test "${PIPESTATUS[0]}" -eq 0

      - name: Run build and tests
        run: |
          mkdir -p mobile/build/reports
          ./gradlew assembleDebug check --no-daemon 2>&1 | tee mobile/build/reports/gradle.log
          test "${PIPESTATUS[0]}" -eq 0

      - name: Install cargo-audit
        run: cargo install cargo-audit --locked

      - name: Audit Rust daemon dependencies
        working-directory: mobile/src/main/rust/vpnhotspotd
        run: cargo audit

      - name: Verify release R8 coroutine debug
        run: ./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon

      - name: Upload APKs
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: apk
          path: mobile/build/outputs/apk

      - name: Upload reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: reports
          path: mobile/build/reports
''')
Path(__file__).unlink()
print("Track C patch applied; workflow restored; patcher removed")
