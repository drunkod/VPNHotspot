package be.mygod.vpnhotspot.root.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Monotonic signal for transport closures that occur while a daemon lease is still active. */
internal class DaemonTransportClosureSignal {
    private val mutableEpoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = mutableEpoch.asStateFlow()

    fun connectionClosed(
        wasConnected: Boolean,
        activeLeases: Int,
        closingAlready: Boolean,
    ) {
        if (!wasConnected || activeLeases <= 0 || closingAlready) return
        mutableEpoch.update { current -> if (current == Long.MAX_VALUE) 1L else current + 1L }
    }
}
