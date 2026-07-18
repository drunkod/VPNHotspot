package be.mygod.vpnhotspot.proxy

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// ---------------------------------------------------------------------------
// Step 2 — VPN-only selection
// Sketch: docs/proxy-only/sketches/02-vpn-selection.md
//
// Security: Upstreams.primary may be overridden by service.upstream to a
// physical network; it must never be trusted without TRANSPORT_VPN validation.
// ---------------------------------------------------------------------------

sealed interface VpnSelection {
    data object None : VpnSelection
    data class One(val upstream: ProxyVpnUpstream) : VpnSelection
    data class Multiple(val candidates: List<ProxyVpnUpstream>) : VpnSelection
}

/**
 * Selects exactly one validated [ProxyVpnUpstream] that carries
 * [NetworkCapabilities.TRANSPORT_VPN]. Fails closed (returns [VpnSelection.None]
 * or [VpnSelection.Multiple]) when the count is not exactly one.
 *
 * No candidate is selected by sorting transient network handles.
 */
class ProxyVpnSelector(
    private val connectivity: ConnectivityManager,
) {
    fun select(candidates: Collection<Upstream>): VpnSelection {
        // Deduplicate by stable network handle, then merge the public interfaceName
        // value from every observation of the same network. LinkProperties exposes
        // one nullable interface name through its public Android API; duplicate
        // observations may still carry different/stale values, so merge them here.
        val usable = candidates
            .mapNotNull { candidate ->
                val caps = connectivity.getNetworkCapabilities(candidate.network)
                    ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                ProxyVpnUpstream(
                    network = candidate.network,
                    handle = candidate.network.networkHandle,
                    interfaces = listOfNotNull(candidate.properties.interfaceName).toSortedSet(),
                )
            }
            .groupBy { it.handle }
            .values
            .map { group ->
                // Merge interface names from all duplicate observations deterministically.
                group.reduce { acc, obs ->
                    acc.copy(interfaces = (acc.interfaces + obs.interfaces).toSortedSet())
                }
            }

        return when (usable.size) {
            0 -> VpnSelection.None
            1 -> VpnSelection.One(usable.single())
            else -> VpnSelection.Multiple(usable)
        }
    }
}

/**
 * Minimal upstream descriptor; the real type is provided by the existing
 * routing/tethering layer. This stub keeps the proxy package self-contained
 * until integration wires the real [android.net.Network] and
 * [android.net.LinkProperties] objects.
 */
interface Upstream {
    val network: android.net.Network
    val properties: android.net.LinkProperties
}
