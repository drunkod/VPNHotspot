# Track I — real foreground `ProxyService` (Android Service) + manifest

**Why this exists:** there is no `android.app.Service` for proxy-only and no `<service>`
entry in `AndroidManifest.xml`, so the OS has nothing to start and the UI has nothing to
bind. `proxy/ProxyService.kt` today is the Phase-0 `ProxyServiceCleanupOwner` — **not** an
Android Service. This track adds the real foreground service that owns the controller,
the Track G daemon composition, and the cleanup owner, and exposes `ProxyOnlyState` to the
UI through a `Binder` (the exact pattern `TetheringService`/`RepeaterService` use).

**Scope:** service lifecycle + manifest + binder. Consumes Track H (desired state) and
Track K (`ProxyFirewallRpc`). The `ProxyServiceClient` **backend** (Hev) remains a
separate later track; this service can run with a typed stub backend so the state machine
progresses to `StartingBackend`/`FailClosed` and the UI shows real state.

**Files:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyService.kt   (new — the Android Service)
mobile/src/main/AndroidManifest.xml                                  (add <service> + FGS type)
mobile/src/main/res/values/strings.xml                               (notification + subtype strings)
```

> Name it `ProxyOnlyService` to avoid colliding with the existing Phase-0
> `proxy/ProxyService.kt` (the cleanup owner). Rename the Phase-0 file to
> `ProxyServiceCleanupOwner.kt` in the same change if you want the names to read cleanly.

---

## Step I1 — the foreground service

Follow the app conventions seen in `TetheringService`/`RepeaterService`: `directBootAware`,
a foreground notification, an inner `Binder : android.os.Binder()` exposing `StateFlow`s,
and a service-scoped `CoroutineScope`. The service owns the cleanup supervisor for its
whole lifetime and cancels/joins the worker **before** closing the owner (the ordering
Track C documented).

```kotlin
package be.mygod.vpnhotspot.proxy

import android.app.Service
import android.content.Intent
import android.os.Binder
import be.mygod.vpnhotspot.App.Companion.app
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProxyOnlyService : Service() {
    companion object {
        const val ACTION_RESUME = "be.mygod.vpnhotspot.proxy.RESUME"
    }

    // Published state for the UI.
    private val _state = MutableStateFlow<ProxyOnlyState>(ProxyOnlyState.Disabled)
    inner class ProxyBinder : Binder() {
        val state: StateFlow<ProxyOnlyState> get() = _state.asStateFlow()
        /** Foreground user action → one-time grant that unblocks ActivationRequired. */
        fun resume() { pendingGrant.value = ProxyActivationGrants.issue(ActivationSource.UserResume) }
    }
    private val binder = ProxyBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupOwner = ProxyServiceCleanupOwner(parent = serviceScope.coroutineContext)
    private val pendingGrant = MutableStateFlow<ActivationGrant?>(null)

    private var composition: ProxyDaemonComposition? = null
    private var workerJob: Job? = null

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME) {
            pendingGrant.value = ProxyActivationGrants.issue(ActivationSource.UserResume)
        }
        startForegroundWithState()
        ensureRunning()
        return START_STICKY
    }

    private fun ensureRunning() {
        if (workerJob != null) return

        // Track G composition owns the RPC tracker + DaemonProxyFirewallClient and
        // overwrites daemonHealthy/daemonGeneration with acknowledgement-backed values.
        val rpc: ProxyFirewallRpc = RootProxyFirewallRpc()          // Track K
        val comp = ProxyDaemonComposition(rpc) { containmentConfig() }
        composition = comp

        val stateSink = object : ProxyStateSink {
            override suspend fun publish(state: ProxyOnlyState) {
                _state.value = state
                updateNotification(state)
            }
        }

        val controller = ProxyOnlyController(
            service = ProxyBackendServiceClient(/* Hev backend seam — later track */),
            firewall = comp.firewallClient,
            activationGrants = ProxyActivationGrants,
            credentialProvider = ProxyCredentialProviderImpl,   // Track H credentials
            stateSink = stateSink,
            reporter = ProxyErrorReporterImpl,
            scope = serviceScope,
            cleanupSupervisor = cleanupOwner.cleanupSupervisor,
        )

        val desired = comp.composeDesiredStates(
            ProxyDesiredStateSource(
                settings = proxySettingsFlow(),
                pendingGrant = pendingGrant,
                vpnSelection = ProxyVpnSelector.selectionFlow(),   // reuse existing selector
                downstreams = tetheringDownstreamsFlow(),          // reuse tethering iface data
                allowedClients = allowedClientsFlow(),
            ).states()
        )

        workerJob = controller.start(desired)
    }

    override fun onDestroy() {
        // Track C ordering: cancel + join the worker BEFORE closing the cleanup owner.
        runBlocking {
            workerJob?.cancelAndJoin()
            cleanupOwner.close()
            composition?.close()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun startForegroundWithState() {
        // Build a persistent notification; reuse the app's notification channel helper.
        // startForeground(NOTIFICATION_ID, buildNotification(_state.value))
    }
    private fun updateNotification(state: ProxyOnlyState) { /* notify with typed reason */ }
    private suspend fun containmentConfig(): ProxyFirewallConfig = /* deny-first config from settings */ TODO()
}
```

Key points, each grounded in an existing decision:

- **Grant on `ACTION_RESUME` only** — honors the "no background activation from persisted
  `enabled=true`" rule. `BootReceiver` must **not** start this service directly into an
  active state; it may start it into `ActivationRequired`.
- **`composeDesiredStates`** is the Track G seam that replaces `daemonHealthy`/
  `daemonGeneration` with acknowledgement-backed identity. Confirm the exact method name
  in `ProxyDaemonComposition`; if it differs, adapt.
- **Teardown order** (`cancelAndJoin` worker → `close` owner) is the exact contract Track C
  documented; violating it strands terminal cleanup debt.

---

## Step I2 — manifest registration

Add alongside the existing services. Match `TetheringService`'s `specialUse` FGS type and
subtype property.

```xml
<service
    android:name=".proxy.ProxyOnlyService"
    android:directBootAware="true"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Keep an authenticated SOCKS5 proxy bound to the phone VPN active" />
</service>
```

> If a later backend needs a different FGS type (e.g. `connectedDevice`), revisit the
> subtype. Google Play's special-use FGS declaration form must be updated to match.

No new runtime permission is required for the service itself; the proxy binds sockets to a
VPN `Network` (Phase 1 backend) and issues root firewall commands via the existing daemon
channel (Track K) — neither needs a new manifest permission beyond what tethering already
declares.

---

## Step I3 — start-into-`ActivationRequired` on boot/open

- `MainActivity`/`BootReceiver`: when `ProxyOnlyPreferences.enabled == true`, start
  `ProxyOnlyService` **without** a grant so it publishes `ActivationRequired` (persistent
  notification + a Resume action). The UI's Resume button (Track J) sends `ACTION_RESUME`.
- Mirror `BootReceiver.startIfEnabled()`'s existing pattern; add a proxy branch behind
  `ProxyOnlyPreferences.enabled`.

---

## Step I4 — tests

Service classes are awkward under pure JVM tests; keep logic testable by delegating to the
already-tested controller/composition and cover the thin service seams with Robolectric or
an instrumented test:

- binder publishes the controller's `ProxyOnlyState` transitions;
- `ACTION_RESUME` issues exactly one grant and clears `ActivationRequired`;
- `onDestroy` cancels+joins the worker before `cleanupOwner.close()` (assert ordering via a
  test double owner that records call order).

## Acceptance criteria

- `ProxyOnlyService` is registered in the manifest with a valid FGS type and starts foreground.
- Binder exposes `StateFlow<ProxyOnlyState>`; the UI can observe live state.
- No background auto-activation: persisted `enabled` starts into `ActivationRequired`, not `Running`.
- Teardown cancels/joins the worker before closing the cleanup owner.
- `./gradlew assembleDebug` builds with the new service; lint passes the manifest.

## Dependencies

- Requires Track H (desired-state source) and Track K (`RootProxyFirewallRpc`).
- The `ProxyServiceClient` backend (Hev) is stubbed here; real traffic needs the backend track.
- Blocks Track J (UI binds this service).
