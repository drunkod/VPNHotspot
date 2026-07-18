package be.mygod.vpnhotspot.proxy

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-lifetime, one-time activation grants issued only by foreground user actions. */
object ProxyActivationGrants : ActivationGrantConsumer {
    private val live = ConcurrentHashMap.newKeySet<UUID>()
    private val mutablePending = MutableStateFlow<ActivationGrant?>(null)
    val pending: StateFlow<ActivationGrant?> = mutablePending.asStateFlow()

    @Synchronized
    fun issue(source: ActivationSource): ActivationGrant {
        mutablePending.value?.let { live.remove(it.id) }
        val grant = ActivationGrant(
            id = UUID.randomUUID(),
            issuedAtElapsedRealtime = SystemClock.elapsedRealtime(),
            source = source,
        )
        live.add(grant.id)
        mutablePending.value = grant
        return grant
    }

    fun isLive(id: UUID): Boolean = id in live

    override suspend fun consume(grantId: UUID) = synchronized(this) {
        live.remove(grantId)
        if (mutablePending.value?.id == grantId) mutablePending.value = null
    }

    @Synchronized
    fun clear() {
        mutablePending.value?.let { live.remove(it.id) }
        mutablePending.value = null
    }
}
