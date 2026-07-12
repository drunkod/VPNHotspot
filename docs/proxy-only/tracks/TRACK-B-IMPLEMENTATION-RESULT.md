# Track B implementation result

Status: **complete and verified, including round-13 remediation**

Verified clean source head: `e2492a5e3838013b089600f7b9631f9f6be01cee`

Resolution audit: [implementation review round 13](../REVIEW_IMPLEMENTATION_ROUND13.md)

## Delivered boundary

- The existing daemon control envelope carries typed proxy-firewall sanitation, start,
  replace, deny and stop commands plus typed acknowledgements.
- Every handle mutation targets a daemon-issued `(session_id, epoch)` pair.
- Rust validates the token and performs the kernel mutation under one serialized
  daemon-side mutex. Stale commands are acknowledged before the kernel backend runs.
- Sanitation accepts an explicit deny-only containment configuration, installs the
  containment rules, clears the runtime ledger and only then advances the epoch.
- The daemon session ID is stored in a crash-persistent fsync'd counter protected by
  an inter-process file lock; serial, threaded and independent-process tests verify
  uniqueness.
- Stale session-counter temporary files are removed while the store lock is held.
- The applied-runtime ledger issues non-zero handles, rejects a second active runtime,
  rejects non-increasing replacement generations and never recycles removed handles.
- Deny-first start rejects any configuration containing allowed clients.
- The Android daemon backend installs dedicated IPv4/IPv6 chains without a
  delete-before-deny window. IPv4 allow rules require interface, client IPv4 and MAC;
  IPv4 terminates in reject and IPv6 listener/relay ports remain rejected.
- Daemon-wide routing cleanup executes under the proxy state lock and cannot clear the
  ledger or advance the epoch unless kernel cleanup succeeds.
- `DaemonProxyFirewallClient` uses the project Wire models, trusts acknowledgement
  identity/handles, sends exact handle tokens and maps stale cleanup acknowledgements
  to non-resolving failures.
- Sanitation transport `IOException` follows the controller's `null`/cleanup-debt gate
  and clears any previously cached sanitation token.
- `SanitationResult` carries the acknowledgement-issued daemon generation. Primary
  reconciliation and cleanup-debt retry require it to match the observed desired-state
  generation; mismatches retain sanitation debt and prevent start.

## Verification

At the verified clean source head:

- `cargo check --locked --all-targets` — passed
- `cargo test --locked --lib` — passed
- `cargo clippy --locked --all-targets -- -D warnings` — passed
- `cargo audit` — passed
- `./gradlew assembleDebug check --no-daemon` — passed
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon` — passed
- Dependency Review with `fail-on-severity: moderate` — passed

The normal least-privilege Test workflow was restored and all temporary remediation
workflows/scripts were removed before the final verification.

## Remaining integration boundary

Track B implements and verifies the daemon protocol boundary and Kotlin adapter. The
actual foreground service and concrete `ProxyFirewallRpc`/health composition remain part
of production service integration/Track C work. That composition must source the
observed daemon generation from the same acknowledgement identity; the controller now
rejects any disagreement rather than accepting divergent clocks.

This result does not claim that the user-facing Proxy-only feature is complete.
