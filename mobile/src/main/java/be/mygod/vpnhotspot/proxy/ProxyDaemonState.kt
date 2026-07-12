package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Authoritative root-daemon availability and boot identity for proxy-only composition.
 *
 * [generation] is accepted only from a daemon acknowledgement identity. Callers must not
 * manufacture it from connection attempts, timestamps or a separate health counter.
 */
data class ProxyDaemonState(
    val healthy: Boolean,
    val sessionId: Long?,
    val generation: Long?,
) {
    init {
        require(healthy == (sessionId != null && generation != null)) {
            "healthy daemon state requires a complete acknowledgement identity"
        }
        require(sessionId == null || sessionId != 0L) { "daemon session ID must be non-zero" }
        require(generation == null || generation != 0L) { "daemon generation must be non-zero" }
    }

    companion object {
        val Unavailable = ProxyDaemonState(
            healthy = false,
            sessionId = null,
            generation = null,
        )
    }
}

/**
 * Single source of truth for the daemon identity used by [DesiredProxyState].
 *
 * The production RPC transport owns this tracker. Every typed daemon acknowledgement with an
 * identity refreshes it; an I/O disconnect clears it. The controller therefore observes the
 * same generation that the firewall client later validates during sanitation.
 */
class ProxyDaemonStateTracker {
    private val mutableState = MutableStateFlow(ProxyDaemonState.Unavailable)
    val state: StateFlow<ProxyDaemonState> get() = mutableState

    fun acknowledge(sessionId: Long, generation: Long) {
        mutableState.value = ProxyDaemonState(
            healthy = true,
            sessionId = sessionId,
            generation = generation,
        )
    }

    fun disconnected() {
        mutableState.value = ProxyDaemonState.Unavailable
    }
}

/**
 * RPC decorator that makes acknowledgement identity the authoritative health/generation source.
 *
 * Stale-session acknowledgements are intentionally observed as well: they are the daemon telling
 * the client that a newer boot identity exists. Only transport [IOException] marks the daemon
 * unavailable; protocol/kernel errors still prove that the daemon process replied.
 */
class TrackingProxyFirewallRpc(
    private val delegate: ProxyFirewallRpc,
    private val tracker: ProxyDaemonStateTracker,
) : ProxyFirewallRpc {
    override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck = try {
        delegate.execute(command).also { acknowledgement ->
            acknowledgement.identity?.let { identity ->
                tracker.acknowledge(
                    sessionId = identity.session_id,
                    generation = identity.generation,
                )
            }
        }
    } catch (failure: IOException) {
        tracker.disconnected()
        throw failure
    }
}

/**
 * Replace any independently supplied daemon health clock with acknowledgement-backed state.
 *
 * This adapter is the required production entry point before a desired-state flow is handed to
 * [ProxyOnlyController]. It prevents a service/health observer from claiming generation G1 while
 * the RPC channel is returning acknowledgements from generation G2.
 */
fun Flow<DesiredProxyState>.withAcknowledgedDaemonState(
    daemonState: Flow<ProxyDaemonState>,
): Flow<DesiredProxyState> = combine(daemonState) { desired, daemon ->
    desired.copy(
        daemonHealthy = daemon.healthy,
        daemonGeneration = daemon.generation,
    )
}.distinctUntilChanged()
