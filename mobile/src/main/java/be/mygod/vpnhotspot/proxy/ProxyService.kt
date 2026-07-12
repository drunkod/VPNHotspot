package be.mygod.vpnhotspot.proxy

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Phase-0 foreground-service lifecycle holder.
 *
 * The production Android service will own one instance for its full lifecycle. Keeping
 * ownership in a named object now makes it impossible to pass an anonymous raw scope to
 * [ProxyOnlyController]. [close] is the single authoritative teardown site.
 *
 * Lifecycle ordering is strict: cancel and join the controller worker first so its terminal
 * cleanup can enqueue final debt, then call [close] to cancel any remaining supervisor retries.
 */
class ProxyServiceCleanupOwner(
    parent: CoroutineContext,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    val cleanupSupervisor: ProxyCleanupSupervisor = ProxyCleanupSupervisor.create(
        parent = parent,
        dispatcher = dispatcher,
        name = "proxy-service-cleanup",
    )

    override fun close() {
        cleanupSupervisor.shutdown("proxy service owner closed")
    }
}
