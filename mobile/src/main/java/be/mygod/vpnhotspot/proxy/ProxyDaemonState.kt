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
        val completeIdentity = sessionId != null && generation != null
        val absentIdentity = sessionId == null && generation == null
        require((healthy && completeIdentity) || (!healthy && absentIdentity)) {
            "daemon state must be either healthy with a complete identity or unavailable"
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

/** Single source of truth for daemon identity used by [DesiredProxyState]. */
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
 * Successful and stale-token acknowledgements publish identity. Stale acknowledgements are
 * intentionally observed because they identify the newer daemon boot. INVALID/IO_ERROR responses
 * do not publish a transient usable state, and transport [IOException] clears the tracker.
 */
class TrackingProxyFirewallRpc(
    private val delegate: ProxyFirewallRpc,
    private val tracker: ProxyDaemonStateTracker,
) : ProxyFirewallRpc {
    override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck = try {
        delegate.execute(command).also { acknowledgement ->
            when (acknowledgement.status) {
                ProxyFirewallAck.Status.OK,
                ProxyFirewallAck.Status.STALE_SESSION,
                ProxyFirewallAck.Status.STALE_EPOCH -> acknowledgement.identity?.let { identity ->
                    tracker.acknowledge(
                        sessionId = identity.session_id,
                        generation = identity.generation,
                    )
                }
                ProxyFirewallAck.Status.INVALID,
                ProxyFirewallAck.Status.IO_ERROR,
                is ProxyFirewallAck.Status.Unrecognized -> Unit
            }
        }
    } catch (failure: IOException) {
        tracker.disconnected()
        throw failure
    }
}

/**
 * Concrete production composition for proxy firewall RPC and daemon health identity.
 *
 * The same private tracker feeds both [firewallClient] and [desiredStates]. Call [bootstrap]
 * before starting the controller and again after the root transport reconnects. Bootstrap uses
 * the existing deny-first sanitation command, so identity becomes visible only after containment
 * succeeds. The controller still performs its own sanitation gate afterward.
 */
class ProxyDaemonComposition(
    rpc: ProxyFirewallRpc,
    containmentConfig: suspend () -> ProxyFirewallConfig,
) {
    private val tracker = ProxyDaemonStateTracker()
    val daemonState: StateFlow<ProxyDaemonState> get() = tracker.state
    val firewallClient: DaemonProxyFirewallClient = DaemonProxyFirewallClient(
        rpc = TrackingProxyFirewallRpc(rpc, tracker),
        containmentConfig = containmentConfig,
    )

    suspend fun bootstrap(): ProxyDaemonState {
        val sanitation = firewallClient.cleanOrDenyBeforeRestart()
        if (sanitation == null) tracker.disconnected()
        return tracker.state.value
    }

    fun transportDisconnected() {
        tracker.disconnected()
    }

    fun desiredStates(source: Flow<DesiredProxyState>): Flow<DesiredProxyState> =
        source.withAcknowledgedDaemonState(tracker.state)
}

/**
 * Replace any independently supplied daemon health clock with acknowledgement-backed state.
 */
fun Flow<DesiredProxyState>.withAcknowledgedDaemonState(
    daemonState: Flow<ProxyDaemonState>,
): Flow<DesiredProxyState> = combine(daemonState) { desired, daemon ->
    desired.copy(
        daemonHealthy = daemon.healthy,
        daemonGeneration = daemon.generation,
    )
}.distinctUntilChanged()
