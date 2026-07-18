# Step 4 — Typed, configuration-aware probes

Task: outbound-only startup probes with typed, configuration-aware evaluation so
expected failures (e.g. `VpnPermissionDenied`) stay diagnosable instead of collapsing
into generic exceptions.
Maps to: Implementation plan Phase 0 (per-app VPN policy matrix, DNS tests) and Phase 4.
Depends on: [Step 1](01-models-and-state.md) models.
Consumed by: [Step 6](06-controller-worker.md).

Probes never connect to the SOCKS listener through the downstream interface — deny-first
startup intentionally blocks client ingress. `INTERNAL_LISTENER_READY` reads native
bind/listen status without traversing iptables. External reachability is tested only
after per-client allow rules are committed.

```kotlin
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
    data object VpnPermissionDenied : ProbeEvaluation
    data class BindFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class TcpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class UdpFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class DnsFailed(val failure: ProbeFailure) : ProbeEvaluation
    data class ListenerNotReady(val failure: ProbeFailure) : ProbeEvaluation
    data class Incomplete(val missing: Set<ProbeKind>) : ProbeEvaluation
}

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
```

UDP is not required when `settings.udpEnabled == false`.

`Incomplete` means a required result is absent; it is not a synonym for listener
failure. Non-permission bind failures map to `BindFailed`, while only
`PermissionDenied` maps to `VpnPermissionDenied`.
