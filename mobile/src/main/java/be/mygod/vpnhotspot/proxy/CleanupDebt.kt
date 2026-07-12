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
     * R8 blocker #2: non-throwing typed conflict resolution.
     *
     * Firewall handle conflict (both non-null, different): drop both handles and
     * set [daemonCleanPending]. The daemon must re-sanitize before any new IPC
     * against either handle. Conflict details are recorded as [CleanupFailure]s.
     *
     * Service handle conflict (both non-null, different): keep the existing
     * handle and set [featureStopPending] so the full service is torn down. The
     * stale handle will produce a no-op or stale-handle failure on the next retry,
     * which is safe and observable.
     *
     * No throws: the invariant violation is recorded in [failures] rather than
     * propagated. This ensures that [applied] can always be cleared atomically
     * after the global debt merge (see [ProxyOnlyController.cleanupApplied]).
     */
    fun mergeUnresolved(other: CleanupDebt): CleanupDebt {
        val firewallConflict = firewallHandlePending != null &&
            other.firewallHandlePending != null &&
            firewallHandlePending != other.firewallHandlePending
        val serviceConflict = serviceHandlePending != null &&
            other.serviceHandlePending != null &&
            serviceHandlePending != other.serviceHandlePending

        val conflictFailures = buildList {
            if (firewallConflict) add(
                CleanupFailure(
                    "firewall_handle_conflict",
                    IllegalStateException(
                        "conflicting firewall handles: $firewallHandlePending " +
                            "vs ${other.firewallHandlePending} — dropped both, daemonCleanPending"
                    )
                )
            )
            if (serviceConflict) add(
                CleanupFailure(
                    "service_handle_conflict",
                    IllegalStateException(
                        "conflicting service handles: $serviceHandlePending " +
                            "vs ${other.serviceHandlePending} — featureStopPending set"
                    )
                )
            )
        }

        return copy(
            listenerClosePending = listenerClosePending || other.listenerClosePending,
            // Service conflict: keep existing handle; featureStopPending forces teardown.
            serviceHandlePending = serviceHandlePending ?: other.serviceHandlePending,
            // Firewall conflict: drop both; no IPC, re-sanitation required.
            firewallHandlePending = if (firewallConflict) null
                                    else firewallHandlePending ?: other.firewallHandlePending,
            firewallDenyPending = if (firewallConflict) false
                                  else firewallDenyPending || other.firewallDenyPending,
            firewallStopPending = if (firewallConflict) false
                                  else firewallStopPending || other.firewallStopPending,
            daemonCleanPending = daemonCleanPending || other.daemonCleanPending || firewallConflict,
            featureStopPending = featureStopPending || other.featureStopPending || serviceConflict,
            serviceWasActivated = serviceWasActivated || other.serviceWasActivated,
            failures = failures + other.failures + conflictFailures,
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
