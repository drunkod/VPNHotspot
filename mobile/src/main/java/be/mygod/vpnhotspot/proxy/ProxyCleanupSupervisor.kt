package be.mygod.vpnhotspot.proxy

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the scope in which terminal cleanup-debt retries execute.
 *
 * The foreground-service/application owner creates this object, hands it to every
 * controller worker it owns, and calls [shutdown] only at authoritative final teardown.
 * Controller workers may launch retry work but cannot cancel the supervisor lifecycle.
 */
class ProxyCleanupSupervisor private constructor(
    private val supervisorJob: CompletableJob,
    val retryScope: CoroutineScope,
) {
    private val active = AtomicBoolean(true)

    val isActive: Boolean get() = active.get() && supervisorJob.isActive

    /** Cancel all in-flight retries. Idempotent; only the lifecycle owner calls this. */
    fun shutdown(reason: String) {
        if (active.compareAndSet(true, false)) {
            supervisorJob.cancel(
                CancellationException("cleanup supervisor shutdown: $reason")
            )
        }
    }

    companion object {
        fun create(
            parent: CoroutineContext,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            name: String = "proxy-cleanup",
        ): ProxyCleanupSupervisor {
            val job = SupervisorJob(parent[Job])
            val scope = CoroutineScope(parent + job + dispatcher + CoroutineName(name))
            return ProxyCleanupSupervisor(job, scope)
        }
    }
}
