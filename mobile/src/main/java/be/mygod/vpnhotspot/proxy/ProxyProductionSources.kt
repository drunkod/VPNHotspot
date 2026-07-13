package be.mygod.vpnhotspot.proxy

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.NetlinkNeighbour
import be.mygod.vpnhotspot.net.TetherStates
import be.mygod.vpnhotspot.root.daemon.NeighbourState
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Exactly-one-VPN selection driven by public ConnectivityManager callbacks. */
fun proxyVpnSelectionFlow(): Flow<VpnSelection> = callbackFlow {
    val connectivity = checkNotNull(app.getSystemService<ConnectivityManager>())
    val selector = ProxyVpnSelector(connectivity)

    fun snapshot(): VpnSelection {
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val properties = connectivity.getLinkProperties(network) ?: LinkProperties()
            object : Upstream {
                override val network: Network = network
                override val properties: LinkProperties = properties
            }
        }
        return selector.select(candidates)
    }

    fun publish() {
        trySend(snapshot())
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = publish()
    }
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .build()
    connectivity.registerNetworkCallback(request, callback)
    publish()
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate().distinctUntilChanged()

/** Active tethering/local-only interfaces with their current IPv4 listener address. */
fun proxyDownstreamsFlow(): Flow<List<ManagedDownstream>> = TetherStates.flow.mapLatest { states ->
    withContext(Dispatchers.IO) {
        (states.tethered + states.localOnly).map { iface ->
            val ipv4 = runCatching {
                NetworkInterface.getByName(iface)?.inetAddresses?.asSequence()
                    ?.filterIsInstance<Inet4Address>()
                    ?.mapNotNull { it.hostAddress }
                    ?.firstOrNull(::isRoutableIpv4)
            }.getOrNull()
            ManagedDownstream(interfaceName = iface, ipv4Address = ipv4)
        }.sortedBy { it.interfaceName }
    }
}.distinctUntilChanged()

/**
 * Current IPv4 neighbours on active downstreams. Firewall admission remains interface + IP + MAC;
 * an empty list is deny-all and is never interpreted as a wildcard.
 */
fun proxyAllowedClientsFlow(): Flow<List<AllowedClient>> = combine(
    TetherStates.flow,
    NetlinkNeighbour.monitorSnapshots.onStart { emit(NetlinkNeighbour.Snapshot()) },
) { states, snapshot ->
    val activeInterfaces = states.tethered + states.localOnly
    snapshot.neighbours.asSequence().mapNotNull { neighbour ->
        if (neighbour.state != NeighbourState.NEIGHBOUR_STATE_VALID) return@mapNotNull null
        val mac = neighbour.lladdr?.toString() ?: return@mapNotNull null
        val ipv4 = neighbour.ip as? Inet4Address ?: return@mapNotNull null
        val address = ipv4.hostAddress ?: return@mapNotNull null
        if (!isRoutableIpv4(address)) return@mapNotNull null
        val canonicalInterface = snapshot.bridgeMasterByMember[neighbour.dev] ?: neighbour.dev
        if (canonicalInterface !in activeInterfaces && neighbour.dev !in activeInterfaces) return@mapNotNull null
        AllowedClient(mac = mac, ipv4Addresses = listOf(address))
    }.groupBy { canonicalizeMac(it.mac) ?: it.mac }
        .map { (mac, clients) ->
            AllowedClient(
                mac = mac,
                ipv4Addresses = clients.flatMap { it.ipv4Addresses }.distinct().sorted(),
            )
        }
        .sortedBy { it.mac }
}.distinctUntilChanged()
