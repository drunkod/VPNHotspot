# Step 5 — Applied state and itemized cleanup debt

Task: model applied resources and per-resource cleanup debt so a partial cleanup
failure blocks restart until every unresolved item is actually resolved.
Maps to: Implementation plan Phase 3 (transaction safety).
Depends on: [Step 1](01-models-and-state.md) models.
Consumed by: [Step 6](06-controller-worker.md).

## 5.1 Applied state and itemized cleanup debt

```kotlin
data class AppliedProxyState(
    val key: RuntimeKey,
    var firewall: ProxyFirewallHandle? = null,
    var service: ProxyServiceHandle? = null,
    var complete: Boolean = false,
)

enum class CleanupResource {
    LISTENER,
    SERVICE_HANDLE,
    FIREWALL_DENY,
    FIREWALL_RUNTIME,
    DAEMON_CLEAN,
    FEATURE_SERVICE,
}

data class CleanupDebt(
    val listenerClosePending: Boolean,
    val serviceHandlePending: ProxyServiceHandle?,
    val firewallHandlePending: ProxyFirewallHandle?,
    val firewallDenyPending: Boolean,
    val firewallStopPending: Boolean,
    val daemonCleanPending: Boolean,
    val featureStopPending: Boolean,
    val failures: List<CleanupFailure>,
    val generation: Long,
    val attempt: Int,
) {
    val unresolved: Set<CleanupResource> = buildSet {
        if (listenerClosePending) add(CleanupResource.LISTENER)
        if (serviceHandlePending != null) add(CleanupResource.SERVICE_HANDLE)
        if (firewallDenyPending) add(CleanupResource.FIREWALL_DENY)
        if (firewallStopPending || firewallHandlePending != null) {
            add(CleanupResource.FIREWALL_RUNTIME)
        }
        if (daemonCleanPending) add(CleanupResource.DAEMON_CLEAN)
        if (featureStopPending) add(CleanupResource.FEATURE_SERVICE)
    }

    val isResolved: Boolean get() = unresolved.isEmpty()
}
```

No backend or new firewall runtime may start while debt exists.

## 5.2 Cleanup accumulator result

```kotlin
data class CleanupOutcome(
    val report: CleanupReport,
    val debt: CleanupDebt?,
)
```

`CleanupAccumulator`:

- applies a timeout to each step;
- attempts all eligible steps;
- records whether each specific resource was resolved;
- merges nested service reports;
- never marks a resource resolved because another resource succeeded;
- returns itemized debt.
