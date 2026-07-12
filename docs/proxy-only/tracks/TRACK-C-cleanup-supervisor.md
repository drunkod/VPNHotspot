# Track C — service-owned cleanup supervisor + authoritative service state

**Goal:** replace the abstract `cleanupScope` lifetime *contract* with a concrete,
service-owned supervisor that provably outlives the controller worker, survives the
recovery window, and is cancelled at final teardown. Make `serviceWasActivated`
authoritative retry state instead of relying on the mutable controller-local
`serviceActivated`.

Today `ProxyOnlyController` takes `cleanupScope: CoroutineScope` as a constructor arg
with only a kdoc promise ("MUST outlive [scope]"). Nothing proves an owner exists, and
`CleanupDebt.serviceWasActivated` is carried but not used as the source of truth for
retry eligibility — process/service state drift can strand service debt.

**Blocker refs:** R10 §5 (persistent cleanup ownership), R11 audit row "Persistent
cleanup ownership — Not fixed", R12 audit row "Persistent cleanup owner — Not fixed".

**Files touched:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyCleanupSupervisor.kt   (new)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyService.kt             (owner; may be stubbed for Phase 0)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyController.kt      (consume supervisor + authoritative state)
mobile/src/test/java/be/mygod/vpnhotspot/proxy/CleanupSupervisorLifecycleTest.kt (new)
```

> For Phase 0 (fakes, no real FGS yet) the "owner" can be a test/application-scoped
> holder. The point is that the supervisor is a *named, owned object with an explicit
> lifecycle*, not an opaque scope handed in from nowhere.

---

## Step C1 — the supervisor abstraction

```kotlin
package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.*

/**
 * Owns the coroutine scope in which terminal cleanup-debt retries execute.
 *
 * Lifecycle contract, now enforced by construction rather than by kdoc:
 *   - created by and tied to the foreground-service (or application) lifecycle;
 *   - guaranteed to outlive any controller worker scope it hands out;
 *   - cancelled exactly once, at authoritative final teardown, via [shutdown].
 *
 * The controller NEVER constructs this; it receives one and may only launch
 * retry work through [retryScope].
 */
class ProxyCleanupSupervisor private constructor(
    private val supervisorJob: CompletableJob,
    val retryScope: CoroutineScope,
) {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)

    val isActive: Boolean get() = active.get()

    /** Cancel all in-flight retries; idempotent. Only the owner may call this. */
    fun shutdown(reason: String) {
        if (active.compareAndSet(true, false)) {
            supervisorJob.cancel(CancellationException("cleanup supervisor shutdown: $reason"))
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
```

`SupervisorJob` ensures a single failed retry does not cancel sibling retries; the
owner's `shutdown()` is the only path that tears it down.

---

## Step C2 — the owner (ProxyService, or Phase-0 application holder)

```kotlin
// ProxyService.kt (real owner once the FGS exists)
class ProxyService : Service() {
    // Tied to the service lifecycle; outlives every controller worker.
    private val cleanupSupervisor =
        ProxyCleanupSupervisor.create(parent = lifecycleScope.coroutineContext)

    fun newController(): ProxyOnlyController = ProxyOnlyController(
        // ... existing deps ...
        cleanupSupervisor = cleanupSupervisor,
    )

    override fun onDestroy() {
        // Authoritative final teardown: the ONE place the supervisor is cancelled.
        cleanupSupervisor.shutdown("service destroyed")
        super.onDestroy()
    }
}
```

For Phase 0 tests, an `ApplicationCleanupOwner` (or the test itself) plays this role —
what matters is that a distinct object owns creation and `shutdown()`.

---

## Step C3 — controller consumes the supervisor, not a raw scope

Replace the `cleanupScope: CoroutineScope` constructor param with the supervisor and
route retry launches through it:

```kotlin
class ProxyOnlyController(
    // ...
    private val cleanupSupervisor: ProxyCleanupSupervisor,
) {
    private fun scheduleDebtRetry(debt: CleanupDebt) {
        if (!cleanupSupervisor.isActive) {
            reporter.report("cleanup supervisor inactive; debt retained but not scheduled", debt)
            return
        }
        if (retryJob?.isActive == true && scheduledRetryGeneration == debt.generation) return
        retryJob?.cancel()
        scheduledRetryGeneration = debt.generation
        retryJob = cleanupSupervisor.retryScope.launch {
            delay(cleanupBackoff(debt.attempt))
            events.send(ControllerEvent.RetryCleanupDebt(debt.generation))
        }
    }
}
```

Because `retryScope` is owned by the service, terminal debt keeps retrying after the
controller worker's own `scope` is cancelled — which is the whole reason the two scopes
were separated.

---

## Step C4 — make `serviceWasActivated` authoritative

`CleanupDebt.serviceWasActivated` already exists (`CleanupDebt.kt` line 85) but retry
eligibility currently reads the mutable `serviceActivated` field. Decide service-IPC
eligibility from the **debt's own captured flag**, so a retry running after worker exit
(or across process/service drift) still knows service IPC is valid.

```kotlin
private suspend fun retryCleanupDebtSafely() {
    val debt = cleanupDebt ?: return
    // Authoritative: was the service ever activated for THIS debt? (captured at creation)
    val serviceIpcValid = debt.serviceWasActivated
    // ...
    if (debt.serviceHandlePending != null && serviceIpcValid) {
        val report = service.stopBackend(debt.serviceHandlePending)
        // ...
    }
    if (debt.featureStopPending && serviceIpcValid && noConcreteControllerHandleRemains()) {
        val report = service.stopFeature("terminal teardown")
        // ...
    }
    // If !serviceIpcValid, service items are treated as already-void (no live service),
    // never as retriable-forever debt that can strand.
}
```

Whenever debt is created, capture the flag from real activation state:

```kotlin
val debt = CleanupDebt(
    // ...
    serviceWasActivated = serviceActivated,  // captured at creation time, immutable thereafter
)
```

---

## Step C5 — lifecycle tests

```kotlin
class CleanupSupervisorLifecycleTest {
    @Test fun retryRunsAfterControllerWorkerScopeCancelled() = runTest {
        val supervisor = ProxyCleanupSupervisor.create(parent = coroutineContext)
        val workerScope = CoroutineScope(coroutineContext + Job())
        val controller = newController(scope = workerScope, cleanupSupervisor = supervisor)
        controller.seedUnresolvedDebt()

        workerScope.cancel("worker done")     // controller worker exits
        advanceUntilRetryFires()

        assertTrue("retry still executed via service-owned scope",
            controller.retryAttemptedForTest())
    }

    @Test fun shutdownCancelsInFlightRetries() = runTest {
        val supervisor = ProxyCleanupSupervisor.create(parent = coroutineContext)
        val controller = newController(cleanupSupervisor = supervisor)
        controller.seedUnresolvedDebt()

        supervisor.shutdown("teardown")
        advanceTimeBy(LONG)

        assertFalse(controller.retryAttemptedForTest())
    }

    @Test fun serviceDebtWithServiceWasActivatedFalse_doesNotStrand() = runTest {
        // debt.serviceWasActivated=false → service items resolve as void, debt clears
    }

    @Test fun serviceDebtWithServiceWasActivatedTrue_retriesServiceIpc() = runTest {
        // debt.serviceWasActivated=true → stopBackend/stopFeature attempted on retry
    }
}
```

## Acceptance criteria

- `cleanupScope: CoroutineScope` constructor param is gone; replaced by
  `ProxyCleanupSupervisor` with an explicit owner and single `shutdown()` site.
- Terminal debt retries survive controller-worker cancellation and stop after
  `shutdown()`.
- Retry service-IPC eligibility is driven by `debt.serviceWasActivated`, not the
  mutable `serviceActivated`.
- No service/listener debt can become permanently unreachable due to state drift.
- New lifecycle tests + existing suite green.

## Dependencies

- Independent of Track B; can land in parallel.
- Coordinates with Track A's teardown-dominance tests (shared fakes).
