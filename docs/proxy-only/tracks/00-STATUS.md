# Proxy-only MVP status

Single source of truth for the proxy-only work on `agent/proxy-only-design`.

- PR: #1, intentionally **draft**
- Executable source verified: `a18473547e4818e3d37053b884d44042be3c0ab0`
- Normal `Test` workflow on that source: **passed**
- Dependency Review: **rerunning on the final documentation head**
- App-visible authenticated SOCKS5 MVP: **implemented**
- Track G composition review: **complete; three findings remediated and regression-tested**
- Track L Mermaid architecture, failure matrix and evidence map: **documented**
- Rooted-device validation runbook and UDP probe: **documented; execution pending**
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
| Transport-epoch acknowledgement fencing | ✅ | `ProxyDaemonState.kt`, Track G review |
| Root RPC cancellation transparency | ✅ | `RootProxyFirewallRpc.kt` |
| Root request/reply transport and lease | ✅ | `RootProxyFirewallRpc.kt`, `DaemonController.kt` |
| Root firewall ACL and global containment | ✅ | Rust `proxy_firewall` + `proxy_firewall_kernel.rs` |
| Authenticated TCP SOCKS5 CONNECT | ✅ | `KotlinSocks5Backend.kt` |
| SOCKS5 UDP ASSOCIATE | ✅ | `KotlinSocks5Backend.kt` |
| VPN-bound sockets and bounded VPN-aware DNS | ✅ code, 🟡 device evidence | `KotlinSocks5Backend.kt` |
| Track L architecture and packet-evidence map | ✅ | [`architecture/TRACK-L-kotlin-socks5-architecture.md`](../architecture/TRACK-L-kotlin-socks5-architecture.md) |
| Rooted-device validation procedure | ✅ documented, 🟡 execution pending | [`DEVICE_VALIDATION.md`](../DEVICE_VALIDATION.md) |
| Authenticated UDP validation probe | ✅ tooling | `tools/proxy_device_validation/socks5_udp_probe.py` |
| Physical-device packet/leak matrix | ⬜ evidence | required before release |

## User flow

1. Open the new **Proxy** tab.
2. Configure TCP port and optional UDP relay range.
3. Enable proxy sharing from the foreground UI.
4. The app generates encrypted username/password credentials and starts the special-use foreground
   service.
5. The service acquires a root-daemon lease and performs deny-first sanitation.
6. The controller requires exactly one usable Android VPN and at least one routable tethering
   downstream.
7. The Kotlin SOCKS5 listener starts, all required VPN-bound probes run, and the root firewall
   transitions from deny to exact interface + source IPv4 + source MAC admission.
8. The screen shows the IPv4 endpoint, credentials and UDP range.
9. VPN/root/probe/lifecycle failures close the listener and publish a typed fail-closed or cleanup
   state.

After process restart, `enabled=true` is only intent. No foreground service, root daemon or listener
starts until the user taps **Resume** and a new one-time grant is issued.

## Track results

| Track | Result |
| --- | --- |
| A — generation/session/epoch controller matrix | ✅ |
| B — authoritative daemon firewall protocol | ✅ |
| C — service-owned cleanup supervisor | ✅ |
| D — bounded cleanup failure history | ✅ |
| E — normalization diagnostics | ✅ |
| F — dependency graph/review workflow | ✅ |
| G — acknowledgement-backed daemon composition | ✅ reviewed; late-ack, malformed-identity, and cancellation gaps remediated; device restart evidence pending |
| H — settings, credentials and desired-state source | ✅ |
| I — production foreground service | ✅ |
| J — Compose UI | ✅ |
| K — root RPC transport and daemon lease | ✅ |
| L — Kotlin SOCKS5 MVP data plane | 🟡 code complete; architecture documented; device evidence pending |

## Track G review result

The focused review found and fixed three composition-boundary defects:

1. An acknowledgement already in flight could previously restore daemon health after an explicit
   transport disconnect. Requests are now fenced to a transport epoch, and old-epoch replies fail
   closed.
2. `OK`/stale acknowledgements with a missing or zero daemon identity could retain an older healthy
   state. Malformed authoritative identities now invalidate the matching epoch and return protocol
   failure.
3. `RootProxyFirewallRpc` could wrap `CancellationException` as `IOException`, misclassifying
   structured cancellation as daemon loss. Cancellation now propagates unchanged.

Deterministic JVM regressions cover all three findings. The remaining Track G boundary is physical:
DV-09 in the device runbook must kill the real daemon, prove the old runtime becomes unreachable, and
show deny-first recovery under a new acknowledgement identity.

## Security and lifecycle properties implemented

- No runtime starts over unresolved cleanup debt.
- Persisted enable state is not an activation grant.
- Exactly one VPN transport is required.
- Every outbound TCP/UDP socket is bound to that VPN before use.
- Domain resolution uses the selected VPN `Network`, with two-way concurrency limiting and a
  five-second caller deadline.
- The blocking platform resolver may outlive a timed-out caller; this bounded cancellation boundary
  and the future async `DnsResolver` option are documented in the Track L architecture companion.
- SOCKS5 username/password authentication is mandatory.
- TCP relay output is flushed for every copied chunk, preserving interactive and small responses.
- UDP replies are accepted only from a bounded set of remote address/port pairs requested by the
  client, and the success reply advertises a reachable downstream relay address.
- Firewall sanitation is deny-first and identity-checked in the root daemon.
- IPv4 admission requires downstream interface + client IPv4 + client MAC.
- Allowed rules are followed by per-downstream and unconditional proxy-port rejects, preventing
  wildcard listener access from every other interface.
- IPv6 has no allow path and proxy ports are rejected globally.
- Daemon session/epoch/generation values come only from acknowledgements.
- An explicit transport disconnect invalidates the request epoch, so an old in-flight reply cannot
  restore daemon health.
- Authoritative acknowledgements with missing or zero identity fail closed and clear the matching
  transport epoch instead of retaining older health.
- Coroutine cancellation is not rewritten as transport failure.
- Idle root-daemon lifetime is explicit through a reference-counted lease.
- Disable closes the backend and releases root-backed monitoring/transport even while UI remains
  bound.
- Backend shutdown joins the entire runtime coroutine tree.
- Foreground stop operations run on the main thread; OS-driven service destruction performs ordered
  fallback cleanup without blocking the main thread.

## Verification on executable head

The normal `Test` workflow passed on `a18473547e4818e3d37053b884d44042be3c0ab0`:

- Rust daemon check: passed
- Rust daemon unit tests, including global non-downstream rejects: passed
- Rust clippy with `-D warnings`: passed
- Rust dependency audit: passed
- Android debug assembly: passed
- Android lint: passed
- JVM tests, including the Track G disconnect/malformed-identity/cancellation regressions: passed
- release R8 coroutine-debug verification: passed
- APK and report artifacts: uploaded

A GitHub-hosted runner setup failure occurred on an intermediate head before checkout. It did not run
repository code. The final documentation head reruns Dependency Review and the complete Test workflow.

## Rooted-device evidence plan

[`DEVICE_VALIDATION.md`](../DEVICE_VALIDATION.md) defines the first repeatable hardware pass and its
sensitive evidence bundle. It covers:

- foreground activation and deny-first startup;
- authenticated TCP CONNECT and auth rejection;
- real UDP ASSOCIATE using the included Python probe;
- exact client/interface/MAC containment and IPv6 denial;
- tether/VPN/uplink packet capture with explicit leak interpretation;
- VPN loss and DNS-blackhole fail-closed behavior;
- real daemon restart and Track G identity recovery;
- process death and explicit Resume;
- 20-cycle FD/task/RSS/listener baselines; and
- UI, notification, clipboard, and configuration QA.

These tests have **not** been executed by this documentation/code-review pass.

## Remaining release blockers

1. Execute the rooted-device runbook and retain its evidence package.
2. Validate VPN include/exclude and permission-denial behavior.
3. Prove TCP CONNECT and UDP ASSOCIATE interoperability from real tethered clients.
4. Prove DNS blackhole, VPN loss, and absence of physical-network fallback by packet capture.
5. Restart the real daemon while active and complete the Track G/K recovery evidence.
6. Complete repeated enable/disable and process-death FD/thread/coroutine leak measurements.
7. Complete phone/large-screen screenshots and notification-permission QA.

The former Hev/JNI pin is no longer required for this Kotlin MVP. Hev may still be evaluated as a
later performance backend behind the same `ProxyBackend` interface, but it must not replace the
Kotlin path without completing its original hook, DNS, UDP and lifecycle evidence.

## Track and evidence index

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
- [Rooted-device validation runbook](../DEVICE_VALIDATION.md)
- [Architecture diagram index](../architecture/README.md)
- [Track L architecture diagrams](../architecture/TRACK-L-kotlin-socks5-architecture.md)
