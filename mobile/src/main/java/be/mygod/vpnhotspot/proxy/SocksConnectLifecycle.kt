package be.mygod.vpnhotspot.proxy

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Maps only pre-success setup failures to SOCKS replies; relay I/O after success only closes streams. */
internal suspend fun <T> establishSocksConnectThenRelay(
    establish: suspend () -> T,
    onSetupFailure: (Throwable) -> Unit,
    onEstablished: (T) -> Unit,
    relay: suspend (T) -> Unit,
) {
    val connection = try {
        establish()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onSetupFailure(failure)
        return
    }

    onEstablished(connection)
    try {
        relay(connection)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        // The success reply has already committed the stream to application data.
    }
}
