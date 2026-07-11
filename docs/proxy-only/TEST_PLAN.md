# Proxy-only security and test plan

The feature is acceptable only when all security properties and Phase 0 gates below pass. Tests must not claim success based only on a public-IP check; packet path, network binding, listener exposure and cleanup behavior must also be observed.

## 1. Security properties

1. A candidate is usable only with fresh `TRANSPORT_VPN` capability.
2. `Upstreams.primary` physical override cannot satisfy proxy VPN selection.
3. Exactly one usable VPN candidate is required in the MVP.
4. The VPN Hotspot app UID must be permitted to use the selected VPN.
5. Every Internet-facing TCP, UDP and resolver FD is bound before packet-producing use.
6. VPN loss, binding failure, resolver failure or daemon loss never falls back to physical networking.
7. A disabled configuration never starts or retains the proxy FGS.
8. An enabled, user-started FGS remains alive but listener-free in waiting/fail-closed states.
9. `ProxyService` is the sole backend/native-handle owner.
10. The listener is reachable only from explicitly allowed IPv4 tethering clients.
11. IPv4 allow requires downstream interface + source IPv4 + source MAC.
12. IPv6 cannot reach the TCP listener or UDP relay range.
13. Standard UDP ASSOCIATE is bounded, source-validated and tied to control TCP.
14. Daemon death closes the listener even if kernel allow rules persist.
15. Reconciliation exceptions and parent cancellation cannot leave an untracked listener.
16. Cleanup attempts every step and records unresolved cleanup debt.
17. No backend restart occurs while cleanup debt exists.
18. Credentials, destination metadata and network handles are redacted.
19. Existing VPN-routing behavior is unchanged unless `PROXY_ONLY` is selected.

## 2. Threat model

### Physical network selected as VPN

Controls:

- VPN-specific enumeration or fresh capability validation;
- zero/one/multiple candidate result;
- app-owned bind probe;
- no fallback to `Upstreams.fallback` or process default.

### Per-app VPN exclusion

Controls:

- allowed/excluded policy matrix;
- stable `VpnPermissionDenied` state;
- no listener exposure after bind failure;
- policy-change-while-running test.

### Open proxy exposure

Controls:

- deny-first root runtime;
- authentication;
- iface+IPv4+MAC allow;
- terminal IPv4 reject;
- complete IPv6 deny;
- listener closure on daemon loss;
- interface/address-family scans.

### Native VPN bypass

Controls:

- one injected prepare hook;
- host CI ordering assertions;
- close FD on hook failure;
- Android invalid/stale handle tests;
- packet capture on physical and VPN interfaces.

### DNS leak or head-of-line blocking

Controls:

- VPN-aware resolver only;
- bounded concurrency/queue;
- strict timeout;
- blackhole stress tests;
- no process-default DNS traffic.

### UDP rule/topology error

Controls:

- Phase 0 FD/port/interface/conntrack correlation;
- no final return rule before evidence;
- no broad VPN-interface accept;
- configured relay range and capacity;
- source/control-peer validation.

### Reconciler failure

Controls:

- one serialized worker;
- timeout distinguished from parent cancellation;
- per-iteration exception boundary;
- recovery routine with its own exception boundary;
- terminal cleanup in `finally`;
- state publication and reporter failures contained;
- cleanup debt blocks restart.

### Background FGS restart rejection

Controls:

- initial start only from user-approved foreground action;
- no activation while disabled;
- waiting/fail-closed states retain existing FGS;
- background dependency recovery uses existing service;
- process-death resurrection is not assumed.

## 3. Phase 0 device matrix

### VPN selection

| Scenario | Expected result |
| --- | --- |
| No VPN | waiting/fail-closed, no listener |
| One usable VPN | candidate accepted after fresh validation |
| Physical `service.upstream` override | routing may use it; proxy rejects it |
| Two usable VPN candidates | `MultipleVpnCandidates`, no listener |
| Candidate loses VPN capability before commit | startup rolls back |
| VPN handle changes with same interface name | full backend restart |

### Per-app VPN policy

| VPN policy | Expected result |
| --- | --- |
| All apps | bind/TCP/UDP/DNS probes succeed |
| VPN Hotspot explicitly allowed | probes succeed |
| VPN Hotspot excluded/denied | `VpnPermissionDenied`, no listener |
| Policy changes while running | listener closes, fail-closed |
| Lockdown/always-on | behavior documented and no bypass |

### UDP topology

For multiple associations, record:

- control TCP FD;
- returned `BND.ADDR/BND.PORT`;
- relay FD/port;
- Internet-facing FD/port;
- shared versus separate socket role;
- prepare-hook events;
- client packet input interface;
- remote-reply input interface;
- conntrack state;
- range/capacity consumption.

Assertions:

- every Internet-facing FD is bound;
- all returned relay ports are inside range;
- reply traffic works without broad VPN-interface allow;
- verified return rule is narrowly scoped and ordered before terminal reject;
- range exhaustion returns controlled failure and metric.

### DNS

With working and blackholed VPN DNS:

- one and many concurrent domain requests;
- unrelated TCP/UDP remains responsive;
- timeout is bounded;
- VPN loss invalidates results;
- no physical/default DNS packet appears.

### Address family

- scan TCP port over native IPv6;
- scan complete UDP range over IPv6;
- test IPv4-mapped behavior;
- confirm ip6tables reject and IPv4 functionality.

## 4. Kotlin/controller unit tests

### Selection and normalization

- settings migration defaults to `VPN_ROUTING`;
- invalid ports/ranges/limits rejected;
- effective UDP capacity computed correctly;
- runtime key ignores client ordering and irrelevant link churn;
- zero/one/multiple VPN result stable;
- app-policy bind failure maps correctly.

### Service activation ordering

- disabled snapshot never calls `activateFeature`;
- disabled snapshot calls terminal stop when service was active;
- enable from approved action activates once;
- repeated enabled snapshots do not restart FGS;
- waiting states call `enterWaiting`;
- user disable calls `stopFeature`;
- background VPN/daemon return does not call a new FGS start.

### Worker exception injection

Inject at:

1. service activation;
2. firewall deny-runtime start;
3. backend start;
4. outbound probes;
5. allow replacement;
6. fast-path `firewall.replace`;
7. fast-path `service.replaceAcl`;
8. state publication;
9. reporter publication;
10. deny during cleanup;
11. normal backend stop;
12. emergency listener close;
13. firewall stop;
14. waiting transition;
15. feature stop;
16. cleanup-debt recovery.

Assertions:

- recoverable iteration failures do not kill the worker;
- recovery failure itself is contained and reported;
- later cleanup steps run after earlier failures;
- emergency close is attempted after uncertain/failed normal stop;
- no new backend starts over cleanup debt;
- newest conflated snapshot is eventually processed after debt clears;
- publication failure does not alter resource safety.

### Cancellation and timeout

- transaction timeout enters fail-closed recovery;
- parent `CancellationException` is rethrown;
- cancellation during idle reaches `finally`;
- cancellation after firewall start cleans partial firewall state;
- cancellation after backend start closes listener;
- cancellation while cleanup throws still attempts remaining steps;
- terminal cleanup executes in `NonCancellable` context.

### Cleanup debt

- failed backend stop records service handle/debt;
- failed firewall stop records firewall handle/debt;
- daemon-unavailable stop marks Clean required;
- debt blocks backend startup;
- emergency close retries while debt exists;
- daemon Clean/deny clears firewall debt;
- successful recovery clears debt before normal reconciliation;
- UI state is `CleanupDegraded` while unresolved.

## 5. ProxyService tests

- only service owns `ProxyBackend`;
- controller has no direct backend reference;
- `activateFeature` requires permitted start context;
- `ForegroundServiceStartNotAllowedException` is actionable;
- `enterWaiting` closes listener but retains FGS/notification;
- `WaitingForTethering`, `WaitingForVpn`, `MultipleVpnCandidates`, `VpnPermissionDenied` and daemon fail-closed have no listener;
- `stopFeature` closes backend and stops FGS;
- backend commands are serialized;
- normal stop is idempotent;
- emergency close works if controller state is corrupt or missing a handle;
- structured cleanup report preserves all failures;
- process death is not treated as guaranteed auto-restart.

## 6. Native and host-CI tests

### Prepare hook

- exactly once per outbound FD;
- before TCP connect;
- before first UDP connect/send;
- retries and address fallback covered;
- resolver-created FDs covered when applicable;
- hook failure closes FD and prevents traffic;
- Linux host target uses fake bind function;
- Android instrumentation tests real NDK call.

### Backend lifecycle

- unique opaque handles;
- repeated start/stop leaks no FDs or threads;
- no callback after stop;
- no live network replacement;
- outbound probe report distinguishes bind/TCP/UDP/DNS/readiness;
- emergency stop closes all listener/session FDs.

### UDP

- control TCP owns association lifetime;
- source peer enforced;
- `FRAG != 0` dropped;
- all ports inside range;
- effective capacity enforced;
- exhaustion controlled;
- debug topology events match packet capture.

## 7. Rust firewall tests

- reuse existing `IptablesRule` and chain abstractions;
- repeated insertion/deletion idempotent;
- deterministic Clean removes proxy jumps/chains;
- IPv4 starts denied and ends in reject;
- allow requires iface+IPv4+MAC;
- MAC mismatch denied with matching IP;
- IPv6 denies TCP and complete UDP range;
- port/range replacement has no broad allow window;
- provisional UDP return policy absent without Phase 0 evidence;
- verified return rule is narrowly encoded and precedes reject;
- no broad VPN-interface accept exists;
- failed mutation never broadens access;
- counters distinguish TCP/UDP and client MAC/downstream.

## 8. Rooted Android integration tests

### Direct baseline

- Proxy-only disabled: tethered client uses physical/carrier IP;
- phone VPN active: unselected direct client traffic remains physical.

### TCP/DNS proxy

```bash
curl --socks5-hostname USER:PASS@PHONE_IPV4:10808 https://ifconfig.me
```

Verify VPN exit IP, authentication behavior, domain resolution through VPN and no physical DNS leak.

### UDP/WARP

- UDP echo;
- DNS over UDP;
- QUIC where supported;
- WARP WireGuard through FlClash `dialer-proxy`;
- returned relay port in range;
- second client cannot inject;
- control TCP close destroys association.

### Daemon loss and background recovery

1. Run with allow rules committed.
2. Kill `vpnhotspotd`.
3. Confirm kernel rules may remain.
4. Confirm service closes listener and remains foreground fail-closed.
5. Restore daemon while UI is backgrounded.
6. Confirm Clean/deny clears debt before backend restart.
7. Confirm no new FGS start attempt occurs.

### VPN loss and background recovery

1. Start proxy and background UI.
2. Disable VPN.
3. Confirm listener closes, FGS remains and notification changes.
4. Restore VPN.
5. Confirm existing service validates and recreates backend.
6. Confirm no `ForegroundServiceStartNotAllowedException`.

### ACL spoofing

Two clients; block one; reuse/spoof allowed IP from the wrong MAC; confirm denial while legitimate client continues.

### Process death/reboot

- process death closes listener;
- no automatic restart is assumed;
- next user/policy-compliant start performs Clean/deny first;
- reboot leaves no active listener;
- optional future boot restore starts denied and requires separate approval.

## 9. FlClash acceptance

| Route | Expected public IP |
| --- | --- |
| `DIRECT` | physical/carrier IP |
| `PhoneVPN` | phone VPN exit IP |
| `WARP-via-PhoneVPN` | Cloudflare WARP IP |

For WARP, verify Cloudflare trace, native UDP association state and packet path. `warp=on` alone is insufficient proof.

## 10. Failure matrix

| Failure | Expected result |
| --- | --- |
| Disabled settings | no FGS activation, no listener |
| FGS start disallowed | actionable error, no listener |
| No/multiple VPNs | waiting/fail-closed, no listener |
| VPN Hotspot excluded | permission error, no listener |
| Firewall deny/start fails | backend not exposed |
| Backend start/probe fails | rollback, cleanup debt if needed |
| Fast-path replace fails | listener closes, fail-closed recovery |
| State publication fails | worker continues, resources remain reconciled |
| Reporter fails | does not kill resource worker |
| VPN disappears | sessions close, FGS waits, no fallback |
| DNS blackhole | bounded failure, other sessions responsive |
| UDP range exhausted | controlled failure, no range expansion |
| Normal backend stop fails | emergency close attempted; debt recorded |
| Firewall stop fails | service remains listener-free; debt recorded |
| Daemon killed | listener closes; stale rules handled on recovery |
| Parent scope cancelled | terminal cleanup runs |
| Process killed | listener closes; no assumed resurrection |
| IPv6 attempt | rejected |

## 11. Build and release gate

Run current repository equivalents of:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run host-native fork tests, Rust daemon tests, Android lint, release/R8, native sanitizers where practical, license checks and APK native-library inspection.

The experimental feature may ship only after:

- Phase 0 passes on multiple Android versions/vendors;
- zero/one/multiple VPN selection is deterministic;
- app-policy exclusion is safely diagnosed;
- TCP/UDP/DNS and UDP topology tests pass;
- IPv6 exposure is fully denied;
- iface+IP+MAC spoof tests pass;
- exception/cancellation/cleanup-debt tests pass;
- daemon-death and background recovery tests pass;
- FGS type/start-context/store policy is approved;
- deterministic Clean removes all proxy state;
- FlClash selective routing and optional WARP interoperate;
- limitations and troubleshooting are visible.
