# Step 6 addendum — mandatory post-split controller corrections

This addendum is normative for [Step 6](06-controller-worker.md). Apply every replacement
below before using the controller sketch in Phase 0. It exists separately so the
historical contiguous controller example and old section mapping remain reviewable.

## A. Thread-safe latest snapshot and daemon-generation sanitation

Use an atomic latest-snapshot reference because the source collector and resource
worker may execute on different threads:

```kotlin
private val latestSnapshot = AtomicReference<DesiredProxyState?>(null)
private var sanitizedDaemonGeneration: Long? = null

source.collect { raw ->
    val normalized = raw.normalized()
    latestSnapshot.set(normalized)
    events.send(ControllerEvent.Snapshot(normalized))
}
```

Before the first proxy-firewall runtime in each observed daemon generation:

```kotlin
if (!next.daemonHealthy || next.daemonGeneration == null) {
    sanitizedDaemonGeneration = null
    transitionDaemonUnavailable()
    return
}
if (sanitizedDaemonGeneration != next.daemonGeneration) {
    if (!firewall.cleanOrDenyBeforeRestart()) {
        mergeDebt(CleanupDebt.daemonCleanRequired("startup sanitation failed"))
        publishAndScheduleDebt()
        return
    }
    sanitizedDaemonGeneration = next.daemonGeneration
}
```

This gate is required even when in-memory cleanup debt is empty, because process death
can lose debt while kernel rules survive.

## B. Retry-generation ownership

Clear timer ownership before stale-generation checks:

```kotlin
private suspend fun retryDebtEvent(event: ControllerEvent.RetryCleanupDebt) {
    retryJob = null
    scheduledRetryGeneration = null

    val debt = cleanupDebt ?: return
    if (debt.generation != event.generation) {
        scheduleDebtRetry(debt)
        return
    }

    retryCleanupDebtSafely()
    cleanupDebt?.let {
        publishDebt(it)
        scheduleDebtRetry(it)
        return
    }
    latestSnapshot.get()?.let { reconcile(it) }
}
```

Scheduling a new generation cancels the old timer:

```kotlin
private fun scheduleDebtRetry(debt: CleanupDebt) {
    if (retryJob?.isActive == true &&
        scheduledRetryGeneration == debt.generation) return

    retryJob?.cancel()
    scheduledRetryGeneration = debt.generation
    retryJob = scope.launch {
        delay(cleanupBackoff(debt.attempt))
        events.send(ControllerEvent.RetryCleanupDebt(debt.generation))
    }
}
```

`mergeDebt` assigns a fresh generation and invalidates the prior timer:

```kotlin
private fun mergeDebt(incoming: CleanupDebt?) {
    if (incoming == null) return
    val merged = cleanupDebt?.mergeUnresolved(incoming) ?: incoming
    cleanupDebt = merged.copy(
        generation = nextDebtGeneration++,
        attempt = 0,
    )
    retryJob?.cancel()
    retryJob = null
    scheduledRetryGeneration = null
}
```

## C. Typed probe mapping

Handle all probe outcomes without `check()`:

```kotlin
when (val evaluation = report.evaluate(requirements)) {
    ProbeEvaluation.Success -> Unit
    ProbeEvaluation.VpnPermissionDenied ->
        transitionAfterExpectedProbeFailure(ProxyOnlyState.VpnPermissionDenied)
    is ProbeEvaluation.BindFailed ->
        transitionAfterExpectedProbeFailure(
            ProxyOnlyState.FailClosed(
                FailClosedReason.BindProbeFailed(evaluation.failure.toString())
            )
        )
    is ProbeEvaluation.TcpFailed -> transitionTcpFailure(evaluation)
    is ProbeEvaluation.UdpFailed -> transitionUdpFailure(evaluation)
    is ProbeEvaluation.DnsFailed -> transitionDnsFailure(evaluation)
    is ProbeEvaluation.ListenerNotReady ->
        transitionAfterExpectedProbeFailure(
            ProxyOnlyState.FailClosed(
                FailClosedReason.ListenerNotReady(evaluation.failure.toString())
            )
        )
    is ProbeEvaluation.Incomplete ->
        transitionAfterExpectedProbeFailure(
            ProxyOnlyState.FailClosed(
                FailClosedReason.ProbeReportIncomplete(evaluation.missing)
            )
        )
}
```

`Incomplete` never means listener failure.

## D. Cleanup dominance

When firewall-runtime stop succeeds, clear both runtime-stop and deny obligations:

```kotlin
if (firewallResolved) {
    denyResolved = true
}
```

The retry path follows the same rule:

```kotlin
if (firewall.stop(handle).succeeded) {
    firewallHandle = null
    stopPending = false
    denyPending = false
}
```

When backend stop succeeds, clear both the service handle and listener uncertainty.
Emergency listener closure clears only listener uncertainty.

## E. Daemon availability in waiting/recovery cleanup

Do not hardcode `daemonAvailable = true`. Pass the snapshot value into waiting cleanup
and read the atomic latest snapshot in unexpected-failure recovery:

```kotlin
enterWaiting(state, reason, daemonAvailable = next.daemonHealthy)

val outcome = cleanupApplied(
    "reconcile failure",
    daemonAvailable = latestSnapshot.get()?.daemonHealthy == true,
)
```

If the daemon is unavailable, retain firewall/daemon-Clean debt instead of pretending
the runtime was stopped.

## Phase 0 tests added by this addendum

- failed backend stop retains a retriable native handle;
- emergency close does not clear the backend handle;
- firewall stop success clears impossible deny-only debt;
- stale retry event cannot starve a newer debt generation;
- debt merge replaces the scheduled generation;
- cold process start sanitizes stale proxy chains before runtime start;
- non-permission bind failure is not mislabeled as listener failure;
- simultaneous VPN/daemon loss records daemon-Clean debt.
