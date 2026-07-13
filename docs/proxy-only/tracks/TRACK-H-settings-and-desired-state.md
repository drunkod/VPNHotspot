# Track H — settings persistence + `DesiredProxyState` source

**Why this exists:** the proxy-only feature is invisible in the app because nothing
persists `ProxyOnlySettings` and nothing produces a `Flow<DesiredProxyState>` for
`ProxyOnlyController` to consume. `ProxyOnlySettings` is a bare data class today. This
track adds the settings store and the desired-state flow, using the app's real
conventions: `App.pref` (`SharedPreferences` on `deviceStorage`) and the
`rememberPreference*` helpers used by `SettingsScreen.kt`.

**Scope:** persistence + state assembly only. No UI (Track J), no service (Track I),
no RPC (Track K).

**Files added:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyOnlyPreferences.kt   (new)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyDesiredStateSource.kt (new)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyActivationGrants.kt   (new)
mobile/src/test/java/be/mygod/vpnhotspot/proxy/ProxyDesiredStateSourceTest.kt (new)
```

---

## Step H1 — settings persistence over `App.pref`

The app stores settings in `App.pref` (`PreferenceManager.getDefaultSharedPreferences(deviceStorage)`,
see `App.kt:171`). Keys live as companion constants (cf. `LocalOnlyHotspotService.KEY_USE_SYSTEM`,
`BootReceiver.KEY`). Mirror that.

```kotlin
package be.mygod.vpnhotspot.proxy

import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app

/**
 * Persisted proxy-only configuration, backed by the app's default SharedPreferences.
 * Fail-closed is NOT a stored toggle (README security contract): only user-facing
 * knobs are persisted.
 */
object ProxyOnlyPreferences {
    const val KEY_ENABLED = "proxy.enabled"
    const val KEY_TCP_PORT = "proxy.tcpPort"
    const val KEY_UDP_ENABLED = "proxy.udpEnabled"
    const val KEY_UDP_RANGE_START = "proxy.udpRangeStart"
    const val KEY_UDP_RANGE_END = "proxy.udpRangeEnd"
    const val KEY_MAX_UDP = "proxy.maxUdpAssociations"
    const val KEY_CREDENTIALS_VERSION = "proxy.credentialsVersion"

    private const val DEFAULT_TCP_PORT = 1080
    private const val DEFAULT_UDP_START = 20_000
    private const val DEFAULT_UDP_END = 20_100
    private const val DEFAULT_MAX_UDP = 32

    var enabled: Boolean
        get() = app.pref.getBoolean(KEY_ENABLED, false)
        set(value) = app.pref.edit { putBoolean(KEY_ENABLED, value) }

    fun current(): ProxyOnlySettings = ProxyOnlySettings(
        enabled = app.pref.getBoolean(KEY_ENABLED, false),
        tcpPort = app.pref.getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT),
        udpEnabled = app.pref.getBoolean(KEY_UDP_ENABLED, false),
        udpPortRange = app.pref.getInt(KEY_UDP_RANGE_START, DEFAULT_UDP_START)..
            app.pref.getInt(KEY_UDP_RANGE_END, DEFAULT_UDP_END),
        maxUdpAssociations = app.pref.getInt(KEY_MAX_UDP, DEFAULT_MAX_UDP),
        credentialsVersion = app.pref.getInt(KEY_CREDENTIALS_VERSION, 1),
    )
}
```

> Confirm the exact `ProxyOnlySettings` constructor arg names against `ProxyModels.kt`
> before wiring (the controller test uses `enabled/tcpPort/udpEnabled/udpPortRange/
> maxUdpAssociations/credentialsVersion`).

Credentials themselves are sensitive; store the encrypted blob the same way the model
expects (the controller reads them via `ProxyCredentialProvider`, wired in Track I).
For the first cut, generate-on-first-enable and keep them in `App.pref` behind a
version int; do not log them.

---

## Step H2 — settings change notifications as a Flow

`SharedPreferences` change listeners are the app's idiomatic reactive source. Expose a
cold `Flow` that re-reads settings whenever a proxy key changes.

```kotlin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun proxySettingsFlow(): Flow<ProxyOnlySettings> = callbackFlow {
    trySend(ProxyOnlyPreferences.current())
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("proxy.")) trySend(ProxyOnlyPreferences.current())
    }
    app.pref.registerOnSharedPreferenceChangeListener(listener)
    awaitClose { app.pref.unregisterOnSharedPreferenceChangeListener(listener) }
}
```

---

## Step H3 — activation grants (one-time, foreground-issued)

The controller's contract (`README` foreground-activation): a background process restart
must **not** activate the service just because `enabled=true`. A foreground user
action issues a one-time `ActivationGrant`; the controller consumes it exactly once.

```kotlin
package be.mygod.vpnhotspot.proxy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime store of one-time activation grants. A grant is issued only from a
 * foreground user action (Resume/enable) and consumed exactly once by the controller.
 * Not persisted: a process restart intentionally loses grants so the feature shows
 * ActivationRequired until the user acts again.
 */
object ProxyActivationGrants : ActivationGrantConsumer {
    private val live = ConcurrentHashMap.newKeySet<UUID>()

    fun issue(source: ActivationSource): ActivationGrant {
        val grant = ActivationGrant(
            id = UUID.randomUUID(),
            issuedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            source = source,
        )
        live.add(grant.id)
        return grant
    }

    fun isLive(id: UUID) = id in live

    override suspend fun consume(grantId: UUID) { live.remove(grantId) }
}
```

> Match `ActivationGrant`/`ActivationSource`/`ActivationGrantConsumer` to their real
> declarations in the proxy package. If `ActivationSource` doesn't exist yet, add a
> minimal `enum class ActivationSource { UserEnable, UserResume }`.

---

## Step H4 — assemble `DesiredProxyState`

Combine settings, the optional grant, VPN candidates, tethering downstreams, allowed
clients, and daemon health into the controller's input. VPN selection reuses the
existing `ProxyVpnSelector`; tethering downstreams come from the same interface data the
tethering screen already observes. Daemon health/generation are **overwritten** by the
Track G `ProxyDaemonComposition`, so here they can be placeholders.

```kotlin
package be.mygod.vpnhotspot.proxy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Produces the controller's desired-state stream. Track I supplies the concrete
 * upstream sources; Track G's composition replaces daemonHealthy/daemonGeneration
 * with acknowledgement-backed values before the controller consumes them.
 */
class ProxyDesiredStateSource(
    private val settings: Flow<ProxyOnlySettings>,
    private val pendingGrant: Flow<ActivationGrant?>,
    private val vpnSelection: Flow<VpnSelection>,
    private val downstreams: Flow<List<ManagedDownstream>>,
    private val allowedClients: Flow<List<AllowedClient>>,
) {
    fun states(): Flow<DesiredProxyState> = combine(
        settings, pendingGrant, vpnSelection, downstreams, allowedClients,
    ) { s, grant, vpn, down, clients ->
        DesiredProxyState(
            settings = s,
            activationGrant = grant,
            vpnSelection = vpn,
            downstreams = down,
            allowedClients = clients,
            // Placeholder — ProxyDaemonComposition overwrites these (Track G).
            daemonHealthy = false,
            daemonGeneration = null,
        ).normalized()
    }
}
```

The `.normalized()` call means invalid downstream/client records are dropped here; use
`normalizedWithReport()` (Track E) instead if you want to surface diagnostics in the UI.

---

## Step H5 — tests

```kotlin
class ProxyDesiredStateSourceTest {
    @Test fun disabledSettings_produceDisabledIntent() = runTest { /* enabled=false → settings.enabled false */ }
    @Test fun grantForwardedOnlyWhilePending() = runTest { /* pendingGrant emits then null */ }
    @Test fun normalizationApplied_dropsInvalidClient() = runTest {
        // feed an invalid-MAC client, assert it's absent from the emitted DesiredProxyState
    }
    @Test fun settingsFlowReemitsOnPrefChange() = runTest { /* toggle KEY_ENABLED, assert re-emit */ }
}
```

## Acceptance criteria

- `ProxyOnlyPreferences.current()` round-trips every field through `App.pref`.
- `proxySettingsFlow()` re-emits on any `proxy.*` key change and unregisters on close.
- A grant flows to `DesiredProxyState.activationGrant` only while pending; process
  restart loses it (no persistence).
- Emitted states are normalized (or reported via `normalizedWithReport`).
- Unit tests green under `./gradlew :mobile:testDebugUnitTest`.

## Dependencies

- Consumes Track A–G types unchanged. Blocks Track I (service assembles this source).
