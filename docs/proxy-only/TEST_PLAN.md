# Proxy-only security and test plan

## 1. Security properties

The feature is acceptable only if all properties hold:

1. The candidate upstream has current `NetworkCapabilities.TRANSPORT_VPN` capability before startup and before publishing `Running`.
2. A user override of `Upstreams.primary` to a physical interface cannot satisfy the proxy VPN requirement.
3. The VPN Hotspot app UID is permitted to use the selected VPN; exclusion/denial produces a clear fail-closed error.
4. Every Internet-facing TCP and UDP socket is bound to the validated VPN `Network` before sending traffic.
5. Domain-form requests use the same VPN-specific resolver path.
6. VPN loss, network replacement, binding failure or DNS failure never causes physical-network fallback.
7. The listener is reachable only from explicitly allowed tethering downstreams.
8. Authentication is required for every LAN listener.
9. IPv4 allow rules require downstream interface + source IPv4 + source MAC.
10. IPv6 cannot reach the IPv4-only MVP listener or UDP relay range.
11. A blocked/disconnected client cannot create new TCP or UDP sessions and active sessions are closed.
12. UDP associations are tied to their control TCP connection and cannot be hijacked by another client.
13. Root-daemon loss closes the native listener immediately; stale kernel allow rules are never relied on as deny state.
14. Cleanup removes app-owned firewall/listener state after normal stop, recovery and reboot.
15. Existing VPN-routing behaviour is unchanged unless the user selects `PROXY_ONLY`.
16. Credentials, destination metadata and network handles are redacted from logs/crash reports.

## 2. Threat model and controls

### Non-VPN upstream masquerading as VPN

Threat: `service.upstream` selects `wlan0` or another physical network and proxy code trusts `Upstreams.primary`.

Controls:

- use `Upstreams.vpn` or explicit capability validation;
- re-read capabilities before startup;
- reject candidates without `TRANSPORT_VPN`;
- unit/instrumentation tests with a physical primary override.

### Per-app VPN exclusion

Threat: the VPN app excludes VPN Hotspot, causing `android_setsocknetwork()`/`Network.bindSocket()` to fail with permission-related errors.

Controls:

- Phase 0 allowed/disallowed app matrix;
- startup probe owned by app UID;
- stable `VpnPermissionDenied` state and troubleshooting text;
- no fallback to physical network.

### Open proxy exposure

Threat: wildcard or multi-interface listener becomes reachable from upstream Wi-Fi, cellular, VPN, loopback or unrelated interfaces.

Controls:

- root deny runtime created before listener;
- IPv4 allow only for configured downstream + IP + MAC;
- IPv4 terminal reject;
- IPv6 reject for TCP port and UDP range;
- authentication;
- listener closure if root policy/channel is unavailable;
- interface scan tests for both families.

### VPN bypass in native fork

Threat: a retry, resolver or UDP socket skips the prepare hook, or hook failure is ignored.

Controls:

- one centralized/injected hook seam;
- host-CI shim that counts and fails calls;
- close FD on any fail-closed hook error;
- Android invalid-handle test;
- runtime hook success/failure metrics;
- packet capture and public-IP checks.

### Blocking/leaking DNS

Threat: domain requests use process-default DNS or block all Hev workers during resolver timeout.

Controls:

- network-aware resolver only;
- bounded resolver concurrency/queue;
- strict timeout/cancellation;
- blackholed DNS stress test;
- physical-interface DNS capture;
- fail on VPN generation loss.

### Client identity spoofing

Threat: DHCP/static-IP reuse lets one client inherit another client's IP-only allow rule.

Controls:

- match input interface + source IPv4 + source MAC;
- deny interfaces where reliable MAC identity is unavailable;
- neighbour updates replace rules promptly;
- test deliberate source-IP reuse and MAC mismatch.

### UDP port exposure and hijacking

Threat: Hev allocates ephemeral relay ports outside firewall policy, or a second client sends packets into another association.

Controls:

- constrain Hev to an explicit UDP relay range;
- proto/firewall carry start/end, not one assumed UDP port;
- source peer validation in native backend;
- lifetime tied to control TCP;
- reject `FRAG != 0`;
- bounded associations and idle timeout.

### Root-daemon death

Threat: iptables allow rules survive daemon death while ACL/counters stop updating.

Controls:

- long-lived firewall call/channel as liveness signal;
- unexpected completion closes backend/listener immediately;
- no automatic restart until daemon is available;
- deterministic Clean or deny reconciliation before listener restart;
- integration test kills daemon with committed allow rules.

### Resource exhaustion and credential disclosure

Controls:

- per-client/global connection limits;
- handshake, DNS and idle timeouts;
- bounded buffers, resolver queue and UDP table;
- FD/memory monitoring and fuzz tests;
- encrypted app-private credentials, backup exclusion and redaction;
- no command-line secrets or password in routine notification.

## 3. Phase 0 device tests

### VPN capability selection

1. Start a VPN and leave `service.upstream` at default: candidate accepted.
2. Set `service.upstream` to physical `wlan0`: routing mode may use it, proxy selector rejects it.
3. Remove the VPN capability during selection/start: startup fails closed.
4. Replace the VPN `Network` while interface name remains the same: backend fully restarts.

### Per-app VPN policy matrix

Test at least:

| VPN app policy | Expected result |
| --- | --- |
| All apps | TCP/UDP/DNS probes succeed through VPN |
| VPN Hotspot explicitly allowed | probes succeed |
| VPN Hotspot excluded/denied | binding fails, listener not exposed, clear error |
| Policy changes while running | listener closes, fail-closed state |

### Hev UDP behaviour

- record returned `BND.ADDR/BND.PORT` for multiple associations;
- prove every relay port is inside configured range;
- prove all relay socket paths invoke prepare hook;
- prove control TCP closure destroys relay state;
- prove range exhaustion fails safely.

### Resolver behaviour

With working and blackholed VPN DNS:

- measure one and many concurrent resolutions;
- verify unrelated TCP/UDP sessions continue while DNS stalls;
- verify timeout/cancellation bounds;
- verify no process-default or physical DNS packet appears.

### IPv6 behaviour

- scan TCP listener port over native IPv6 and IPv4-mapped paths;
- scan full UDP relay range over IPv6;
- confirm ip6tables rejects and no native listener accepts;
- confirm IPv4 SOCKS path remains functional.

## 4. Unit and host-native tests

### Kotlin

- migration defaults existing installations to `VPN_ROUTING`;
- settings validate TCP port and bounded UDP range;
- weak/empty LAN credentials are rejected;
- non-VPN candidate is rejected;
- VPN capability loss before commit is rejected;
- app-UID binding failure maps to `VpnPermissionDenied`;
- runtime key is independent of client ordering and irrelevant link churn;
- client updates perform replace, not restart;
- network-handle change performs deny/stop/start;
- desired-state worker never cancels an in-flight transaction;
- partial firewall/backend startup always rolls back;
- daemon channel loss closes backend before reporting fail-closed;
- credentials and generated config errors are redacted;
- FlClash YAML generation is syntactically valid/escaped.

### Native bridge/backend

- start returns unique opaque handles;
- stop is idempotent;
- no callback after final stop;
- repeated start/stop leaks no threads/FDs;
- ACL replacement does not change VPN network;
- backend has no live network replacement in MVP;
- native errors map to stable UI categories.

### Hev fork host CI

Using the injected bind shim:

- hook executes exactly once per outbound FD;
- TCP hook occurs before `connect()`;
- UDP hook occurs before first `connect()`/`sendto()`;
- resolver-created sockets are covered if applicable;
- all retry/address-family paths are covered;
- shim failure closes FD and prevents connect/send;
- auth accepts/rejects expected credentials;
- UDP `FRAG != 0` is dropped;
- relay ports stay inside configured range;
- association closes with control TCP.

The host target must not link to `android_setsocknetwork`; it tests the seam. Android instrumentation tests the real NDK implementation.

### Rust daemon

- proxy firewall uses existing `IptablesRule` ledger types;
- repeated apply is idempotent and duplicate cleanup uses `delete_repeated`;
- IPv4 policy starts denied and ends in reject;
- allow requires interface + IPv4 + MAC;
- MAC mismatch is denied even when IP matches;
- UDP range rules cover exactly start..end;
- IPv6 rules deny TCP port and UDP range;
- blocked/disconnected clients are removed on replacement;
- port/range changes replace old rules without broad allow window;
- proxy chains/jumps are removed by deterministic `firewall_cleanup::clean()`;
- counters identify MAC/downstream and distinguish TCP/UDP;
- failed mutations never leave a broader allow policy.

## 5. Rooted Android integration tests

### Baseline direct path

1. Enable ordinary Android Wi-Fi tethering.
2. Keep Proxy-only disabled.
3. Confirm laptop uses physical/carrier IP.
4. Enable phone VPN.
5. Confirm ordinary laptop traffic still uses physical/carrier IP.

### TCP proxy

1. Enable Proxy-only with validated VPN.
2. Use SOCKS5 hostname resolution.
3. Confirm visible IP is phone VPN exit.
4. Confirm unrelated direct browser/process traffic remains physical.
5. Test IPv4 literal and domain targets.
6. Confirm authentication failure creates no upstream socket.

```bash
curl --socks5-hostname USER:PASS@PHONE_IPV4:10808 https://ifconfig.me
```

### UDP proxy

Verify:

- DNS over UDP;
- controlled UDP echo;
- QUIC/HTTP3 where client supports SOCKS UDP;
- WARP WireGuard through FlClash `dialer-proxy`;
- returned relay port lies in configured range;
- association closes with control TCP;
- another client cannot inject into association;
- idle/range exhaustion cleanup.

### VPN loss and generation change

1. Start continuous TCP, UDP and DNS traffic.
2. Disable VPN or replace its network.
3. Confirm deny/stop occurs before new start.
4. Confirm all sessions close.
5. Confirm no request appears with physical IP.
6. Re-enable VPN.
7. Confirm new backend instance/handle is used only after complete validation and firewall reconciliation.

Repeat for Wi-Fi↔cellular underlay handoff, VPN reconnect, server change and another VPN taking ownership.

### ACL anti-spoofing

1. Connect two clients.
2. Verify both with distinct MAC/IP identities.
3. Block one client and confirm active/new sessions stop.
4. Assign/spoof the allowed client's previous IP from the blocked MAC.
5. Confirm interface+IP+MAC rule denies it.
6. Confirm the legitimate client remains operational.

### Interface and address-family exposure

Attempt TCP and UDP-range access from:

- allowed Wi-Fi client IPv4;
- allowed USB client IPv4;
- blocked/unknown downstream client;
- phone loopback;
- upstream Wi-Fi peer;
- cellular/VPN/unrelated interfaces where testable;
- every reachable IPv6 interface/address.

Only authenticated, explicitly allowed IPv4 downstream clients may succeed.

### Root-daemon kill test

1. Start proxy and confirm allow rules/listener are active.
2. Kill `vpnhotspotd` without first stopping the firewall runtime.
3. Confirm kernel rules may remain.
4. Confirm app detects channel loss and closes listener/sessions immediately.
5. Confirm clients cannot connect despite stale allow rules.
6. Restart daemon/app control connection.
7. Confirm Clean/deny reconciliation occurs before listener restart.
8. Confirm ACL/counters resume only after full commit.

### App-process kill and reboot

- kill app process: listener closes; stale rules cannot proxy without listener;
- next start runs Clean/deny before exposure;
- reboot leaves no active listener/rules;
- optional boot restore starts denied and waits for validated VPN+tethering.

## 6. FlClash acceptance

Expected public IP:

| Application route | Expected public IP |
| --- | --- |
| `DIRECT` | physical/carrier IP |
| `PhoneVPN` | phone VPN exit IP |
| `WARP-via-PhoneVPN` | Cloudflare WARP IP |

For WARP, verify Cloudflare trace plus native UDP association counters/log state. `warp=on` alone is not proof that SOCKS UDP used the intended path.

## 7. Corrected failure matrix

| Failure | Expected result |
| --- | --- |
| No root permission | no LAN listener exposure |
| Non-VPN primary override | rejected; no listener |
| VPN Hotspot excluded by VPN policy | binding error; fail-closed guidance |
| Firewall deny/start failure | backend not started or immediately stopped |
| Backend start/probe failure | firewall runtime rolled back |
| Invalid/stale VPN handle | fail-closed, no direct connection |
| VPN disappears during DNS/TCP/UDP | request/session closes, no fallback |
| DNS blackhole | bounded timeout; other workers remain responsive |
| UDP relay range exhausted | new association fails safely |
| Tethering interface disappears | access removed; listener reconciled/stopped |
| Password/version changes | controlled backend restart; old credentials rejected |
| Native worker crash | error state; deny retained when daemon alive |
| App process killed | listener closes; stale rules cleaned on recovery |
| Root daemon killed | **allow rules may persist; app must close listener immediately** |
| Daemon returns | Clean/deny before listener restart |
| IPv6 connection attempt | rejected for TCP and UDP range |
| Device reboot | no active stale listener/rules; optional restore starts denied |

## 8. Performance and stability

Compare:

- direct tethering baseline;
- existing VPN routing;
- Proxy-only TCP;
- Proxy-only UDP;
- WARP through Proxy-only.

Measure throughput, median/p95 latency, CPU, memory/GC, battery, FDs, packet loss, DNS queue time, UDP associations and APK-size delta. Test one and multiple clients, interactive requests and sustained transfer.

## 9. Build/static validation

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run:

- host-native Hev fork tests with bind shim;
- Rust daemon tests;
- Android lint and release/R8 build;
- native sanitizers where practical;
- license/notice verification;
- APK native-library inspection;
- manifest/foreground-service policy validation.

## 10. Release gate

The experimental feature may ship only after:

- Phase 0 matrix passes on at least two Android versions/vendors;
- physical-primary override is rejected;
- app-excluded VPN policy is diagnosed safely;
- invalid-handle, VPN-loss and daemon-death tests prove fail-closed behaviour;
- TCP, UDP-range and VPN-aware DNS tests pass;
- IPv6 exposure tests show complete denial;
- iface+IP+MAC ACL spoof tests pass;
- host CI enforces hook coverage;
- deterministic Clean removes proxy chains/jumps;
- foreground-service declaration/policy is resolved;
- third-party notices and known limitations are complete;
- FlClash process routing and optional WARP interoperate.