package be.mygod.vpnhotspot.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import be.mygod.vpnhotspot.MainActivity
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.root.daemon.DaemonController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Persistent foreground owner for the proxy controller, backend and daemon composition. */
class ProxyOnlyService : Service() {
    companion object {
        const val ACTION_START = "be.mygod.vpnhotspot.proxy.START"
        const val ACTION_ENABLE = "be.mygod.vpnhotspot.proxy.ENABLE"
        const val ACTION_RESUME = "be.mygod.vpnhotspot.proxy.RESUME"
        const val ACTION_DISABLE = "be.mygod.vpnhotspot.proxy.DISABLE"
        const val ACTION_ROTATE_CREDENTIALS = "be.mygod.vpnhotspot.proxy.ROTATE_CREDENTIALS"

        private const val CHANNEL_ID = "proxy-only"
        private const val NOTIFICATION_ID = 2
        private const val FGS_NOT_ALLOWED_CLASS = "android.app.ForegroundServiceStartNotAllowedException"

        /**
         * Deliberately does not start anything. A persisted enable bit is not an activation grant;
         * binding the screen exposes ActivationRequired until the user explicitly resumes.
         */
        @Suppress("UNUSED_PARAMETER")
        fun startIfEnabled(context: Context) = Unit

        fun request(context: Context, action: String) {
            val intent = Intent(context, ProxyOnlyService::class.java).setAction(action)
            if (action == ACTION_DISABLE) context.startService(intent)
            else ContextCompat.startForegroundService(context, intent)
        }
    }

    class Binder internal constructor(private val owner: ProxyOnlyService) : android.os.Binder() {
        val state: StateFlow<ProxyOnlyState> get() = owner.state
        val settings: StateFlow<ProxyOnlySettings> get() = owner.settings
        val credentials: StateFlow<ProxyCredentials?> get() = owner.credentials

        fun enable() = request(owner, ACTION_ENABLE)
        fun resume() = request(owner, ACTION_RESUME)
        fun disable() = request(owner, ACTION_DISABLE)
        fun rotateCredentials() = request(owner, ACTION_ROTATE_CREDENTIALS)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.Default + CoroutineName("proxy-only-service"),
    )
    private val cleanupOwner = ProxyServiceCleanupOwner(serviceScope.coroutineContext)
    private val mutableState = MutableStateFlow<ProxyOnlyState>(ProxyOnlyState.Disabled)
    val state: StateFlow<ProxyOnlyState> = mutableState.asStateFlow()
    private val mutableCredentials = MutableStateFlow<ProxyCredentials?>(null)
    val credentials: StateFlow<ProxyCredentials?> = mutableCredentials.asStateFlow()
    private val binder = Binder(this)
    private val foreground = AtomicBoolean(false)

    lateinit var settings: StateFlow<ProxyOnlySettings>
        private set
    private lateinit var downstreams: StateFlow<List<ManagedDownstream>>
    private lateinit var clients: StateFlow<List<AllowedClient>>
    private lateinit var vpnSelection: StateFlow<VpnSelection>
    private lateinit var composition: ProxyDaemonComposition
    private lateinit var serviceClient: AndroidProxyServiceClient
    private var workerJob: Job? = null
    private var bootstrapJob: Job? = null
    private var daemonLease: DaemonController.Lease? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settings = proxySettingsFlow().stateIn(
            serviceScope,
            SharingStarted.Eagerly,
            ProxyOnlyPreferences.current(),
        )
        downstreams = proxyDownstreamsFlow().stateIn(
            serviceScope,
            SharingStarted.Eagerly,
            emptyList(),
        )
        clients = proxyAllowedClientsFlow().stateIn(
            serviceScope,
            SharingStarted.Eagerly,
            emptyList(),
        )
        vpnSelection = proxyVpnSelectionFlow().stateIn(
            serviceScope,
            SharingStarted.Eagerly,
            VpnSelection.None,
        )
        mutableCredentials.value = runCatching { ProxyCredentialStore.credentials() }.getOrNull()

        serviceClient = AndroidProxyServiceClient(this, KotlinSocks5Backend())
        composition = ProxyDaemonComposition(RootProxyFirewallRpc(), ::containmentConfig)
        val desired = ProxyDesiredStateSource(
            settings = settings,
            pendingGrant = ProxyActivationGrants.pending,
            vpnSelection = vpnSelection,
            downstreams = downstreams,
            allowedClients = clients,
        ).states()
        val controller = ProxyOnlyController(
            service = serviceClient,
            firewall = composition.firewallClient,
            activationGrants = ProxyActivationGrants,
            credentialProvider = ProxyCredentialStore,
            stateSink = object : ProxyStateSink {
                override suspend fun publish(state: ProxyOnlyState) {
                    mutableState.value = state
                    updateNotification(state)
                    if (state == ProxyOnlyState.Disabled && !ProxyOnlyPreferences.enabled) {
                        stopForegroundAndSelf()
                    }
                }
            },
            reporter = object : ProxyErrorReporter {
                override fun report(
                    category: String,
                    failure: Throwable,
                    cleanupFailures: List<CleanupFailure>,
                ) {
                    Timber.tag("ProxyOnly").w(failure, "%s cleanup=%s", category, cleanupFailures.map { it.key })
                }
            },
            scope = serviceScope,
            cleanupSupervisor = cleanupOwner.cleanupSupervisor,
        )
        workerJob = controller.start(composition.desiredStates(desired))
        bootstrapJob = serviceScope.launch {
            while (isActive) {
                if (settings.value.enabled && ProxyActivationGrants.pending.value != null) {
                    if (daemonLease == null) {
                        daemonLease = runCatching { DaemonController.acquireLease() }
                            .onFailure { Timber.tag("ProxyOnly").w(it, "Root daemon lease failed") }
                            .getOrNull()
                    }
                    if (daemonLease != null && !composition.daemonState.value.healthy) {
                        runCatching { composition.bootstrap() }.onFailure {
                            Timber.tag("ProxyOnly").w(it, "Root daemon bootstrap failed")
                        }
                    }
                }
                delay(if (composition.daemonState.value.healthy) 15_000L else 3_000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_ENABLE -> {
                ProxyOnlyPreferences.enabled = true
                mutableCredentials.value = ProxyCredentialStore.credentials()
                ProxyActivationGrants.issue(ActivationSource.USER_ENABLE)
                ensureForeground(ProxyOnlyState.ServiceStarting)
            }
            ACTION_RESUME -> {
                if (ProxyOnlyPreferences.enabled) {
                    ProxyActivationGrants.issue(ActivationSource.USER_RESUME)
                    ensureForeground(ProxyOnlyState.ServiceStarting)
                } else stopSelfResult(startId)
            }
            ACTION_DISABLE -> {
                ProxyActivationGrants.clear()
                ProxyOnlyPreferences.enabled = false
                if (!foreground.get()) stopSelfResult(startId)
            }
            ACTION_ROTATE_CREDENTIALS -> {
                mutableCredentials.value = ProxyCredentialStore.rotate()
            }
            ACTION_START -> {
                if (!ProxyOnlyPreferences.enabled) stopSelfResult(startId)
                else mutableState.value = ProxyOnlyState.ActivationRequired
            }
            else -> Timber.tag("ProxyOnly").w("Unknown service action %s", intent?.action)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            withContext(NonCancellable) {
                bootstrapJob?.cancelAndJoin()
                workerJob?.cancelAndJoin()
                cleanupOwner.close()
                daemonLease?.close()
                daemonLease = null
            }
        }
        serviceScope.cancel()
        foreground.set(false)
        super.onDestroy()
    }

    internal fun validateGrant(grant: ActivationGrant) {
        check(ProxyActivationGrants.isLive(grant.id)) { "activation grant is not live" }
    }

    /** Returns false only when Android rejected foreground activation. */
    internal fun ensureForeground(state: ProxyOnlyState): Boolean {
        val notification = buildNotification(state)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else startForeground(NOTIFICATION_ID, notification)
            foreground.set(true)
            true
        } catch (failure: RuntimeException) {
            if (Build.VERSION.SDK_INT >= 31 && failure.javaClass.name == FGS_NOT_ALLOWED_CLASS) {
                mutableState.value = ProxyOnlyState.ActivationRequired
                foreground.set(false)
                false
            } else throw failure
        }
    }

    internal fun updateNotification(state: ProxyOnlyState) {
        if (!foreground.get()) return
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    internal fun stopForegroundAndSelf() {
        if (foreground.getAndSet(false)) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun containmentConfig(): ProxyFirewallConfig {
        val current = settings.value
        return ProxyFirewallConfig(
            downstreams = downstreams.value.mapNotNull { downstream ->
                downstream.ipv4Address?.let { address ->
                    ProxyDownstreamConfig(downstream.interfaceName, listOf(address))
                }
            },
            tcpPort = current.tcpPort,
            udpPortRangeStart = if (current.udpEnabled) current.udpPortRange.first else 0,
            udpPortRangeEnd = if (current.udpEnabled) current.udpPortRange.last else 0,
            allowedClients = emptyList(),
            generation = 1L,
            denyAllIpv4 = true,
            denyAllIpv6 = true,
        )
    }

    private fun createNotificationChannel() {
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getText(R.string.notification_channel_proxy),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(state: ProxyOnlyState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID).apply {
            setWhen(0)
            setOngoing(true)
            setCategory(Notification.CATEGORY_SERVICE)
            setSmallIcon(R.drawable.ic_proxy)
            setContentTitle(getText(R.string.notification_proxy_title))
            setContentText(proxyStateText(state))
            setContentIntent(contentIntent)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            if (state == ProxyOnlyState.ActivationRequired) {
                addAction(
                    Notification.Action.Builder(
                        R.drawable.ic_proxy,
                        getText(R.string.proxy_resume),
                        PendingIntent.getService(
                            this@ProxyOnlyService,
                            1,
                            Intent(this@ProxyOnlyService, ProxyOnlyService::class.java)
                                .setAction(ACTION_RESUME),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build(),
                )
            }
        }.build()
    }

    private fun proxyStateText(state: ProxyOnlyState): CharSequence = getText(when (state) {
        ProxyOnlyState.Disabled -> R.string.proxy_state_disabled
        ProxyOnlyState.ActivationRequired -> R.string.proxy_state_activation_required
        ProxyOnlyState.ServiceStarting -> R.string.proxy_state_starting
        ProxyOnlyState.WaitingForTethering -> R.string.proxy_state_waiting_tethering
        ProxyOnlyState.WaitingForVpn -> R.string.proxy_state_waiting_vpn
        is ProxyOnlyState.MultipleVpnCandidates -> R.string.proxy_state_multiple_vpn
        ProxyOnlyState.VpnPermissionDenied -> R.string.proxy_state_vpn_denied
        ProxyOnlyState.StartingBackend -> R.string.proxy_state_starting_backend
        is ProxyOnlyState.Running -> R.string.proxy_state_running
        is ProxyOnlyState.FailClosed -> R.string.proxy_state_fail_closed
        is ProxyOnlyState.CleanupDegraded -> R.string.proxy_state_cleanup
    })
}

/** Service/backend handle owner used by the controller. */
private class AndroidProxyServiceClient(
    private val owner: ProxyOnlyService,
    private val backend: ProxyBackend,
) : ProxyServiceClient {
    private val lock = Mutex()
    private var featureActive = false
    private var backendHandle: ProxyBackendHandle? = null

    override suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation = lock.withLock {
        if (featureActive) return@withLock ServiceActivation.Active
        owner.validateGrant(grant)
        val started = withContext(Dispatchers.Main.immediate) { owner.ensureForeground(initialState) }
        if (!started) return@withLock ServiceActivation.ForegroundStartNotAllowed
        featureActive = true
        ServiceActivation.Active
    }

    override suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport = lock.withLock {
        if (!featureActive) return@withLock CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("waiting: $state")
        owner.updateNotification(state)
        report
    }

    override suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle = lock.withLock {
        check(featureActive) { "proxy feature is inactive" }
        check(backendHandle == null) { "proxy backend is already active" }
        backend.start(config).also { backendHandle = it }.let { ProxyServiceHandle(it.id) }
    }

    override suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>) = lock.withLock {
        backend.replaceAcl(requireHandle(handle), clients)
    }

    override suspend fun runOutboundProbes(
        handle: ProxyServiceHandle,
        requirements: ProbeRequirements,
    ): ProbeReport = lock.withLock {
        backend.runOutboundProbes(requireHandle(handle), requirements)
    }

    override suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport = lock.withLock {
        if (!featureActive) return@withLock CleanupReport.noOp("service inactive")
        val current = backendHandle ?: return@withLock CleanupReport.noOp("backend absent")
        if (current.id != handle.id) return@withLock CleanupReport.staleHandle(handle.id)
        backend.stop(current).withContext("ProxyOnlyService.stopBackend").also { report ->
            if (!report.hasCriticalFailure) backendHandle = null
        }
    }

    override suspend fun emergencyCloseListener(reason: String): CleanupOutcome = lock.withLock {
        if (!featureActive) return@withLock CleanupOutcome(CleanupReport.noOp("service inactive"), null)
        val current = backendHandle
            ?: return@withLock CleanupOutcome(CleanupReport.noOp("backend absent"), null)
        val report = backend.emergencyCloseListener(current, reason)
            .withContext("ProxyOnlyService.emergencyCloseListener")
        val debt = if (report.hasCriticalFailure) CleanupDebt(
            listenerClosePending = true,
            serviceHandlePending = ProxyServiceHandle(current.id),
            firewallHandlePending = null,
            firewallDenyPending = false,
            firewallStopPending = false,
            daemonCleanPending = false,
            featureStopPending = false,
            serviceWasActivated = true,
            failures = report.failures,
            generation = 0L,
            attempt = 0,
        ) else null
        CleanupOutcome(report, debt)
    }

    override suspend fun stopFeature(reason: String): CleanupReport = lock.withLock {
        if (!featureActive) return@withLock CleanupReport.noOp("service inactive")
        val report = closeCurrentBackend("feature stop: $reason")
        if (!report.hasCriticalFailure) {
            featureActive = false
            withContext(Dispatchers.Main.immediate) { owner.stopForegroundAndSelf() }
        }
        report
    }

    override suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats = lock.withLock {
        backend.stats(requireHandle(handle))
    }

    private suspend fun closeCurrentBackend(reason: String): CleanupReport {
        val current = backendHandle ?: return CleanupReport.noOp("backend absent")
        return backend.stop(current).withContext("ProxyOnlyService.closeCurrentBackend", reason).also { report ->
            if (!report.hasCriticalFailure) backendHandle = null
        }
    }

    private fun requireHandle(handle: ProxyServiceHandle): ProxyBackendHandle {
        val current = backendHandle ?: throw IllegalStateException("proxy backend is absent")
        if (current.id != handle.id) throw IllegalStateException("stale proxy backend handle ${handle.id}")
        return current
    }
}
