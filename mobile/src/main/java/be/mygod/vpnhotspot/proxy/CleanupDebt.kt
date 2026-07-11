package be.mygod.vpnhotspot.proxy

// ---------------------------------------------------------------------------
// Step 5 — Applied state and itemized cleanup debt
// Sketch: docs/proxy-only/sketches/05-cleanup-debt-model.md
//
// Resolution invariants (Step 10 §D):
//   • Successful backend stop    → resolves SERVICE_HANDLE + LISTENER
//   • Emergency listener close   → resolves LISTENER only (handle retained)
//   • Successful firewall stop   → resolves FIREWALL_RUNTIME + FIREWALL_DENY
//   • Deny success alone         → does NOT resolve FIREWALL_RUNTIME
//   • Debt merge                 → fresh generation, union of unresolved resources
//   • New-epoch sanitation       → dominates all firewall handles whose epoch
//     is strictly less than the new sanitation epoch, regardless of daemon generation.
//
// R5: serviceWasActivated — records whether the service foreground activation
//     was issued, so cleanup-scope retries know whether service IPC is valid
//     even after the controller worker has exited.
//
// R6: epoch-qualified firewall handle provenance.
//     ProxyFirewallHandle now carries (epoch, id). The epoch is set by the
//     controller to the sanitizedEpoch returned by cleanOrDenyBeforeRestart().
//     A handle is stale iff its epoch is strictly less than the current
//     sanitizedEpoch. firewallHandleGeneration (daemon-generation proxy) is
//     removed; epoch comparison covers same-generation pre-sanitation handles.
// ---------------------------------------------------------------------------

/**
 * Tracks one active proxy runtime.
 *
 * [firewall] carries epoch provenance via [ProxyFirewallHandle.epoch]; the
 * separate [firewallDaemonGeneration] field from R5 is removed.
 */
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
    /**
     * Non-null only when the handle belongs to the current sanitation epoch and
     * the firewall runtime has not been stopped. The epoch is embedded in the
     * handle ([ProxyFirewallHandle.epoch]); callers compare it against
     * [ProxyOnlyController.sanitizedEpoch] to detect stale handles.
     *
     * Handles from prior epochs are NOT carried here; they are resolved by
     * sanitation dominance in [ProxyOnlyController.cleanupApplied] and
     * [ProxyOnlyController.retryCleanupDebtSafely].
     */
    val firewallHandlePending: ProxyFirewallHandle?,
    val firewallDenyPending: Boolean,
    val firewallStopPending: Boolean,
    val daemonCleanPending: Boolean,
    val featureStopPending: Boolean,
    /** R5: see top-level comment. */
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
     *
     * Conflicting live handles with different epochs represent runtimes from
     * distinct sanitation epochs and are a fatal invariant violation — the
     * daemon must be sanitized before a new runtime is started.
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
        ) {
            "conflicting live firewall handles (epoch mismatch or different ids): " +
                "$firewallHandlePending vs ${other.firewallHandlePending}"
        }

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
