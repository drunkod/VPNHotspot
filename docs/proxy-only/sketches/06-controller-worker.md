# Step 6 — Controller worker: reconciliation, cleanup, debt retry and terminal stop

Task: the complete `ProxyOnlyController` — a single serialized, exception-safe worker
with activation-grant gating, typed probe handling, itemized cleanup, self-triggered
debt retry and terminal shutdown.
Maps to: Implementation plan Phase 3 (serialized controller).
Depends on: [Step 1](01-models-and-state.md), [Step 2](02-vpn-selection.md),
[Step 3](03-service-and-backend-ownership.md), [Step 4](04-probes.md),
[Step 5](05-cleanup-debt-model.md).

The class body is presented in order; all fragments below belong to one
`ProxyOnlyController` class.

## 6.1 Controller and retry scheduler

```kotlin
class ProxyOnlyController(
    private val service: ProxyServiceClient,
    private val firewall: ProxyFirewallClient,
    private val activationGrants: ActivationGrantConsumer,
    private val stateSink: ProxyStateSink,
    private val reporter: ProxyErrorReporter,
    private val scope: CoroutineScope,
) {
    private val events = Channel<ControllerEvent>(Channel.CONFLATED)
    private var latestSnapshot: DesiredProxyState? = null
    private var serviceActivated = false
    private var applied: AppliedProxyState? = null
    private var cleanupDebt: CleanupDebt? = null
    private var retryJob: Job? = null
    private var nextDebtGeneration = 1L

    fun start(source: Flow<DesiredProxyState>): Job = scope.launch {
        val collector = launch {
            source.collect { snapshot ->
                val normalized = snapshot.normalized()
                latestSnapshot = normalized
                events.send(ControllerEvent.Snapshot(normalized))
            }
        }
        try {
            for (event in events) runIteration(event)
        } finally {
            collector.cancel()
            retryJob?.cancel()
            withContext(NonCancellable) {
                terminalStopSafely("controller worker terminated")
            }
        }
    }

    private suspend fun runIteration(event: ControllerEvent) {
        try {
            withContext(NonCancellable) {
                withTimeout(TRANSACTION_TIMEOUT) {
                    when (event) {
                        is ControllerEvent.Snapshot -> reconcile(event.state)
                        is ControllerEvent.RetryCleanupDebt -> retryDebtEvent(event)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            withContext(NonCancellable) { recoverSafely(timeout) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) { recoverSafely(failure) }
        }
    }

    private suspend fun retryDebtEvent(event: ControllerEvent.RetryCleanupDebt) {
        val debt = cleanupDebt ?: return
        if (debt.generation != event.generation) return
        retryJob = null
        retryCleanupDebtSafely()
        val unresolved = cleanupDebt
        if (unresolved != null) {
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }
        latestSnapshot?.let { reconcile(it) }
    }
```

## 6.2 Reconciliation with activation grant and typed probes

```kotlin
    private suspend fun reconcile(next: DesiredProxyState) {
        if (!next.settings.enabled) {
            terminalStopSafely("disabled")
            publishSafely(ProxyOnlyState.Disabled)
            return
        }

        if (!serviceActivated) {
            val grant = next.activationGrant
            if (grant == null) {
                publishSafely(ProxyOnlyState.ActivationRequired)
                return
            }
            publishSafely(ProxyOnlyState.ServiceStarting)
            when (service.activateFeature(grant, ProxyOnlyState.ServiceStarting)) {
                ServiceActivation.Active -> {
                    serviceActivated = true
                    activationGrants.consume(grant.id)
                }
                ServiceActivation.ForegroundStartNotAllowed -> {
                    publishSafely(ProxyOnlyState.ActivationRequired)
                    return
                }
            }
        }

        cleanupDebt?.let { debt ->
            retryCleanupDebtSafely()
            cleanupDebt?.let { unresolved ->
                enterWaitingIfActive(debtState(unresolved))
                publishDebt(unresolved)
                scheduleDebtRetry(unresolved)
                return
            }
        }

        if (next.downstreams.isEmpty()) {
            enterWaiting(ProxyOnlyState.WaitingForTethering, "no tethering")
            return
        }

        val upstream = when (val selection = next.vpnSelection) {
            VpnSelection.None -> {
                enterWaiting(ProxyOnlyState.WaitingForVpn, "no VPN")
                return
            }
            is VpnSelection.Multiple -> {
                enterWaiting(
                    ProxyOnlyState.MultipleVpnCandidates(selection.candidates.size),
                    "multiple VPNs",
                )
                return
            }
            is VpnSelection.One -> selection.upstream
        }

        if (!next.daemonHealthy) {
            val outcome = cleanupApplied("daemon unavailable", daemonAvailable = false)
            mergeDebt(outcome.debt)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(FailClosedReason.RootDaemonUnavailable)
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
            return
        }

        val key = next.runtimeKey(upstream)
        val current = applied
        if (current?.complete == true && current.key == key) {
            firewall.replace(current.firewall!!, next.firewallConfig(denyAll = false))
            service.replaceAcl(current.service!!, next.allowedClients)
            publishSafely(next.runningState())
            return
        }

        val old = cleanupApplied("runtime key changed", daemonAvailable = true)
        mergeDebt(old.debt)
        cleanupDebt?.let { unresolved ->
            enterWaitingIfActive(debtState(unresolved))
            publishDebt(unresolved)
            scheduleDebtRetry(unresolved)
            return
        }

        publishSafely(ProxyOnlyState.StartingBackend)
        val partial = AppliedProxyState(key)
        applied = partial

        partial.firewall = firewall.start(next.firewallConfig(denyAll = true))
        partial.service = service.startBackend(next.backendConfig(upstream))

        val requirements = ProbeRequirements(udpRequired = next.settings.udpEnabled)
        val report = service.runOutboundProbes(partial.service!!, requirements)
        when (val evaluation = report.evaluate(requirements)) {
            ProbeEvaluation.Success -> Unit
            ProbeEvaluation.VpnPermissionDenied -> {
                transitionAfterExpectedProbeFailure(ProxyOnlyState.VpnPermissionDenied)
                return
            }
            is ProbeEvaluation.TcpFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.TcpProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.UdpFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.UdpProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.DnsFailed -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.DnsProbeFailed(evaluation.failure.toString())
                    )
                )
                return
            }
            is ProbeEvaluation.ListenerNotReady,
            is ProbeEvaluation.Incomplete -> {
                transitionAfterExpectedProbeFailure(
                    ProxyOnlyState.FailClosed(
                        FailClosedReason.ListenerNotReady(evaluation.toString())
                    )
                )
                return
            }
        }

        firewall.replace(partial.firewall!!, next.firewallConfig(denyAll = false))
        partial.complete = true
        publishSafely(next.runningState())
    }
```

Typed expected probe failures are not converted to generic exceptions.

## 6.3 Expected probe failure transition

```kotlin
    private suspend fun transitionAfterExpectedProbeFailure(state: ProxyOnlyState) {
        val outcome = cleanupApplied("startup probe failed", daemonAvailable = true)
        mergeDebt(outcome.debt)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }
```

`VpnPermissionDenied` is now reachable and remains diagnosable.

## 6.4 Cleanup creation

```kotlin
    private suspend fun cleanupApplied(
        reason: String,
        daemonAvailable: Boolean,
    ): CleanupOutcome {
        val current = applied ?: return CleanupOutcome(
            CleanupReport.noOp("nothing applied"),
            debt = null,
        )
        applied = null
        val acc = CleanupAccumulator(reporter)

        var denyResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            denyResolved = acc.stepSucceeded("deny") {
                firewall.denyAll(current.firewall!!)
                CleanupReport.empty()
            }
        }

        var serviceResolved = current.service == null
        if (current.service != null && serviceActivated) {
            serviceResolved = acc.stepSucceeded("backend_stop") {
                service.stopBackend(current.service!!)
            }
        }

        var listenerResolved = serviceResolved
        if (!serviceResolved && serviceActivated) {
            listenerResolved = acc.stepSucceeded("emergency_close") {
                service.emergencyCloseListener(reason)
            }
        }

        var firewallResolved = current.firewall == null
        if (daemonAvailable && current.firewall != null) {
            firewallResolved = acc.stepSucceeded("firewall_stop") {
                firewall.stop(current.firewall!!)
                CleanupReport.empty()
            }
        }

        val debt = CleanupDebt(
            listenerClosePending = !listenerResolved,
            serviceHandlePending = current.service.takeUnless { serviceResolved },
            firewallHandlePending = current.firewall.takeUnless { firewallResolved },
            firewallDenyPending = current.firewall != null && !denyResolved,
            firewallStopPending = current.firewall != null && !firewallResolved,
            daemonCleanPending = current.firewall != null && !daemonAvailable,
            featureStopPending = false,
            failures = acc.failures,
            generation = nextDebtGeneration++,
            attempt = 0,
        ).takeUnless { it.isResolved }

        return CleanupOutcome(acc.report(), debt)
    }
```

Emergency close is never called when `applied == null`; idle snapshots cannot fabricate debt.

## 6.5 Item-by-item debt retry

```kotlin
    private suspend fun retryCleanupDebtSafely() {
        val original = cleanupDebt ?: return
        var debt = original
        val failures = mutableListOf<CleanupFailure>()

        suspend fun attempt(name: String, block: suspend () -> CleanupReport): Boolean {
            return try {
                val report = withTimeout(CLEANUP_STEP_TIMEOUT) { block() }
                failures += report.failures
                report.failures.isEmpty()
            } catch (failure: Throwable) {
                failures += CleanupFailure(name, failure)
                reportSafely("proxy.cleanup_debt.$name", failure)
                false
            }
        }

        var serviceHandle = debt.serviceHandlePending
        var listenerPending = debt.listenerClosePending
        if (serviceHandle != null && serviceActivated) {
            if (attempt("backend_stop") { service.stopBackend(serviceHandle!!) }) {
                serviceHandle = null
                listenerPending = false
            }
        }
        if (listenerPending && serviceActivated) {
            if (attempt("emergency_close") {
                    service.emergencyCloseListener("cleanup debt retry")
                }) {
                listenerPending = false
            }
        }

        var firewallHandle = debt.firewallHandlePending
        var denyPending = debt.firewallDenyPending
        var stopPending = debt.firewallStopPending
        val daemonHealthy = latestSnapshot?.daemonHealthy == true

        if (firewallHandle != null && daemonHealthy && denyPending) {
            if (attempt("deny") {
                    firewall.denyAll(firewallHandle!!)
                    CleanupReport.empty()
                }) {
                denyPending = false
            }
        }

        if (firewallHandle != null && daemonHealthy && stopPending) {
            if (attempt("firewall_stop") {
                    firewall.stop(firewallHandle!!)
                    CleanupReport.empty()
                }) {
                stopPending = false
                firewallHandle = null
            }
        }

        var daemonCleanPending = debt.daemonCleanPending
        if (daemonCleanPending && daemonHealthy) {
            if (attempt("daemon_clean") {
                    firewall.cleanOrDenyBeforeRestart().asCleanupReport()
                }) {
                daemonCleanPending = false
            }
        }

        var featureStopPending = debt.featureStopPending
        if (featureStopPending && serviceActivated) {
            if (attempt("feature_stop") { service.stopFeature("cleanup debt retry") }) {
                featureStopPending = false
                serviceActivated = false
            }
        }

        val updated = debt.copy(
            listenerClosePending = listenerPending,
            serviceHandlePending = serviceHandle,
            firewallHandlePending = firewallHandle,
            firewallDenyPending = denyPending,
            firewallStopPending = stopPending,
            daemonCleanPending = daemonCleanPending,
            featureStopPending = featureStopPending,
            failures = failures,
            attempt = debt.attempt + 1,
        )
        cleanupDebt = updated.takeUnless { it.isResolved }
    }
```

A healthy-daemon `firewall_stop` failure cannot be cleared by listener closure. Its handle and stop obligation remain until retried successfully.

## 6.6 Self-triggered debt retries

```kotlin
    private fun scheduleDebtRetry(debt: CleanupDebt) {
        if (retryJob?.isActive == true) return
        val generation = debt.generation
        val delayMillis = cleanupBackoff(
            attempt = debt.attempt,
            baseMillis = 1_000,
            maxMillis = 60_000,
            jitterFraction = 0.20,
        )
        retryJob = scope.launch {
            delay(delayMillis)
            events.send(ControllerEvent.RetryCleanupDebt(generation))
        }
    }
```

This retry runs even when settings, VPN, tethering and client state are unchanged.

## 6.7 Waiting, recovery and terminal stop

```kotlin
    private suspend fun enterWaiting(state: ProxyOnlyState, reason: String) {
        val outcome = cleanupApplied(reason, daemonAvailable = true)
        mergeDebt(outcome.debt)
        val published = cleanupDebt?.let(::debtState) ?: state
        enterWaitingIfActive(published)
        publishSafely(published)
        cleanupDebt?.let(::scheduleDebtRetry)
    }

    private suspend fun enterWaitingIfActive(state: ProxyOnlyState) {
        if (serviceActivated) service.enterWaiting(state)
    }

    private suspend fun recoverSafely(original: Throwable) {
        try {
            val outcome = cleanupApplied("reconcile failure", daemonAvailable = true)
            mergeDebt(outcome.debt)
            reportSafely("proxy.reconcile", original, outcome.report.failures)
            val state = cleanupDebt?.let(::debtState)
                ?: ProxyOnlyState.FailClosed(
                    FailClosedReason.InternalFailure(original::class.java.simpleName)
                )
            enterWaitingIfActive(state)
            publishSafely(state)
            cleanupDebt?.let(::scheduleDebtRetry)
        } catch (recoveryFailure: Throwable) {
            reportSafely("proxy.recovery", recoveryFailure)
            if (serviceActivated) {
                try {
                    val emergency = service.emergencyCloseListener("recovery failure")
                    mergeDebt(emergency.debt)
                } catch (emergencyFailure: Throwable) {
                    mergeDebt(
                        CleanupDebt(
                            listenerClosePending = true,
                            serviceHandlePending = applied?.service,
                            firewallHandlePending = applied?.firewall,
                            firewallDenyPending = applied?.firewall != null,
                            firewallStopPending = applied?.firewall != null,
                            daemonCleanPending = true,
                            featureStopPending = false,
                            failures = listOf(
                                CleanupFailure("emergency_close", emergencyFailure)
                            ),
                            generation = nextDebtGeneration++,
                            attempt = 0,
                        )
                    )
                }
            }
            cleanupDebt?.let {
                publishDebt(it)
                scheduleDebtRetry(it)
            }
        }
    }

    private suspend fun terminalStopSafely(reason: String) {
        val outcome = cleanupApplied(reason, daemonAvailable = latestSnapshot?.daemonHealthy == true)
        mergeDebt(outcome.debt)

        if (serviceActivated) {
            val featureReport = try {
                service.stopFeature(reason)
            } catch (failure: Throwable) {
                CleanupReport.failure("feature_stop", failure)
            }
            if (featureReport.failures.isEmpty()) {
                serviceActivated = false
            } else {
                mergeDebt(
                    CleanupDebt(
                        listenerClosePending = false,
                        serviceHandlePending = null,
                        firewallHandlePending = null,
                        firewallDenyPending = false,
                        firewallStopPending = false,
                        daemonCleanPending = false,
                        featureStopPending = true,
                        failures = featureReport.failures,
                        generation = nextDebtGeneration++,
                        attempt = 0,
                    )
                )
            }
        }
        cleanupDebt?.let(::scheduleDebtRetry)
    }
```

`stopFeature` has one owner: `terminalStopSafely`.

## 6.8 Debt merge and observable state

```kotlin
    private fun mergeDebt(newDebt: CleanupDebt?) {
        if (newDebt == null) return
        cleanupDebt = cleanupDebt?.merge(newDebt) ?: newDebt
    }

    private fun debtState(debt: CleanupDebt) = ProxyOnlyState.CleanupDegraded(
        unresolved = debt.unresolved,
        failures = debt.failures,
        retryAttempt = debt.attempt,
    )

    private suspend fun publishDebt(debt: CleanupDebt) {
        publishSafely(debtState(debt))
    }

    private suspend fun publishSafely(state: ProxyOnlyState) {
        try {
            stateSink.publish(state)
        } catch (failure: Throwable) {
            reportSafely("proxy.state_publish", failure)
        }
    }

    private fun reportSafely(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure> = emptyList(),
    ) {
        try {
            reporter.report(category, failure, cleanupFailures)
        } catch (_: Throwable) {
            // Last-resort platform logging must not throw into the worker.
        }
    }
}
```
