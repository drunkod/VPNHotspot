# Step 2 — VPN-only selection

Task: select exactly one validated `TRANSPORT_VPN` network; fail closed on none or many.
Maps to: Implementation plan Phase 0 (VPN-selection tasks) and Phase 3 (VPN selector).
Depends on: [Step 1](01-models-and-state.md) models.
Consumed by: [Step 6](06-controller-worker.md).

Security context: `Upstreams.primary` can be overridden by the `service.upstream`
interface-regex preference to a physical network, so it must never be trusted without
capability validation (round-1 review, finding 1.1).

```kotlin
sealed interface VpnSelection {
    data object None : VpnSelection
    data class One(val upstream: ProxyVpnUpstream) : VpnSelection
    data class Multiple(val candidates: List<ProxyVpnUpstream>) : VpnSelection
}

class ProxyVpnSelector(
    private val connectivity: ConnectivityManager,
) {
    fun select(candidates: Collection<Upstream>): VpnSelection {
        val usable = candidates.mapNotNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate.network)
                ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
            ProxyVpnUpstream(
                network = candidate.network,
                handle = candidate.network.networkHandle,
                interfaces = candidate.properties.allInterfaceNames.toSortedSet(),
            )
        }
        return when (usable.size) {
            0 -> VpnSelection.None
            1 -> VpnSelection.One(usable.single())
            else -> VpnSelection.Multiple(usable)
        }
    }
}
```

No candidate is selected by sorting transient network handles. A typed app-UID bind probe ([Step 4](04-probes.md)) decides whether the one candidate is usable by VPN Hotspot.
