# Step 3 — Service/backend ownership and the persistent foreground service

Task: make `ProxyService` the sole backend owner with defined pre-activation behavior;
keep the FGS alive, listener-free, through waiting and fail-closed states.
Maps to: Implementation plan Phase 2 (foreground-service gate) and Phase 4 (ProxyService).
Depends on: [Step 1](01-models-and-state.md) models.
Consumed by: [Step 6](06-controller-worker.md).

## 3.1 Service/backend ownership contracts

```kotlin
interface ProxyServiceClient {
    suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation

    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport
    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle
    suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyServiceHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport
    suspend fun emergencyCloseListener(reason: String): CleanupReport
    suspend fun stopFeature(reason: String): CleanupReport
    suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats
}

interface ProxyBackend {
    suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle
    suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>)
    suspend fun runOutboundProbes(
        handle: ProxyBackendHandle,
        requirements: ProbeRequirements,
    ): ProbeReport
    suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats
    suspend fun emergencyCloseListener(
        handle: ProxyBackendHandle,
        reason: String,
    ): CleanupReport
    suspend fun stop(handle: ProxyBackendHandle): CleanupReport
}
```

`ProxyOnlyController` never owns a backend/native handle. `ProxyService` is the sole backend owner.

Pre-activation behavior is defined:

```text
enterWaiting / stopBackend / emergencyCloseListener / stopFeature
  when service is inactive
  -> return CleanupReport.noOp(ServiceNotActive)
  -> do not throw
```

The controller still guards these calls with `serviceActivated`; the no-op contract is defense in depth.

## 3.2 Persistent service sketch

```kotlin
class ProxyService : Service() {
    private lateinit var backend: ProxyBackend
    private var backendHandle: ProxyBackendHandle? = null
    private var featureActive = false

    suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation {
        check(!featureActive)
        validateForegroundGrant(grant)
        startForeground(NOTIFICATION_ID, waitingNotification(initialState))
        featureActive = true
        return ServiceActivation.Active
    }

    suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("waiting: $state")
        updateNotification(waitingNotification(state))
        return report
    }

    suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle {
        check(featureActive)
        check(backendHandle == null)
        val handle = backend.start(config)
        backendHandle = handle
        return ProxyServiceHandle(handle.id)
    }

    suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        if (current.id != handle.id) return CleanupReport.staleHandle(handle.id)

        val report = backend.stop(current).withContext("ProxyService.stopBackend")
        // Never discard the only native handle before stop is proven complete.
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }

    suspend fun emergencyCloseListener(reason: String): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        // Emergency listener closure is fail-closed containment, not proof that the
        // backend handle and all native resources were destroyed.
        return backend.emergencyCloseListener(current, reason)
            .withContext("ProxyService.emergencyCloseListener")
    }

    suspend fun stopFeature(reason: String): CleanupReport {
        if (!featureActive) return CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("feature stop: $reason")
        if (report.hasCriticalFailure) return report
        featureActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return report
    }

    private suspend fun closeCurrentBackend(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        val report = backend.stop(current)
            .withContext("ProxyService.closeCurrentBackend", reason)
        if (!report.hasCriticalFailure) backendHandle = null
        return report
    }
}
```

After activation, the service remains foreground but listener-free in waiting/fail-closed states.

A failed stop retains the native handle for a later idempotent retry. Emergency listener
closure may establish fail-closed safety, but it never causes the service/backend handle
to be forgotten. Phase 0 must prove that both `stop()` and `emergencyCloseListener()` are
idempotent and that a stale/mismatched handle is reported as unresolved rather than
silently treated as success.
