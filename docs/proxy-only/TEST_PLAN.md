# Proxy-only security and test plan

## 1. Security properties

The feature is acceptable only if all properties hold:

1. Exactly one usable VPN candidate is selected; zero or multiple candidates fail closed.
2. A physical `Upstreams.primary` override cannot satisfy the proxy VPN requirement.
3. The VPN Hotspot app UID is permitted to use the VPN.
4. Every Internet-facing TCP, UDP and resolver FD is bound before sending.
5. DNS uses the same VPN network and never falls back to process-default resolution.
6. VPN loss, daemon loss, worker exception or cleanup error cannot cause direct fallback.
7. The listener is reachable only from authenticated, explicitly allowed tethered clients.
8. IPv4 allow requires interface + IPv4 + MAC.
9. IPv6 cannot reach the MVP listener or UDP range.
10. UDP associations are source-bound, tied to control TCP and range-limited.
11. Root-daemon loss closes the listener even if kernel allow rules persist.
12. A reconciliation exception cannot kill the worker while a listener remains live.
13. Controller scope cancellation performs terminal cleanup.
14. `ProxyService` remains alive but listener-free through waiting/fail-closed recovery states.
15. Existing VPN routing is unchanged unless `PROXY_ONLY` is selected.
16. Credentials and sensitive network metadata are redacted.

## 2. Threats and controls

### Non-VPN or ambiguous upstream

Controls:

- VPN-specific candidate source;
- fresh `TRANSPORT_VPN` validation;
- app-UID bind probe;
- fail closed on multiple usable VPNs;
- no ordering by transient network handle.

### Worker exception / partial cleanup

Threat: fast-path firewall/ACL replacement, publication, rollback or stop throws and kills the worker while resources remain active.

Controls:

- per-iteration catch boundary;
- non-cancellable timed transactions;
- partial handles recorded immediately;
- fail-closed recovery on any iteration exception;
- cleanup attempts every step independently;
- aggregated cleanup reporting;
- safe publication;
- final `stopApplied()` in worker `finally`.

### Open proxy and daemon death

Controls:

- deny-first root runtime;
- iface+IPv4+MAC allows;
- IPv4 terminal reject and IPv6 deny;
- authentication;
- immediate service emergency close on daemon-channel loss;
- Clean/deny before restart.

### UDP topology uncertainty

Threat: remote replies arrive at a relay-range socket and are rejected, or an unverified broad return rule exposes the listener.

Controls:

- Phase 0 FD/port/interface correlation;
- packet capture and conntrack inspection;
- verify same-FD versus separate-FD behavior;
- approve only narrowly scoped return rules;
- prohibit broad VPN-interface allow.

### FGS recovery

Threat: VPN or daemon returns while app is backgrounded and Android rejects a new FGS start.

Controls:

- initial user-approved foreground start;
- keep service alive while feature remains enabled;
- waiting/fail-closed notification with no listener;
- recover inside existing service;
- do not assume process-death resurrection.

## 3. Phase 0 device tests

### VPN selection

1. One usable VPN: accepted.
2. Physical primary override: rejected for proxy-only.
3. No VPN: waiting/fail-closed.
4. Two usable VPN candidates: `MultipleVpnCandidates`, no listener.
5. Candidate loses `TRANSPORT_VPN` before commit: startup aborts.
6. Same interface name but new network handle: full backend restart.

### Per-app VPN policy

| Policy | Expected result |
| --- | --- |
| VPN applies to all apps | outbound probes succeed |
| VPN Hotspot explicitly allowed | probes succeed |
| VPN Hotspot excluded/denied | permission error, no listener |
| Policy changes while running | listener closes, service remains fail-closed |

### Outbound-only probe ordering

With firewall runtime in deny state:

- app-UID bind probe succeeds/fails as expected;
- backend outbound TCP/UDP/DNS probes do not require listener ingress;
- internal listener readiness confirms bind/listen only;
- a client-side reachability attempt remains blocked;
- after allow commit, permitted client reachability succeeds.

### UDP socket topology

For one and many associations:

- log every relevant FD and hook call;
- record `BND.ADDR/BND.PORT`;
- identify client relay and Internet-facing roles;
- determine whether roles share an FD/port;
- capture client requests and remote replies;
- record ingress interfaces and conntrack states;
- prove the final firewall rule order permits valid replies only;
- prove no broad VPN-interface allow is needed;
- prove all ports remain in range;
- prove range exhaustion fails safely.

### DNS behavior

With working and blackholed VPN DNS:

- one and many concurrent resolutions;
- unrelated sessions remain responsive;
- bounded queue and timeout;
- VPN loss cancels/invalidate resolution;
- no physical/process-default DNS packets.

### IPv6

- TCP scan over native IPv6 and IPv4-mapped addresses;
- UDP scan across full relay range;
- confirm ip6tables denial;
- confirm IPv4 path still works.

## 4. Kotlin/controller unit tests

### Normalization and selection

- existing installs migrate to `VPN_ROUTING`;
- settings validate port, range and association limit;
- effective UDP capacity is computed correctly;
- client ordering and irrelevant link churn do not alter runtime key;
- multiple VPN candidates fail closed;
- app-UID binding error maps to stable UI state.

### Exception-safe worker

Inject failures at every marked point:

1. `firewall.replace` fast path;
2. `service.replaceAcl` fast path;
3. start deny runtime;
4. service backend start;
5. outbound probes;
6. allow replacement;
7. state publication;
8. deny during stop;
9. service backend stop;
10. firewall stop;
11. error publication;
12. reporter failure, if reporter is pluggable.

Assertions:

- worker remains alive after recoverable iteration errors;
- listener is closed or emergency-close is attempted;
- later cleanup steps run even if earlier ones fail;
- cleanup failures are aggregated with step names;
- partial handles are cleaned;
- no silent `runCatching` path hides failures;
- newest conflated desired state is processed after recovery.

### Scope cancellation

- cancel during idle;
- cancel after firewall start but before backend start;
- cancel after backend start but before allow commit;
- cancel while running;
- cancel while cleanup step throws.

In every case worker `finally` invokes terminal cleanup and the service has no active listener.

### Publication behavior

- `publish` throw does not terminate worker;
- resource state remains reconciled;
- publication error is reported;
- next successful publish restores observable state.

## 5. ProxyService tests

- service is started from explicit allowed user context;
- chosen FGS type/permissions pass manifest and policy checks;
- `ForegroundServiceStartNotAllowedException` is mapped to actionable error;
- service owns backend exclusively;
- controller has no direct backend reference;
- `WaitingForTethering`, `WaitingForVpn` and `FailClosed` retain FGS but no listener;
- VPN/daemon return while UI is backgrounded recovers without starting a new FGS;
- user disable stops backend and service;
- backend stop is idempotent and returns structured cleanup report;
- emergency close works if controller reconciliation is failing;
- process death is not treated as guaranteed automatic restart.

## 6. Native and host-CI tests

### Socket hook

- exactly once per outbound FD;
- before TCP connect;
- before first UDP send/connect;
- resolver-created paths covered;
- retries/fallback paths covered;
- hook failure closes FD and prevents traffic;
- host target uses injected shim, not Android symbols.

### UDP

- association tied to control TCP;
- `FRAG != 0` dropped;
- source peer enforced;
- all relay ports inside range;
- effective capacity enforced;
- exhaustion returns controlled failure/metric;
- debug topology events accurately identify FD role.

### Backend lifecycle

- unique opaque handles;
- start/stop loops leak no FD/thread;
- no callback after stop;
- no live network replacement in MVP;
- outbound probe report distinguishes bind/TCP/UDP/DNS/readiness.

## 7. Rust firewall tests

- uses existing `IptablesRule` ledger;
- repeated apply/delete is idempotent;
- iface+IPv4+MAC required;
- MAC mismatch denied even with matching IP;
- IPv4 terminal reject present;
- IPv6 TCP/range deny present;
- proxy jumps/chains included in deterministic Clean;
- port/range replacement has no broad allow window;
- failed mutation never broadens policy;
- provisional UDP return rule is absent until topology contract is supplied;
- when supplied, verified return rule precedes terminal reject and is narrowly scoped;
- no broad `-i <vpn> -j ACCEPT` rule;
- counters distinguish TCP/UDP and MAC/downstream.

## 8. Rooted Android integration tests

### Baseline

- ordinary tethering direct IP with Proxy-only disabled;
- phone VPN does not capture client direct traffic in Proxy-only architecture.

### TCP and DNS

```bash
curl --socks5-hostname USER:PASS@PHONE_IPV4:10808 https://ifconfig.me
```

Verify VPN exit IP, authentication failure behavior and no physical DNS leak.

### UDP and WARP

- UDP echo;
- DNS over UDP;
- QUIC where supported;
- WARP WireGuard through FlClash `dialer-proxy`;
- returned relay port in range;
- second client cannot inject;
- control TCP close destroys association.

### ACL spoofing

Two clients, block one, reuse/spoof allowed IP from wrong MAC, confirm denial.

### Daemon kill and return

1. Run listener with allows committed.
2. Kill `vpnhotspotd`.
3. Confirm rules may remain.
4. Confirm service closes listener immediately and remains foreground fail-closed.
5. Return daemon while UI is backgrounded.
6. Confirm Clean/deny before backend restart.
7. Confirm no new FGS start attempt is required.

### VPN loss and return in background

1. Start proxy and background UI.
2. Disable VPN.
3. Confirm listener closes, FGS remains and notification changes.
4. Restore VPN.
5. Confirm existing service validates/restarts backend.
6. Confirm no `ForegroundServiceStartNotAllowedException`.

### Controller worker failure

Use debug fault injection to throw during fast-path ACL replace and during cleanup. Confirm listener closes, worker survives/reports, and subsequent valid desired state can recover.

### Process death

Kill app process. Confirm listener closes. Do not assume automatic background restart. Reopening/re-enabling from a valid user context runs Clean/deny before exposure.

## 9. Failure matrix

| Failure | Expected result |
| --- | --- |
| No root | no LAN listener |
| Physical primary | rejected |
| Multiple VPN candidates | fail-closed selection error |
| App excluded by VPN | permission error, no listener |
| Firewall start failure | backend not started |
| Backend start/probe failure | partial firewall cleaned |
| Fast-path ACL/firewall exception | fail-closed cleanup; worker survives |
| Publish exception | reported; worker/resources not abandoned |
| Deny failure | listener still closes; failure reported |
| Backend-stop failure | firewall-stop still attempted; critical report |
| Firewall-stop failure | listener remains closed; cleanup-required report |
| Worker scope cancellation | terminal cleanup in `finally` |
| DNS blackhole | bounded timeout, other sessions responsive |
| UDP topology incompatible with safe rules | Phase 0 fails; no production implementation |
| UDP range exhausted | controlled association failure |
| VPN loss | listener closes; FGS waits |
| Daemon loss | listener closes; FGS waits; stale rules not trusted |
| Dependency returns in background | recover inside existing FGS |
| Process death | no listener; no assumed automatic restart |
| IPv6 connection | rejected |

## 10. Performance and stability

Compare direct tethering, existing VPN routing, Proxy-only TCP/UDP and WARP through Proxy-only.

Measure throughput, median/p95 latency, CPU, memory/GC, battery, FDs, packet loss, DNS queue time, cleanup time, active UDP associations, range exhaustion and APK size.

## 11. Build/static validation

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run host-native fork tests, Rust tests, Android lint, release/R8, native sanitizers where practical, license checks, ABI inspection and FGS manifest/policy validation.

## 12. Release gate

The feature may ship experimentally only after:

- all Phase 0 topology/security evidence passes;
- worker exception/cancellation matrix passes;
- service ownership and background recovery tests pass;
- no physical/multiple-VPN ambiguity;
- app-policy exclusion is safe;
- TCP/UDP/DNS/IPv6/ACL tests pass;
- daemon death and cleanup-error tests pass;
- host CI enforces hook ordering;
- deterministic Clean removes proxy state;
- third-party notices and limitations are complete;
- FlClash process selection and optional WARP interoperate.
