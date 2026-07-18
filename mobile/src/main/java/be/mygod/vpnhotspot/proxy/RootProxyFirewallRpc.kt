package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import be.mygod.vpnhotspot.root.daemon.DaemonController
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Concrete proxy-firewall transport over the existing framed root daemon channel. */
class RootProxyFirewallRpc(
    private val call: suspend (ProxyFirewallCommand) -> ProxyFirewallAck = DaemonController::proxyFirewall,
) : ProxyFirewallRpc {
    override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck = try {
        call(command)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("proxy firewall RPC transport failed", e)
    }
}
