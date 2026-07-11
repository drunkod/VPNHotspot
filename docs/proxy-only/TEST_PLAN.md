# Proxy-only security and test plan

## 1. Security properties

The implementation is acceptable only if all of these properties hold:

1. The SOCKS5 listener is reachable only from explicitly allowed tethering downstreams.
2. Authentication is required for all non-loopback listeners.
3. Every Internet-facing socket is bound to the selected VPN `Network` before sending traffic.
4. VPN loss never causes automatic direct fallback when fail-closed is enabled.
5. DNS for domain-form SOCKS requests uses the same VPN network.
6. A blocked tethering client cannot create new TCP or UDP sessions.
7. Credentials, destination metadata and private network handles are not leaked to logs or crash reports.
8. Cleanup removes all app-owned firewall and listener state after normal stop, crash recovery and reboot.
9. UDP associations cannot be hijacked by a different tethered client.
10. The feature does not change existing VPN-routing behaviour unless the user selects a new sharing mode.

## 2. Threat model

### Open proxy exposure

Threat: the listener binds to `0.0.0.0`/`::` and becomes reachable from cellular, upstream Wi-Fi, VPN or unrelated local interfaces.

Controls:

- install deny-first root firewall state before listener startup;
- allow only active downstream interfaces;
- require username/password;
- reject all other interfaces;
- show a blocking error if root policy cannot be committed;
- add an external network scan test from every reachable interface.

### VPN bypass

Threat: Hev creates a socket that is not passed through the network-binding hook, or binding fails and the library continues normally.

Controls:

- instrument every outbound socket path;
- make the hook return an error and close the FD on failure;
- add a build-time audit checklist against the pinned Hev source;
- add runtime counters for hook success/failure;
- test with an invalid network handle;
- temporarily block the VPN endpoint and verify no carrier-IP connection appears.

### DNS leak

Threat: a domain-form SOCKS request is resolved with the process-default resolver.

Controls:

- use `Network.getAllByName()` or Android network-aware native resolver APIs;
- fail when the selected network disappears;
- test against a domain visible only through a controlled resolver;
- capture DNS on the physical interface during tests.

### Credential disclosure

Threat: credentials appear in logs, notifications, analytics, backups, clipboard history or exported intents.

Controls:

- encrypted app-private storage;
- backup exclusion;
- redacted models and exceptions;
- no command-line secrets;
- explicit reveal/copy action;
- clipboard warning and optional timed clearing;
- never include the password in normal notifications.

### Client spoofing

Threat: one hotspot client spoofs another source address or sends UDP packets into another client's association.

Controls:

- root ACL derived from neighbour state and downstream interface;
- associate UDP relay state with the control TCP peer;
- accept UDP only from the negotiated source address;
- reject SOCKS UDP `FRAG != 0` in the MVP;
- expire association state when the TCP control connection closes.

### Resource exhaustion

Threat: a client opens excessive TCP connections, UDP associations or sends malformed handshakes.

Controls:

- global and per-client connection limits;
- handshake timeout;
- idle timeout;
- bounded UDP association table;
- file-descriptor limit monitoring;
- bounded buffers;
- fuzz testing;
- immediate cleanup when clients are blocked/disconnected.

## 3. Unit tests

### Kotlin

- settings migration defaults existing installs to `VPN_ROUTING`;
- invalid ports are rejected;
- empty/weak credentials are rejected for LAN listeners;
- state transitions follow the documented state machine;
- repeated identical desired states are idempotent;
- a VPN generation change produces stop-before-start ordering;
- VPN loss produces `FailClosed` and does not select fallback;
- credentials are redacted from `toString()` and error messages;
- generated FlClash YAML is syntactically valid and properly escaped;
- concurrent enable/disable operations serialize correctly.

### Native bridge

- start returns a unique opaque handle;
- stop is idempotent;
- invalid instance handles fail safely;
- network handle update cancels older-generation sessions;
- start/stop loops do not leak threads or FDs;
- native errors map to stable Kotlin error categories;
- no callback occurs after final stop.

### Hev fork

- socket hook executes exactly once per outbound socket;
- TCP hook executes before `connect()`;
- UDP hook executes before first `sendto()`/`connect()`;
- hook failure closes the FD;
- all retry/fallback socket paths invoke the hook;
- IPv4 and IPv6 paths invoke the hook;
- `UDP ASSOCIATE` closes when control TCP closes;
- UDP `FRAG != 0` is dropped;
- authentication accepts/rejects expected credentials.

### Rust daemon

- desired firewall state includes deny-by-default;
- only configured downstream interfaces are allowed;
- blocked clients are removed on replacement;
- port changes replace old rules atomically;
- traffic sources map to proxy TCP/UDP counters;
- cleanup reconstructs and removes stale app-owned chains;
- replacement is idempotent;
- failed mutations do not silently install a broader allow rule.

## 4. Integration tests on a rooted Android device

### Baseline

1. Enable ordinary Android Wi-Fi tethering.
2. Keep Proxy-only disabled.
3. Confirm a laptop uses the carrier/physical IP.
4. Enable the phone VPN.
5. Confirm ordinary laptop traffic still uses the carrier/physical IP.

This proves Proxy-only is not accidentally enabling existing full VPN routing.

### TCP proxy

1. Enable Proxy-only.
2. Configure `curl` with SOCKS5 hostname resolution.
3. Confirm the visible IP is the phone VPN exit IP.
4. Confirm direct browser traffic still uses the carrier/physical IP.
5. Test IPv4 literal, domain target and IPv6 target where supported.
6. Verify authentication failures do not open an upstream socket.

Example:

```bash
curl --socks5-hostname USER:PASS@PHONE_IP:10808 https://ifconfig.me
```

### UDP proxy

Verify at least:

- DNS over UDP;
- QUIC/HTTP3 where the client supports SOCKS UDP;
- a controlled UDP echo server;
- WARP WireGuard through FlClash `dialer-proxy`;
- association shutdown when the control TCP connection closes;
- idle timeout cleanup.

### VPN loss

1. Start continuous TCP and UDP traffic through the proxy.
2. Disable the phone VPN.
3. Confirm the listener changes to fail-closed.
4. Confirm new proxy requests fail.
5. Confirm existing TCP and UDP sessions close.
6. Confirm no request appears with the carrier IP.
7. Re-enable the VPN.
8. Confirm a new network generation is used and traffic resumes only after full reconciliation.

### VPN handoff

Repeat while switching:

- Wi-Fi underlay to cellular;
- cellular to Wi-Fi;
- VPN reconnect without underlay change;
- VPN server change;
- another Android VPN taking ownership.

Record network handles and ensure old-generation sessions are terminated.

### Client ACL

1. Connect two laptop/client devices.
2. Allow both and verify access.
3. Block one client in VPN Hotspot.
4. Confirm its new connections fail immediately.
5. Confirm the other client remains operational.
6. Unblock and confirm restoration without restarting the proxy.

### Interface exposure

Attempt access from:

- tethered Wi-Fi client;
- USB client;
- phone loopback;
- phone upstream Wi-Fi peer;
- cellular side where testable;
- VPN-side address;
- another local interface.

Only explicitly allowed downstream paths should succeed.

## 5. FlClash acceptance test

Use the generated `PhoneVPN` SOCKS5 outbound and process rules.

Expected results:

| Application route | Expected public IP |
| --- | --- |
| `DIRECT` | physical/carrier IP |
| `PhoneVPN` | phone VPN exit IP |
| `WARP-via-PhoneVPN` | Cloudflare WARP IP |

For WARP verification:

```bash
curl https://www.cloudflare.com/cdn-cgi/trace
```

The selected application should receive `warp=on` or `warp=plus`.

Do not treat this as proof of UDP support by itself; also inspect FlClash logs and native UDP association counters.

## 6. Failure injection matrix

| Failure | Expected result |
| --- | --- |
| No root permission | Proxy does not expose a LAN listener |
| Firewall start failure | Native listener remains denied/stops |
| Native start failure | Firewall state is removed |
| Invalid VPN network handle | Fail-closed, no direct connection |
| VPN disappears during DNS | Request fails, no default DNS fallback |
| VPN disappears during TCP connect | Connection fails and socket closes |
| VPN disappears during UDP flow | Association closes |
| Tethering interface disappears | Firewall access removed and listener reconciled |
| Password changed | Old credentials stop working after controlled restart |
| Native worker crash | Service reports error and root deny state remains |
| App process killed | Android closes listener; stale firewall state is cleaned on next start/Clean |
| Root daemon killed | Access defaults to denied; app reports unavailable |
| Device reboot | No stale firewall rules; restart only if explicitly configured |

## 7. Performance tests

Measure separately for:

- direct tethering baseline;
- existing full VPN routing;
- Proxy-only TCP;
- Proxy-only UDP;
- WARP through Proxy-only.

Metrics:

- upload/download throughput;
- median and p95 latency;
- CPU by app and root daemon;
- memory and GC activity;
- battery drain;
- file descriptors;
- packet loss;
- UDP association count;
- APK size delta.

Test with one and multiple clients. Include small interactive requests and sustained transfers.

## 8. Build and static validation

Required commands or current repository equivalents:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run:

- Rust unit tests for `vpnhotspotd`;
- Android lint;
- native sanitizer builds where practical;
- license/notice verification;
- APK native-library inspection;
- R8/release build verification.

## 9. Release gate

The experimental feature may be enabled for users only after:

- TCP and UDP tests pass on at least two Android versions and two vendors;
- invalid-handle and VPN-loss tests prove fail-closed behaviour;
- interface exposure tests show no open proxy;
- process rules work in FlClash;
- deterministic Clean removes stale firewall state;
- third-party license notices are complete;
- the Hev fork and pin are documented;
- known limitations are visible in the UI and README.
