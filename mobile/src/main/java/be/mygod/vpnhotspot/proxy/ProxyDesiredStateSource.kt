package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Assembles the controller input from app-facing settings and live network state. */
class ProxyDesiredStateSource(
    private val settings: Flow<ProxyOnlySettings>,
    private val pendingGrant: Flow<ActivationGrant?>,
    private val vpnSelection: Flow<VpnSelection>,
    private val downstreams: Flow<List<ManagedDownstream>>,
    private val allowedClients: Flow<List<AllowedClient>>,
) {
    fun states(): Flow<DesiredProxyState> = combine(
        settings,
        pendingGrant,
        vpnSelection,
        downstreams,
        allowedClients,
    ) { currentSettings, grant, vpn, currentDownstreams, clients ->
        DesiredProxyState(
            settings = currentSettings,
            activationGrant = grant,
            vpnSelection = vpn,
            downstreams = currentDownstreams,
            allowedClients = clients,
            daemonHealthy = false,
            daemonGeneration = null,
        ).normalized()
    }.distinctUntilChanged()
}
