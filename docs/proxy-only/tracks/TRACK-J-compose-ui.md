# Track J — Compose proxy UI

Status: **implemented and build-verified; physical UI QA pending**

## Result

The proxy feature is now visible and interactive in the app.

`RootDestination.Proxy` is part of the existing adaptive root navigation, so it appears in
both the bottom navigation bar and navigation rail. The route binds `ProxyOnlyService` with
the same lifecycle-aware service-binding helper used by the tethering screens.

## Screen contents

`ProxyScreen` provides:

- a persisted master enable switch;
- an exhaustive live rendering of every `ProxyOnlyState` variant;
- typed details for multiple VPNs, fail-closed reasons and cleanup debt;
- a Resume action for `ActivationRequired`;
- configurable TCP port;
- UDP ASSOCIATE enable state and relay port range;
- generated username/password display, copy and rotation actions;
- running IPv4 SOCKS5 endpoint and UDP range display.

Changing configuration flows through `ProxyOnlyPreferences` and reconciles the active
controller runtime. Invalid port ranges are rejected in the screen before persistence.
Configuration-aware `stringResource` values are captured before asynchronous snackbar calls,
so Compose lint and locale changes are handled correctly.

## Activation UX

After process restart, persisted enable state does not start a foreground service. Opening the
Proxy tab shows `ActivationRequired`; tapping Resume performs the foreground user action and
issues a new one-time activation grant.

Disabling uses the service command path rather than directly killing the service, allowing the
controller to close the listener, deny/stop firewall state and drain cleanup debt in order.

## Files

- `ui/ProxyScreen.kt`
- `ui/VpnHotspotApp.kt`
- `res/drawable/ic_proxy.xml`
- `res/values/proxy_strings.xml`

## Verification

The exhaustive state rendering, navigation integration, resources and manifest references pass
Android compilation and lint on executable head
`a5fe4dd574faba2a6b29d5e55e72af5526798631`.

## Remaining device evidence

A physical-device/UI pass must still capture screenshots and verify navigation, notification
permission behavior, clipboard UX, configuration changes while running and process-restart
Resume behavior on supported phone and large-screen layouts.
