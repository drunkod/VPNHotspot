package be.mygod.vpnhotspot.proxy

import java.io.Closeable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import sun.misc.Unsafe

internal class FakeFirewallClient : ProxyFirewallClient {
    val calls = mutableListOf<String>()
    val sanitationResults = mutableListOf<SanitationResult?>()

    var nextStartHandle: ProxyFirewallHandle? = null
    var denyResult: CleanupReport = CleanupReport.empty()
    var stopResult: CleanupReport = CleanupReport.empty()

    override suspend fun cleanOrDenyBeforeRestart(): SanitationResult? {
        calls += "sanitize"
        return if (sanitationResults.isEmpty()) null else sanitationResults.removeAt(0)
    }

    override suspend fun start(config: ProxyFirewallConfig): ProxyFirewallHandle {
        calls += "start"
        return nextStartHandle ?: error("test did not program a firewall start handle")
    }

    override suspend fun replace(handle: ProxyFirewallHandle, config: ProxyFirewallConfig) {
        calls += "replace(${handle.sessionId}/${handle.epoch}/${handle.id})"
    }

    override suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport {
        calls += "denyAll(${handle.sessionId}/${handle.epoch}/${handle.id})"
        return denyResult
    }

    override suspend fun stop(handle: ProxyFirewallHandle): CleanupReport {
        calls += "stop(${handle.sessionId}/${handle.epoch}/${handle.id})"
        return stopResult
    }

    fun handleIpcCalls(): List<String> = calls.filter {
        it.startsWith("denyAll") || it.startsWith("stop") || it.startsWith("replace")
    }
}

internal class FakeServiceClient : ProxyServiceClient {
    val calls = mutableListOf<String>()

    var activationResult: ServiceActivation = ServiceActivation.Active
    var waitingResult: CleanupReport = CleanupReport.empty()
    var nextServiceHandle: ProxyServiceHandle = ProxyServiceHandle(1)
    var stopBackendResult: CleanupReport = CleanupReport.empty()
    var emergencyOutcome: CleanupOutcome = CleanupOutcome(CleanupReport.empty(), null)
    var stopFeatureResult: CleanupReport = CleanupReport.empty()

    override suspend fun activateFeature(
        grant: ActivationGrant,
        initialState: ProxyOnlyState,
    ): ServiceActivation {
        calls += "activateFeature"
        return activationResult
    }

    override suspend fun enterWaiting(state: ProxyOnlyState): CleanupReport {
        calls += "enterWaiting"
        return waitingResult
    }

    override suspend fun startBackend(config: ProxyBackendConfig): ProxyServiceHandle {
        calls += "startBackend"
        return nextServiceHandle
    }

    override suspend fun replaceAcl(handle: ProxyServiceHandle, clients: List<AllowedClient>) {
        calls += "replaceAcl(${handle.id})"
    }

    override suspend fun runOutboundProbes(
        handle: ProxyServiceHandle,
        requirements: ProbeRequirements,
    ): ProbeReport {
        calls += "runOutboundProbes(${handle.id})"
        return ProbeReport(requirements.requiredKinds.associateWith { ProbeResult.Success })
    }

    override suspend fun stopBackend(handle: ProxyServiceHandle): CleanupReport {
        calls += "stopBackend(${handle.id})"
        return stopBackendResult
    }

    override suspend fun emergencyCloseListener(reason: String): CleanupOutcome {
        calls += "emergencyCloseListener"
        return emergencyOutcome
    }

    override suspend fun stopFeature(reason: String): CleanupReport {
        calls += "stopFeature"
        return stopFeatureResult
    }

    override suspend fun readStats(handle: ProxyServiceHandle): ProxyBackendStats =
        ProxyBackendStats(activeTcpConnections = 0, activeUdpAssociations = 0)
}

internal class RecordingStateSink : ProxyStateSink {
    val states = mutableListOf<ProxyOnlyState>()
    override suspend fun publish(state: ProxyOnlyState) {
        states += state
    }
}

internal class RecordingErrorReporter : ProxyErrorReporter {
    data class Entry(
        val category: String,
        val failure: Throwable,
        val cleanupFailures: List<CleanupFailure>,
    )

    val entries = mutableListOf<Entry>()

    override fun report(
        category: String,
        failure: Throwable,
        cleanupFailures: List<CleanupFailure>,
    ) {
        entries += Entry(category, failure, cleanupFailures)
    }
}

internal class ControllerHarness(
    val firewall: FakeFirewallClient = FakeFirewallClient(),
    val service: FakeServiceClient = FakeServiceClient(),
) : Closeable {
    private val workerJob = SupervisorJob()
    private val cleanupJob = SupervisorJob()
    private val workerScope = CoroutineScope(workerJob + Dispatchers.Unconfined)
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.Unconfined)

    val stateSink = RecordingStateSink()
    val reporter = RecordingErrorReporter()

    val controller = ProxyOnlyController(
        service = service,
        firewall = firewall,
        activationGrants = object : ActivationGrantConsumer {
            override suspend fun consume(grantId: java.util.UUID) = Unit
        },
        credentialProvider = object : ProxyCredentialProvider {
            override fun credentials() = ProxyCredentials("test-user", "test-password")
        },
        stateSink = stateSink,
        reporter = reporter,
        scope = workerScope,
        cleanupScope = cleanupScope,
    )

    override fun close() {
        workerScope.cancel()
        cleanupScope.cancel()
    }
}

internal fun desiredStateForTest(
    daemonGeneration: Long,
    vpnSelection: VpnSelection = VpnSelection.None,
    downstreams: List<ManagedDownstream> = emptyList(),
): DesiredProxyState = DesiredProxyState(
    settings = ProxyOnlySettings(
        enabled = true,
        tcpPort = 1080,
        udpEnabled = false,
        udpPortRange = 20_000..20_100,
        maxUdpAssociations = 32,
        credentialsVersion = 1,
    ),
    activationGrant = null,
    vpnSelection = vpnSelection,
    downstreams = downstreams,
    allowedClients = emptyList(),
    daemonHealthy = true,
    daemonGeneration = daemonGeneration,
)

internal fun runtimeKeyForTest(daemonGeneration: Long): RuntimeKey = RuntimeKey(
    tcpPort = 1080,
    udpPortRange = null,
    credentialsVersion = 1,
    vpnNetworkHandle = 42,
    downstreams = listOf("wlan0" to listOf("192.168.43.1")),
    backendVersion = 1,
    daemonGeneration = daemonGeneration,
)

/**
 * Builds a [ProxyVpnUpstream] without invoking the android.jar Network constructor.
 * The controller's Phase-0 fast path reads only [ProxyVpnUpstream.handle] and never
 * dereferences [ProxyVpnUpstream.network], so a null backing field is safe in this JVM test.
 */
internal fun fakeVpnUpstream(handle: Long): ProxyVpnUpstream {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null) as Unsafe
    val instance = unsafe.allocateInstance(ProxyVpnUpstream::class.java) as ProxyVpnUpstream

    ProxyVpnUpstream::class.java.getDeclaredField("handle").also { field ->
        unsafe.putLong(instance, unsafe.objectFieldOffset(field), handle)
    }
    ProxyVpnUpstream::class.java.getDeclaredField("interfaces").also { field ->
        unsafe.putObject(instance, unsafe.objectFieldOffset(field), sortedSetOf("tun0"))
    }
    return instance
}

internal fun ProxyOnlyController.seedForTrackATest(
    latest: DesiredProxyState,
    sanitizedDaemonGeneration: Long,
    sanitizedSessionId: Long,
    sanitizedEpoch: Long,
    applied: AppliedProxyState? = null,
    debt: CleanupDebt? = null,
    serviceActivated: Boolean = false,
) {
    readPrivateField<AtomicReference<DesiredProxyState?>>("latestSnapshot").set(latest)
    writePrivateField("sanitizedDaemonGeneration", sanitizedDaemonGeneration)
    writePrivateField("sanitizedSessionId", sanitizedSessionId)
    writePrivateField("sanitizedEpoch", sanitizedEpoch)
    writePrivateField("firewallGeneration", AtomicLong(sanitizedEpoch))
    writePrivateField("applied", applied)
    writePrivateField("cleanupDebt", debt)
    writePrivateField("serviceActivated", serviceActivated)
    writePrivateField("nextDebtGeneration", maxOf(1L, (debt?.generation ?: 0L) + 1L))
}

internal fun ProxyOnlyController.cleanupDebtForTrackATest(): CleanupDebt? =
    readPrivateField("cleanupDebt")

internal fun ProxyOnlyController.appliedForTrackATest(): AppliedProxyState? =
    readPrivateField("applied")

internal fun ProxyOnlyController.serviceActivatedForTrackATest(): Boolean =
    readPrivateField("serviceActivated")

internal suspend fun ProxyOnlyController.cleanupAppliedForTrackATest(
    reason: String = "track-a test",
    daemonAvailable: Boolean = true,
): CleanupReport = invokePrivateSuspend("cleanupApplied", reason, daemonAvailable)

internal suspend fun ProxyOnlyController.retryCleanupDebtForTrackATest() {
    invokePrivateSuspend<Unit>("retryCleanupDebtSafely")
}

internal suspend fun ProxyOnlyController.reconcileForTrackATest(state: DesiredProxyState) {
    invokePrivateSuspend<Unit>("reconcile", state)
}

private fun Any.findPrivateField(name: String): Field {
    var type: Class<*>? = javaClass
    while (type != null) {
        try {
            return type.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    error("No field '$name' on ${javaClass.name}")
}

private fun Any.writePrivateField(name: String, value: Any?) {
    findPrivateField(name).set(this, value)
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any.readPrivateField(name: String): T = findPrivateField(name).get(this) as T

@Suppress("UNCHECKED_CAST")
private suspend fun <T> Any.invokePrivateSuspend(name: String, vararg args: Any?): T =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = javaClass.declaredMethods.singleOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == args.size + 1 &&
                Continuation::class.java.isAssignableFrom(candidate.parameterTypes.last())
        } ?: error("No suspend method '$name' with ${args.size} arguments on ${javaClass.name}")
        method.isAccessible = true
        try {
            val result = method.invoke(this, *args, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as T
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
