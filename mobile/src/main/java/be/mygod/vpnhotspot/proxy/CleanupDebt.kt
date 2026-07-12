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
//   • New-epoch sanitation       → dominates all firewall handles whose session
//     and epoch are strictly dominated by the new (sessionId, epoch) pair.
//
// R5: serviceWasActivated — records whether the service foreground activation
//     was issued, so cleanup-scope retries know whether service IPC is valid
//     even after the controller worker has exited.
//
// R6/R7/R9: epoch-qualified, session-qualified firewall handle provenance.
//     ProxyFirewallHandle carries (sessionId, epoch, id), all returned by the
//     daemon in the start acknowledgement. The controller MUST NOT manufacture
//     sessionId or epoch locally. A handle is current iff its (sessionId, epoch)
//     exactly matches the controller's (sanitizedSessionId, sanitizedEpoch).
//     A same-session lower epoch is dominated (sanitation covered it); a
//     different session or future epoch is a conflict requiring re-sanitation.
//     firewallHandleGeneration (daemon-generation proxy) was removed in R6;
//     the authoritative (sessionId, epoch) pair replaces it.
//
// R9: service-handle conflict resolution: both handles are discarded, listener
//     ownership is marked unknown, and featureStopPending forces authoritative
//     full service teardown. stopFeature() uses the service's own internal
//     backend handle, so it does not require the controller to hold a specific
//     service handle. Conflict failures are deduplicated per step name to bound
//     history growth under repeated merges.
// ---------------------------------------------------------------------------

/**
 * Tracks one active proxy runtime.
 *
 * [firewall] carries full provenance via [ProxyFirewallHandle.sessionId] and
 * [ProxyFirewallHandle.epoch], both issued by the daemon.
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
    /**
     * Non-null only when the controller holds a specific service handle that
     * needs to be stopped. Null when service identity is unknown/conflicted
     * (in which case [featureStopPending] forces a full authoritative teardown).
     */
    val serviceHandlePending: ProxyServiceHandle?,
    /**
     * Non-null only when the handle belongs to the current sanitation session/epoch
     * and the firewall runtime has not been stopped. The session and epoch are
     * embedded in the handle ([ProxyFirewallHandle.sessionId] / [.epoch]); callers
     * compare them against [ProxyOnlyController.sanitizedSessionId] /
     * [ProxyOnlyController.sanitizedEpoch] to detect stale handles.
     *
     * Handles from prior sessions/epochs are NOT carried here; they are resolved
     * by sanitation dominance in [ProxyOnlyController.cleanupApplied] and
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
     * R8/R9: non-throwing typed conflict resolution.
     *
     * Firewall handle conflict (both non-null, different): drop both handles and
     * set [daemonCleanPending]. The daemon must re-sanitize before any new IPC
     * against either handle. Conflict details are recorded as [CleanupFailure]s.
     *
     * Service handle conflict (both non-null, different): discard BOTH handles
     * (neither is authoritative), mark [listenerClosePending] true (ownership
     * unknown), and set [featureStopPending] for authoritative full teardown.
     * [ProxyService.stopFeature] uses the service's own internal backend handle
     * and does not require the controller to hold a specific [serviceHandlePending].
     * This breaks the R8 deadlock where retaining a stale handle permanently
     * blocked [featureStopPending]. Once authoritative feature teardown is pending,
     * later merges must not re-adopt a concrete service handle: doing so would
     * suppress stopFeature() behind a newly observed, non-authoritative handle.
     *
     * Conflict failures are deduplicated by step name: a given conflict type is
     * appended at most once, preventing unbounded history growth under repeated
     * merges of the same unresolved conflict (R9 amendment #6).
     *
     * No throws: invariant violations are recorded in [failures] rather than
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
        val authoritativeFeatureStop = featureStopPending || other.featureStopPending ||
            serviceConflict

        // Deduplicate: append a conflict failure only if not already present.
        val existingSteps = (failures + other.failures).map { it.step }.toHashSet()
        val conflictFailures = buildList {
            if (firewallConflict && "firewall_handle_conflict" !in existingSteps) add(
                CleanupFailure(
                    "firewall_handle_conflict",
                    IllegalStateException(
                        "conflicting firewall handles: $firewallHandlePending " +
                            "vs ${other.firewallHandlePending} — dropped both, daemonCleanPending"
                    )
                )
            )
            if (serviceConflict && "service_handle_conflict" !in existingSteps) add(
                CleanupFailure(
                    "service_handle_conflict",
                    IllegalStateException(
                        "conflicting service handles: $serviceHandlePending " +
                            "vs ${other.serviceHandlePending} — dropped both, " +
                            "listenerClosePending + featureStopPending set"
                    )
                )
            )
        }

        return copy(
            // Service conflict: mark listener unknown (two backends = unknown listener).
            listenerClosePending = listenerClosePending || other.listenerClosePending ||
                serviceConflict,
            // Authoritative feature teardown owns service cleanup. Never re-adopt a
            // concrete handle once that path is pending, even from a later merge.
            serviceHandlePending = if (authoritativeFeatureStop) null
                                   else serviceHandlePending ?: other.serviceHandlePending,
            // Firewall conflict: drop both; no IPC, re-sanitation required.
            firewallHandlePending = if (firewallConflict) null
                                    else firewallHandlePending ?: other.firewallHandlePending,
            firewallDenyPending = if (firewallConflict) false
                                  else firewallDenyPending || other.firewallDenyPending,
            firewallStopPending = if (firewallConflict) false
                                  else firewallStopPending || other.firewallStopPending,
            daemonCleanPending = daemonCleanPending || other.daemonCleanPending || firewallConflict,
            featureStopPending = authoritativeFeatureStop,
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
