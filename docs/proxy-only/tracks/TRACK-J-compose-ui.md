# Track J — Compose UI: proxy tab, toggle, live state (**this is what makes the option appear**)

**Why this exists:** this is the piece whose absence is the literal answer to "I don't see
the option." The app UI is Jetpack Compose with a `RootDestination` enum driving the
navigation bar (`VpnHotspotApp.kt`). No proxy destination, screen, or toggle exists. This
track adds them, binding to the `ProxyOnlyService` from Track I with the same
`rememberServiceBinder` + `collectAsStateWithLifecycle` pattern the tethering screen uses.

**Scope:** UI only. Requires Track I (service + binder) and Track H (settings keys).

**Files:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/ui/ProxyScreen.kt   (new)
mobile/src/main/java/be/mygod/vpnhotspot/ui/VpnHotspotApp.kt (add RootDestination + composable)
mobile/src/main/res/values/strings.xml                       (titles, state labels, actions)
mobile/src/main/res/drawable/ic_proxy.xml                    (nav icon)
```

---

## Step J1 — add the navigation destination

In `VpnHotspotApp.kt`, extend the `RootDestination` enum (currently Tethering/Clients/Settings):

```kotlin
private enum class RootDestination(
    val route: String,
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
) {
    Tethering("tethering", R.string.title_tethering, R.drawable.ic_wifi_tethering),
    Proxy("proxy", R.string.title_proxy, R.drawable.ic_proxy),   // NEW
    Clients("clients", R.string.title_clients, R.drawable.ic_devices),
    Settings("settings", R.string.title_settings, R.drawable.ic_settings),
}
```

Because the nav bar renders `RootDestination.entries`, adding the entry makes the tab
appear immediately. Then add the `composable` route inside the `NavHost` block, mirroring
the existing `Clients`/`Settings` blocks:

```kotlin
composable(RootDestination.Proxy.route) {
    val proxyBinder by rememberServiceBinder<ProxyOnlyService.ProxyBinder>(
        // bind only while the Proxy tab is visible, like tethering does
        rootDestination == RootDestination.Proxy,
        ProxyOnlyService::class.java,
    )
    RootDestinationScaffold(
        snackbarHostState = snackbarHostState,
        selectedDestination = RootDestination.Proxy,
        onNavigate = { navController.navigateRoot(it) },
        activeSnackbarPadding = route == RootDestination.Proxy.route,
        /* ...match the other scaffold args... */
    ) {
        ProxyScreen(binder = proxyBinder, snackbarHostState = snackbarHostState)
    }
}
```

> Copy the exact `RootDestinationScaffold(...)` argument list from the existing
> `Clients`/`Settings` blocks — they show the current required parameters.

---

## Step J2 — the screen

Bind the service state and render the toggle + typed state. Persisted enable uses the same
`app.pref.edit { }` pattern `SettingsScreen.kt` uses; live state comes from the binder.

```kotlin
package be.mygod.vpnhotspot.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.mygod.vpnhotspot.proxy.*

@Composable
fun ProxyScreen(
    binder: ProxyOnlyService.ProxyBinder?,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ProxyOnlyPreferences.enabled) }
    val state: ProxyOnlyState = binder?.state?.collectAsStateWithLifecycle()?.value
        ?: ProxyOnlyState.Disabled

    Column {
        // Master enable switch — persists and starts/stops the service.
        ListItem(
            headlineContent = { Text(stringResource(R.string.proxy_enable)) },
            supportingContent = { Text(stringResource(R.string.proxy_enable_summary)) },
            trailingContent = {
                Switch(checked = enabled, onCheckedChange = { checked ->
                    enabled = checked
                    ProxyOnlyPreferences.enabled = checked
                    val intent = Intent(context, ProxyOnlyService::class.java)
                    if (checked) {
                        // Foreground user action → issue a grant via ACTION_RESUME.
                        intent.action = ProxyOnlyService.ACTION_RESUME
                        androidx.core.content.ContextCompat.startForegroundService(context, intent)
                    } else context.stopService(intent)
                })
            },
        )

        // Typed state row + contextual action.
        ProxyStateRow(state = state, onResume = {
            val intent = Intent(context, ProxyOnlyService::class.java).apply {
                action = ProxyOnlyService.ACTION_RESUME
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        })

        // Running-only details: endpoint, UDP range, credentials reveal, FlClash snippet.
        if (state is ProxyOnlyState.Running) ProxyRunningDetails(state)
    }
}

@Composable
private fun ProxyStateRow(state: ProxyOnlyState, onResume: () -> Unit) {
    val (label, action) = when (state) {
        ProxyOnlyState.Disabled -> R.string.proxy_state_disabled to null
        ProxyOnlyState.ActivationRequired -> R.string.proxy_state_activation_required to onResume
        ProxyOnlyState.ServiceStarting -> R.string.proxy_state_starting to null
        ProxyOnlyState.WaitingForVpn -> R.string.proxy_state_waiting_vpn to null
        ProxyOnlyState.MultipleVpnCandidates -> R.string.proxy_state_multiple_vpn to null
        ProxyOnlyState.VpnPermissionDenied -> R.string.proxy_state_vpn_denied to null
        is ProxyOnlyState.Running -> R.string.proxy_state_running to null
        is ProxyOnlyState.FailClosed -> R.string.proxy_state_fail_closed to null
        ProxyOnlyState.CleanupDegraded -> R.string.proxy_state_cleanup to null
        else -> R.string.proxy_state_unknown to null
    }
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = {
            if (action != null) TextButton(onClick = action) {
                Text(stringResource(R.string.proxy_resume))
            }
        },
    )
}
```

> Match the `ProxyOnlyState` sealed hierarchy exactly (`ProxyModels.kt`). The `when` must
> be exhaustive; the compiler will tell you if a case is missing. `FailClosed` carries a
> typed reason — surface `reason` text in the supporting line for real diagnostics.

The `ActivationRequired` → Resume flow is the important UX: after a process restart the
feature shows "Tap Resume", exactly the README contract, and Resume sends `ACTION_RESUME`.

---

## Step J3 — running details (endpoint + FlClash snippet)

When `Running`, show the SOCKS5 endpoint, UDP range, per-client counters, and a copyable
FlClash snippet (see `FLCLASH_EXAMPLE.md`). Do **not** display IPv6 endpoints (Phase 7
rule). Credentials are reveal-on-tap and never logged.

---

## Step J4 — strings + icon

Add `title_proxy`, `proxy_enable`, `proxy_enable_summary`, `proxy_resume`, and one
`proxy_state_*` string per state, plus `res/drawable/ic_proxy.xml` (a vector). Provide
translations later; English is enough to ship the tab.

---

## Step J5 — verification (screenshots, not just build)

- `./gradlew assembleDebug` builds; the exhaustive `when` compiles against the real state type.
- Install and confirm the **Proxy** tab now appears in the bottom nav.
- Toggle on with no VPN present → UI shows `WaitingForVpn`/`FailClosed` (proves the whole
  chain H→I→J is wired, even before the backend exists).
- Kill and relaunch the app with `enabled=true` → tab shows `ActivationRequired` + Resume.
- Add a Compose UI test (or a manual QA checklist) asserting the tab is present and the
  switch persists across process death.

## Acceptance criteria

- A **Proxy** destination is visible in the navigation bar.
- The switch persists `ProxyOnlyPreferences.enabled` and starts/stops `ProxyOnlyService`.
- The screen renders the live typed `ProxyOnlyState` from the service binder.
- `ActivationRequired` offers a working Resume action.
- No IPv6 endpoint shown; credentials not logged.

## Dependencies

- Requires Track I (service + `ProxyBinder`) and Track H (`ProxyOnlyPreferences`).
- This is the minimum set (H+I+J) to make the option **appear and be interactive**;
  Track K makes the firewall side functional, and the Hev backend makes traffic actually flow.
