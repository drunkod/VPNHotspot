# Step 1 — Settings, state, activation and desired-state models

Task: define the domain models the controller, service and firewall contracts share.
Maps to: Implementation plan Phase 2 (domain model and settings).
Depends on: nothing — this is the foundation step.
Consumed by: [Step 2](02-vpn-selection.md), [Step 5](05-cleanup-debt-model.md), [Step 6](06-controller-worker.md).

## 1.1 Settings, state and activation models

```kotlin
enum class SharingMode { VPN_ROUTING, PROXY_ONLY }

data class ProxyOnlySettings(
    val enabled: Boolean,
    val tcpPort: Int,
    val udpEnabled: Boolean,
    val udpPortRange: IntRange,
    val maxUdpAssociations: Int,
    val credentialsVersion: Long,
    val username: String,
    val password: String,
)

enum class ActivationSource {
    USER_ENABLE,
    USER_RESUME,
}

data class ActivationGrant(
    val id: UUID,
    val issuedAtElapsedRealtime: Long,
    val source: ActivationSource,
)

interface ActivationGrantConsumer {
    suspend fun consume(grantId: UUID)
}

data class ProxyVpnUpstream(
    val network: Network,
    val handle: Long,
    val interfaces: Set<String>,
)

data class RuntimeKey(
    val tcpPort: Int,
    val udpPortRange: IntRange?,
    val credentialsVersion: Long,
    val vpnNetworkHandle: Long,
    val downstreams: List<Pair<String, String>>,
    val backendVersion: Int,
)

sealed interface FailClosedReason {
    data object RootDaemonUnavailable : FailClosedReason
    data class TcpProbeFailed(val detail: String) : FailClosedReason
    data class UdpProbeFailed(val detail: String) : FailClosedReason
    data class DnsProbeFailed(val detail: String) : FailClosedReason
    data class ListenerNotReady(val detail: String) : FailClosedReason
    data class InternalFailure(val category: String) : FailClosedReason
}

sealed interface ProxyOnlyState {
    data object Disabled : ProxyOnlyState
    data object ActivationRequired : ProxyOnlyState
    data object ServiceStarting : ProxyOnlyState
    data object WaitingForTethering : ProxyOnlyState
    data object WaitingForVpn : ProxyOnlyState
    data class MultipleVpnCandidates(val count: Int) : ProxyOnlyState
    data object VpnPermissionDenied : ProxyOnlyState
    data object StartingBackend : ProxyOnlyState
    data class Running(val endpoint: ProxyEndpoint) : ProxyOnlyState
    data class FailClosed(val reason: FailClosedReason) : ProxyOnlyState
    data class CleanupDegraded(
        val unresolved: Set<CleanupResource>,
        val failures: List<CleanupFailure>,
        val retryAttempt: Int,
    ) : ProxyOnlyState
}
```

Persisted `settings.enabled` is not an activation grant. A grant is created only by an Android-permitted foreground user action.

## 1.2 Desired state and controller events

```kotlin
data class DesiredProxyState(
    val settings: ProxyOnlySettings,
    val activationGrant: ActivationGrant?,
    val vpnSelection: VpnSelection,
    val downstreams: List<ManagedDownstream>,
    val allowedClients: List<AllowedClient>,
    val daemonHealthy: Boolean,
)

sealed interface ControllerEvent {
    data class Snapshot(val state: DesiredProxyState) : ControllerEvent
    data class RetryCleanupDebt(val generation: Long) : ControllerEvent
}
```

Snapshots are normalized before entering the controller channel. Client ordering and irrelevant `LinkProperties` churn must not alter the runtime key.
