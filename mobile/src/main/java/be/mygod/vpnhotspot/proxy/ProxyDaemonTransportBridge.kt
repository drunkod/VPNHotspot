package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** Connects real daemon transport closure signals to fail-closed proxy composition and recovery. */
internal class ProxyDaemonTransportBridge(
    private val composition: ProxyDaemonComposition,
    private val containBackend: suspend () -> CleanupReport,
    private val rebootstrap: suspend () -> Unit,
    private val reportContainmentFailure: (CleanupReport) -> Unit = {},
    private val reportBootstrapFailure: (Throwable) -> Unit = {},
) {
    suspend fun collect(
        closures: StateFlow<Long>,
        initialEpoch: Long,
        leaseActive: () -> Boolean,
    ) {
        var observedEpoch = initialEpoch
        closures.collect { epoch ->
            if (epoch == observedEpoch) return@collect
            observedEpoch = epoch
            if (!leaseActive()) return@collect

            composition.transportDisconnected()
            val containment = try {
                containBackend()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportContainmentFailure(CleanupReport.failure("daemon_disconnect_containment", failure))
                return@collect
            }
            if (containment.hasCriticalFailure) {
                reportContainmentFailure(containment)
                return@collect
            }

            try {
                rebootstrap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportBootstrapFailure(failure)
            }
        }
    }
}
