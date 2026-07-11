package be.mygod.vpnhotspot.proxy

// ---------------------------------------------------------------------------
// Step 5 — Applied state and itemized cleanup debt
// Sketch: docs/proxy-only/sketches/05-cleanup-debt-model.md
//
// Resolution invariants (Step 10 §D):
//   • Successful backend stop  → resolves SERVICE_HANDLE + LISTENER
//   • Emergency listener close → resolves LISTENER only (handle retained)
//   • Successful firewall stop → resolves FIREWALL_RUNTIME + FIREWALL_DENY
//   • Deny success alone       → does NOT resolve FIREWALL_RUNTIME
//   • Debt merge               → fresh generation, union of unresolved resources
//
// R4 additions:
//   • serviceWasActivated: required by ProxyCleanupSupervisor to know whether
//     stopBackend / emergencyCloseListener / stopFeature calls are meaningful.
//     Set to true in any debt created while serviceActivated=true; merged with OR.
// ---------------------------------------------------------------------------

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
    /**
     * R4: True when [ProxyService.activateFeature] was called and not yet matched
     * by a successful [stopFeature]. Required by [ProxyCleanupSupervisor] so it
     * knows whether service-layer cleanup calls are meaningful after the
     * controller worker has exited.
     */
    val serviceWasActivated: Boolean,
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

    /**
     * Merge [other] into this debt, preserving concrete surviving handles.
     * Conflicting live handles (two non-equal non-null handles) are fatal invariant
     * violations — callers must perform daemon Clean before restart.
     *
     * The controller assigns a fresh generation after merge (Step 10 §B).
     */
    fun mergeUnresolved(other: CleanupDebt): CleanupDebt {
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
            serviceWasActivated = serviceWasActivated || other.serviceWasActivated,
            failures = failures + other.failures,
            attempt = 0,
            // Fresh generation assigned by the controller after merge.
            generation = generation,
        )
    }
}

data class CleanupOutcome(
    val report: CleanupReport,
    val debt: CleanupDebt?,
)
