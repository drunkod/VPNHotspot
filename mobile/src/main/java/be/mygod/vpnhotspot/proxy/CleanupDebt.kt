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
//   • New-daemon sanitation    → dominates all firewall debt from older generations
//     (service/listener debt is independent and never dominated by sanitation)
//
// R4: serviceWasActivated tracks whether the service foreground activation
//     was issued, so cleanup-scope retries know whether service IPC is valid.
// R5: firewallHandleGeneration tracks which daemon generation issued the
//     firewall handle so that new-daemon sanitation can dominate stale handles
//     without making IPC calls against the new daemon.
// ---------------------------------------------------------------------------

/**
 * Tracks one active proxy runtime.
 *
 * [firewallDaemonGeneration] is the daemon generation recorded when [firewall]
 * was created. A change in the controller's [sanitizedDaemonGeneration] means
 * the new daemon's sanitation dominates this handle; the controller must not
 * submit it to the new daemon.
 */
data class AppliedProxyState(
    val key: RuntimeKey,
    var firewall: ProxyFirewallHandle? = null,
    var service: ProxyServiceHandle? = null,
    var complete: Boolean = false,
    /**
     * R5: daemon generation that issued [firewall].
     * 0L until the handle is created (matches no real daemon generation).
     */
    val firewallDaemonGeneration: Long = 0L,
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
     * by a successful [stopFeature]. Required by cleanup-scope retries so that
     * service-layer cleanup calls remain valid after the controller worker exits.
     */
    val serviceWasActivated: Boolean,
    /**
     * R5: daemon generation that issued [firewallHandlePending].
     * Null when [firewallHandlePending] is null or the generation is unknown.
     *
     * When this differs from the controller's current [sanitizedDaemonGeneration]
     * AND a sanitation has completed ([sanitizedDaemonGeneration] is non-null),
     * the handle is stale and new-daemon sanitation dominates: firewall debt can
     * be marked resolved without any IPC call against the new daemon.
     */
    val firewallHandleGeneration: Long?,
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
        ) { "conflicting live service handles: $serviceHandlePending vs ${other.serviceHandlePending}" }
        require(
            firewallHandlePending == null ||
                other.firewallHandlePending == null ||
                firewallHandlePending == other.firewallHandlePending
        ) { "conflicting live firewall handles: $firewallHandlePending vs ${other.firewallHandlePending}" }

        return copy(
            listenerClosePending = listenerClosePending || other.listenerClosePending,
            serviceHandlePending = serviceHandlePending ?: other.serviceHandlePending,
            firewallHandlePending = firewallHandlePending ?: other.firewallHandlePending,
            firewallDenyPending = firewallDenyPending || other.firewallDenyPending,
            firewallStopPending = firewallStopPending || other.firewallStopPending,
            daemonCleanPending = daemonCleanPending || other.daemonCleanPending,
            featureStopPending = featureStopPending || other.featureStopPending,
            serviceWasActivated = serviceWasActivated || other.serviceWasActivated,
            // R5: take the generation of whichever side owns the handle.
            firewallHandleGeneration = firewallHandlePending?.let { firewallHandleGeneration }
                ?: other.firewallHandlePending?.let { other.firewallHandleGeneration },
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
