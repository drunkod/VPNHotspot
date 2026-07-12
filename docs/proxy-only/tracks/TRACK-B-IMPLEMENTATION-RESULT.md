# Track B implementation result

Status: **complete and verified**

Verified source head: `229a7459f3d0ce1587e0cdf2a39225d8437cb782`

## Delivered boundary

- The existing daemon control envelope carries typed proxy-firewall sanitation, start,
  replace, deny and stop commands plus typed acknowledgements.
- Every handle mutation targets a daemon-issued `(session_id, epoch)` pair.
- Rust validates the token and performs the kernel mutation under one serialized
  daemon-side mutex. Stale commands are acknowledged before the kernel backend runs.
- Sanitation accepts an explicit deny-only containment configuration, installs the
  containment rules, clears the runtime ledger and only then advances the epoch.
- The daemon session ID is stored in a crash-persistent fsync'd counter protected by
  an inter-process file lock; serial and concurrent boot tests verify uniqueness.
- The applied-runtime ledger issues non-zero handles and rejects non-increasing
  replacement generations.
- The Android daemon backend installs dedicated IPv4/IPv6 chains without a
  delete-before-deny window. IPv4 allow rules require interface, client IPv4 and MAC;
  IPv4 terminates in reject and IPv6 listener/relay ports remain rejected.
- Daemon-wide routing cleanup removes proxy chains while holding the same proxy state
  lock and advances the sanitation epoch only after successful cleanup.
- `DaemonProxyFirewallClient` uses the project Wire models, trusts acknowledgement
  identity/handles, sends exact handle tokens and maps stale cleanup acknowledgements
  to non-resolving failures.

## Verification

At the verified source head:

- `cargo check --locked --all-targets` — passed
- `cargo test --locked --lib` — passed
- `cargo clippy --locked --all-targets -- -D warnings` — passed
- `cargo audit` — passed
- `./gradlew assembleDebug check --no-daemon` — passed
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon` — passed
- Dependency Review with `fail-on-severity: moderate` — passed

## Remaining integration boundary

Track B implements and verifies the daemon protocol boundary and Kotlin adapter. The
actual foreground service and concrete `ProxyFirewallRpc` composition remain part of
production service integration/Track C work; this result does not claim that the
user-facing Proxy-only feature is complete.
