# Track H — settings persistence + desired-state composition

Status: **implemented and verified**

## Result

The app now has a production settings and state-composition layer for proxy-only mode.

### Persisted settings

`ProxyOnlyPreferences` stores the user-facing configuration in the app's existing
`App.pref` default preferences:

- enabled state;
- TCP listener port;
- UDP enable state and relay range;
- maximum concurrent UDP associations;
- credential version.

Values are range-checked when read and written. `proxySettingsFlow()` uses a
`SharedPreferences.OnSharedPreferenceChangeListener`, emits an initial snapshot, re-reads
on every `proxy.*` change and unregisters when collection ends.

### Credentials

`ProxyCredentialStore` implements `ProxyCredentialProvider` and generates credentials on
first use. The encrypted credential blob is stored in preferences, while its AES key is
created in Android Keystore with AES/GCM and no plaintext secret is included in model
`toString()` output or logs. Rotating credentials increments `credentialsVersion`, forcing
the controller to replace the active runtime.

### Activation grants

`ProxyActivationGrants` is process-local and one-time:

- enable and resume actions issue a UUID grant stamped with elapsed realtime;
- the controller consumes the exact grant after foreground activation succeeds;
- grants are never persisted;
- process restart with `enabled=true` therefore produces `ActivationRequired` instead of
  silently restarting the feature.

### Live application inputs

`ProxyDesiredStateSource` combines:

1. persisted settings;
2. the pending activation grant;
3. exactly-one-VPN selection from `ConnectivityManager` callbacks;
4. active tethering/local-only interfaces and their IPv4 addresses;
5. valid IPv4 neighbour records mapped to MAC/IP client bindings.

The resulting `DesiredProxyState` is normalized before it reaches the controller. Track G's
`ProxyDaemonComposition` then overwrites the placeholder daemon fields with the identity
from actual daemon acknowledgements.

Root-backed neighbour monitoring is gated on successful foreground activation; merely
opening the Proxy screen after process restart does not start the root daemon.

## Files

- `proxy/ProxyOnlyPreferences.kt`
- `proxy/ProxyActivationGrants.kt`
- `proxy/ProxyDesiredStateSource.kt`
- `proxy/ProxyProductionSources.kt`
- `ProxyDesiredStateSourceTest.kt`

## Verification

`ProxyDesiredStateSourceTest` verifies canonical downstream selection, client merge and
normalization, placeholder daemon state, grant forwarding and later preference emissions.
The complete Android/JVM workflow passed on executable head
`a5fe4dd574faba2a6b29d5e55e72af5526798631`.

## Remaining device evidence

A physical-device pass must still verify the exact interface/address observations for Wi-Fi,
USB, Ethernet and local-only hotspot variants on supported Android releases.
