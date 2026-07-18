package be.mygod.vpnhotspot.proxy

// ---------------------------------------------------------------------------
// Step 4 — Typed, configuration-aware outbound probes
// Sketch: docs/proxy-only/sketches/04-probes.md
//
// Probes are outbound-only. INTERNAL_LISTENER_READY reads native bind/listen
// status without traversing iptables. Permission denial, other bind failures,
// transport/DNS failures and missing reports are distinct typed outcomes.
// ---------------------------------------------------------------------------

enum class ProbeKind {
    APP_UID_BIND,
    OUTBOUND_TCP,
    OUTBOUND_UDP,
    VPN_DNS,
    INTERNAL_LISTENER_READY,
}

sealed interface ProbeFailure {
    data object PermissionDenied : ProbeFailure
    data class Timeout(val operation: String) : ProbeFailure
    data class NetworkError(val message: String) : ProbeFailure
    data class Internal(val message: String) : ProbeFailure
}

sealed interface ProbeResult {
    data object Success : ProbeResult
    data class Failed(val failure: ProbeFailure) : ProbeResult
}

/**
 * Which probes are required for this configuration.
 * UDP is only required when [udpRequired] is true (i.e. settings.udpEnabled).
 */
data class ProbeRequirements(val udpRequired: Boolean) {
    val requiredKinds: Set<ProbeKind> = buildSet {
        add(ProbeKind.APP_UID_BIND)
        add(ProbeKind.OUTBOUND_TCP)
        add(ProbeKind.VPN_DNS)
        add(ProbeKind.INTERNAL_LISTENER_READY)
        if (udpRequired) add(ProbeKind.OUTBOUND_UDP)
    }
}

data class ProbeReport(val results: Map<ProbeKind, ProbeResult>)

sealed interface ProbeEvaluation {
    data object Success : ProbeEvaluation
    /** APP_UID_BIND failed with PermissionDenied — VPN excludes this app. */
    data object VpnPermissionDenied : ProbeEvaluation
    /** APP_UID_BIND failed for a non-permission reason. */
    data class BindFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class TcpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class UdpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class DnsFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class ListenerNotReady(val failure: ProbeFailure) : ProbeEvaluation
    /** A required probe result is absent — not a synonym for listener failure. */
    data class Incomplete(val missing: Set<ProbeKind>) : ProbeEvaluation
}

/**
 * Evaluates the report against [requirements].
 *
 * Outcome priority (from sketch §4 and Step 10 §C):
 * 1. Missing required results → [ProbeEvaluation.Incomplete]
 * 2. Bind permission denial  → [ProbeEvaluation.VpnPermissionDenied]
 * 3. Other bind failure      → [ProbeEvaluation.BindFailed]
 * 4. TCP failure             → [ProbeEvaluation.TcpFailed]
 * 5. DNS failure             → [ProbeEvaluation.DnsFailed]
 * 6. Listener not ready      → [ProbeEvaluation.ListenerNotReady]
 * 7. UDP failure (if req.)   → [ProbeEvaluation.UdpFailed]
 * 8. All passed              → [ProbeEvaluation.Success]
 */
fun ProbeReport.evaluate(requirements: ProbeRequirements): ProbeEvaluation {
    val missing = requirements.requiredKinds - results.keys
    if (missing.isNotEmpty()) return ProbeEvaluation.Incomplete(missing)

    val bind = results.getValue(ProbeKind.APP_UID_BIND)
    if (bind is ProbeResult.Failed && bind.failure is ProbeFailure.PermissionDenied) {
        return ProbeEvaluation.VpnPermissionDenied
    }
    if (bind is ProbeResult.Failed) return ProbeEvaluation.BindFailed(bind.failure)

    fun failure(kind: ProbeKind): ProbeFailure? =
        (results.getValue(kind) as? ProbeResult.Failed)?.failure

    failure(ProbeKind.OUTBOUND_TCP)?.let { return ProbeEvaluation.TcpFailed(it) }
    failure(ProbeKind.VPN_DNS)?.let { return ProbeEvaluation.DnsFailed(it) }
    failure(ProbeKind.INTERNAL_LISTENER_READY)?.let {
        return ProbeEvaluation.ListenerNotReady(it)
    }
    if (requirements.udpRequired) {
        failure(ProbeKind.OUTBOUND_UDP)?.let { return ProbeEvaluation.UdpFailed(it) }
    }
    return ProbeEvaluation.Success
}
