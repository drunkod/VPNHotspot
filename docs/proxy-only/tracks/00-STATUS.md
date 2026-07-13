# Proxy-only MVP status

Single source of truth for the proxy-only work on `agent/proxy-only-design`.

- PR: #1, intentionally **draft**
- Executable source verified: `a5fe4dd574faba2a6b29d5e55e72af5526798631`
- Normal `Test` and `Dependency Review` workflows: **passed**
- App-visible authenticated SOCKS5 MVP: **implemented**
- Rooted physical-device/security evidence: **still required before release**

## Legend

- ✅ implemented and covered by build/unit/Rust verification
- 🟡 implemented but physical-device or packet evidence remains
- ⬜ not implemented

## What is in the app

| Area | State | Main implementation |
| --- | --- | --- |
| Proxy navigation destination and screen | ✅ | `ui/ProxyScreen.kt`, `ui/VpnHotspotApp.kt` |
| Persisted ports/UDP/enable settings | ✅ | `proxy/ProxyOnlyPreferences.kt` |
| Android-Keystore-backed credentials | ✅ | `ProxyCredentialStore` |
| One-time foreground activation grants | ✅ | `proxy/ProxyActivationGrants.kt` |
| Live VPN/downstream/client desired state | ✅ | `ProxyDesiredStateSource.kt`, `ProxyProductionSources.kt` |
| Production foreground service and binder | ✅ | `proxy/ProxyOnlyService.kt`, manifest registration |
| Controller and cleanup-debt supervisor | ✅ | Tracks A, C, D |
| Acknowledgement-backed daemon identity | ✅ | Tracks B, G |
| Root request/reply transport and lease | ✅ | `RootProxyFirewallRpc.kt`, `DaemonController.kt` |
| Root firewall ACL and global containment | ✅ | Rust `proxy_firewall` + `proxy_firewall_kernel.rs` |
| Authenticated TCP SOCKS5 CONNECT | ✅ | `KotlinSocks5Backend.kt` |
| SOCKS5 UDP ASSOCIATE | ✅ | `KotlinSocks5Backend.kt` |
| VPN-bound sockets and VPN-aware DNS | ✅ code, 🟡 device evidence | `KotlinSocks5Backend.kt` |
| Physical-device packet/leak matrix | ⬜ | required before release |

## User flow

1. Open the new **Proxy** tab.
2. Configure TCP port and optional UDP relay range.
3. Enable proxy sharing from the foreground UI.
4. The app generates encrypted username/password credentials and starts the special-use
   foreground service.
5. The service acquires a root-daemon lease and performs deny-first sanitation.
6. The controller requires exactly one usable Android VPN and at least one routable tethering
   downstream.
7. The Kotlin SOCKS5 listener starts, all required VPN-bound probes run, and the root firewall
   transitions from deny to exact interface + source IPv4 + source MAC admission.
8. The screen shows the IPv4 endpoint, credentials and UDP range.
9. VPN/root/probe/lifecycle failures close the listener and publish a typed fail-closed or cleanup
   state.

After process restart, `enabled=true` is only intent. No foreground service, root daemon or
listener starts until the user taps **Resume** and a new one-time grant is issued.

## Track results

| Track | Result |
| --- | --- |
| A — generation/session/epoch controller matrix | ✅ |
| B — authoritative daemon firewall protocol | ✅ |
| C — service-owned cleanup supervisor | ✅ |
| D — bounded cleanup failure history | ✅ |
| E — normalization diagnostics | ✅ |
| F — dependency graph/review workflow | ✅ |
| G — acknowledgement-backed daemon composition | ✅ |
| H — settings, credentials and desired-state source | ✅ |
| I — production foreground service | ✅ |
| J — Compose UI | ✅ |
| K — root RPC transport and daemon lease | ✅ |
| L — Kotlin SOCKS5 MVP data plane | 🟡 code complete; device evidence pending |

## Security properties implemented

- No runtime starts over unresolved cleanup debt.
- Persisted enable state is not an activation grant.
- Exactly one VPN transport is required.
- Every outbound TCP/UDP socket is bound to that VPN before use.
- Domain resolution uses the selected VPN `Network`.
- SOCKS5 username/password authentication is mandatory.
- Firewall sanitation is deny-first and identity-checked in the root daemon.
- IPv4 admission requires downstream interface + client IPv4 + client MAC.
- Allowed rules are followed by per-downstream and unconditional proxy-port rejects, preventing
  wildcard listener access from every other interface.
- IPv6 has no allow path and proxy ports are rejected globally.
- Daemon session/epoch/generation values come only from acknowledgements.
- Idle root-daemon lifetime is explicit through a reference-counted lease.
- Disable closes the backend and releases root-backed monitoring/transport even while UI remains
  bound.

## Verification on executable head

- Rust daemon check: passed
- Rust daemon unit tests, including global non-downstream rejects: passed
- Rust clippy with `-D warnings`: passed
- Rust dependency audit: passed
- Android debug assembly: passed
- Android lint: passed
- JVM tests, including Tracks A–K integration regressions: passed
- release R8 coroutine-debug verification: passed
- Dependency Review with moderate-severity failure policy: passed
- APK and report artifacts: uploaded

## Remaining release blockers

These are evidence tasks, not missing app wiring:

1. Rooted physical-device installation and foreground-service behavior across supported Android
   versions.
2. VPN include/exclude and permission-denial matrix.
3. TCP CONNECT and UDP ASSOCIATE interoperability from real tethered clients.
4. DNS blackhole, VPN loss and packet-capture proof of no physical fallback.
5. Real daemon restart while a runtime is active.
6. Repeated enable/disable and process-death FD/thread/coroutine leak measurements.
7. UI screenshots and large-screen/notification-permission QA.

The former Hev/JNI pin is no longer required for this Kotlin MVP. Hev may still be evaluated as
a later performance backend behind the same `ProxyBackend` interface, but it must not replace
the Kotlin path without completing its original hook, DNS, UDP and lifecycle evidence.

## Track index

- [Track A](TRACK-A-generation-matrix-tests.md)
- [Track B](TRACK-B-daemon-protocol-enforcement.md)
- [Track C](TRACK-C-cleanup-supervisor.md)
- [Track D](TRACK-D-bounded-failure-history.md)
- [Track E](TRACK-E-normalization-diagnostics.md)
- [Track F](TRACK-F-dependency-review-ci.md)
- [Track G](TRACK-G-daemon-health-composition.md)
- [Track H](TRACK-H-settings-and-desired-state.md)
- [Track I](TRACK-I-foreground-service.md)
- [Track J](TRACK-J-compose-ui.md)
- [Track K](TRACK-K-root-rpc-transport.md)
- [Track L](TRACK-L-kotlin-socks-backend.md)
