# Track I — production Android foreground service

Status: **implemented and verified**

## Result

`ProxyOnlyService` is now the Android lifecycle owner for proxy-only mode. It is registered
as a non-exported `specialUse` foreground service and follows the app's existing bound-service
pattern.

## Ownership

The service owns, for one service lifetime:

- `ProxyOnlyController`;
- `ProxyServiceCleanupOwner` and its supervisor-owned retry scope;
- `ProxyDaemonComposition` and concrete root RPC adapter;
- the Kotlin SOCKS5 backend;
- live settings, VPN, downstream and allowed-client sources;
- the root-daemon transport lease;
- the foreground notification and UI binder.

The binder exposes live `StateFlow`s for controller state, settings and credentials, and
provides explicit enable, resume, disable and credential-rotation commands.

## Activation contract

The service is deliberately `START_NOT_STICKY`.

- A persisted `enabled=true` bit never starts a foreground service or root process.
- Binding the screen after process restart exposes `ActivationRequired`.
- Only foreground enable/resume commands issue a process-local one-time grant.
- Android 12+ foreground-start rejection maps back to `ActivationRequired`.
- Root-backed client monitoring and daemon bootstrap begin only after foreground activation
  succeeds.

This preserves the Track A/C requirement that persistence is not authority to reactivate a
network listener.

## Foreground and waiting behavior

After activation, the service stays foreground through waiting and fail-closed states while
the backend listener is closed. Notifications show the typed controller state and include a
Resume action when user activation is required. The three-argument `startForeground` call and
`FOREGROUND_SERVICE_TYPE_SPECIAL_USE` are used only on Android 14+, with the compatible call
used on earlier supported versions.

## Root-daemon lifecycle

The service acquires an explicit reference-counted `DaemonController.Lease` before bootstrap.
The lease keeps one daemon identity alive between individual request/reply calls and is released
immediately after authoritative feature stop, even if the UI remains bound. Re-enable creates a
fresh bootstrap job and lease.

## Teardown ordering

The implemented ordering is:

1. controller closes backend/firewall resources and releases daemon resources on feature stop;
2. service destruction cancels and joins the controller worker;
3. the cleanup owner is closed only after the worker exits;
4. any remaining bootstrap job and daemon lease are closed;
5. the service scope is cancelled.

Failed cleanup retains handles/debt and does not falsely report a completed stop.

## Files

- `proxy/ProxyOnlyService.kt`
- `AndroidManifest.xml`
- `values/proxy_strings.xml`
- `drawable/ic_proxy.xml`

## Verification

Manifest merge, Android assembly, lint, JVM tests and release R8 all passed on executable head
`a5fe4dd574faba2a6b29d5e55e72af5526798631`.

## Remaining device evidence

Instrumentation/device runs are still required for Android foreground-service policy variants,
process death while enabled, rapid disable/re-enable, and long-running cleanup retry behavior.
