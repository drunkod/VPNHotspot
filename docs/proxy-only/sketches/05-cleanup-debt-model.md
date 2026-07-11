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

## 5.3 Resolution and merge invariants

- A successful backend stop resolves both `SERVICE_HANDLE` and `LISTENER`.
- Emergency listener closure resolves only `LISTENER`; it does not discard the service handle.
- A successful firewall-runtime stop resolves both `FIREWALL_RUNTIME` and any pending
  `FIREWALL_DENY`, because no live proxy rule set remains to deny.
- A successful deny does not resolve `FIREWALL_RUNTIME`.
- Debt merge creates a fresh generation, unions unresolved resources and preserves the
  concrete surviving handles. A conflicting pair of non-equal live handles is a fatal
  invariant violation and requires daemon Clean before restart.
- Changing debt generation cancels/replaces any retry scheduled for the previous generation.
- Cold process start performs daemon sanitation even when no in-memory debt exists.

A sketch for merge semantics:

```kotlin
fun CleanupDebt.mergeUnresolved(other: CleanupDebt): CleanupDebt {
    require(
        serviceHandlePending == null ||
            other.serviceHandlePending == null ||
            serviceHandlePending == other.serviceHandlePending
    ) { "conflicting live service handles" }
    require(
        firewallHandlePending == null ||
            other.firewallHandlePending == null ||
            firewallHandlePending == other.firewallHandlePending
    ) { "conflicting live firewall handles" }

    return copy(
        listenerClosePending = listenerClosePending || other.listenerClosePending,
        serviceHandlePending = serviceHandlePending ?: other.serviceHandlePending,
        firewallHandlePending = firewallHandlePending ?: other.firewallHandlePending,
        firewallDenyPending = firewallDenyPending || other.firewallDenyPending,
        firewallStopPending = firewallStopPending || other.firewallStopPending,
        daemonCleanPending = daemonCleanPending || other.daemonCleanPending,
        featureStopPending = featureStopPending || other.featureStopPending,
        failures = failures + other.failures,
        attempt = 0,
        // The controller assigns a fresh generation after merge.
        generation = generation,
    )
}
```

If a firewall-runtime stop succeeds, callers must clear both `firewallStopPending` and
`firewallDenyPending`. If backend stop succeeds, callers clear both the service handle
and listener uncertainty.
