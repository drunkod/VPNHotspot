# Proxy-only security and test plan

The feature is acceptable only when every security property and Phase 0 gate below passes. Public-IP checks alone are insufficient; tests must observe network binding, listener exposure, firewall state, cleanup behavior and typed diagnostics.

## 1. Security properties

1. A proxy upstream has current `TRANSPORT_VPN` capability.
2. A physical `Upstreams.primary` override cannot satisfy the proxy requirement.
3. Zero or multiple usable VPN candidates fail closed.
4. The VPN Hotspot app UID is permitted to use the selected VPN.
5. Every Internet-facing TCP/UDP/DNS socket is bound before sending.
6. UDP is required by startup probes only when UDP is enabled.
7. App-UID permission denial reaches `VpnPermissionDenied`.
8. DNS/TCP/UDP/listener failures retain typed fail-closed reasons.
9. Persisted `enabled=true` does not authorize a background FGS start.
10. Service activation requires a valid foreground `ActivationGrant`.
11. Enabled without grant reaches `ActivationRequired`.
12. Pre-activation wait/stop/emergency commands are no-op reports, not exceptions.
13. The service is the sole backend/native owner.
14. Listener exposure exists only for authenticated allowed tethered clients.
15. IPv4 allow requires interface + IPv4 + MAC.
16. IPv4 and IPv6 deny state is explicit in protocol/configuration.
17. IPv6 cannot reach the MVP listener or UDP range.
18. VPN loss, daemon loss, binding failure and DNS failure never cause direct fallback.
19. Daemon loss closes the listener even if kernel allows survive.
20. Cleanup attempts every eligible step.
21. Cleanup debt records unresolved resources individually.
22. A successful listener close cannot clear unresolved firewall debt.
23. No backend/firewall runtime starts while debt remains.
24. Cleanup debt retries with no external state change.
25. Each debt field clears only after that resource/action succeeds.
26. Idle snapshots with no applied resources cannot fabricate debt.
27. Feature stop has one owner.
28. Parent cancellation reaches worker `finally` and terminal cleanup.
29. Process restart does not assume automatic service resurrection.
30. Existing VPN-routing behavior is unchanged unless `PROXY_ONLY` is selected.

## 2. Threat model and controls

### Physical upstream mistaken for VPN

Controls:

- VPN-specific candidate enumeration;
- fresh capability checks;
- exact-one selection;
- physical-primary override tests.

### Per-app VPN exclusion

Controls:

- typed app-UID bind probe;
- EPERM maps to `VpnPermissionDenied`;
- no direct fallback;
- clear user guidance.

### Unauthorized foreground-service activation

Threat: process restarts in background with persisted enabled state and attempts to start the FGS.

Controls:

- one-time `ActivationGrant` issued only by foreground user action;
- `ActivationRequired` state without grant;
- grant consumed only after successful activation;
- no service calls before activation except defined no-op behavior;
- explicit `ForegroundServiceStartNotAllowedException` tests.

### Open proxy exposure

Controls:

- deny runtime before listener startup;
- explicit `deny_all_ipv4` and `deny_all_ipv6`;
- iface + IPv4 + MAC allow;
- terminal reject;
- authentication;
- listener closure on daemon loss;
- interface scans.

### VPN bypass in native fork

Controls:

- centralized injected prepare hook;
- host CI counts every path;
- hook failure closes FD;
- invalid-handle Android tests;
- physical-interface captures.

### Blocking or leaking DNS

Controls:

- network-aware resolver only;
- bounded concurrency and queue;
- strict timeout;
- blackhole stress test;
- process-default DNS capture;
- VPN generation invalidation.

### UDP topology assumption

Controls:

- debug FD/port/network instrumentation;
- packet capture;
- conntrack observation;
- no broad VPN-interface allow;
- return policy absent until evidence exists.

### Cleanup debt cleared incorrectly

Threat: listener close succeeds but firewall runtime remains, and the controller clears debt and restarts.

Controls:

- per-resource fields;
- surviving firewall handle retained;
- separate deny/stop obligations;
- partial resolution tests;
- restart gate checks `debt.isResolved` only.

### Cleanup retry starvation

Threat: cleanup fails transiently while all external flows remain stable; no new snapshot arrives.

Controls:

- internal `RetryCleanupDebt` event;
- bounded exponential backoff;
- retained latest snapshot;
- stale-generation rejection;
- quiescent-system recovery test.

## 3. Phase 0 device tests

### VPN candidate selection

1. One VPN, default primary: candidate accepted.
2. Physical `wlan0` primary override: routing may accept; proxy rejects.
3. No VPN: waiting/fail-closed.
4. Two usable VPN networks: `MultipleVpnCandidates`.
5. VPN disappears between selection and startup: startup rejected.
6. VPN handle changes with same interface name: backend fully restarts.

### Per-app policy matrix

| VPN policy | Expected result |
| --- | --- |
| all apps | bind/TCP/UDP/DNS succeed |
| VPN Hotspot explicitly allowed | succeed |
| VPN Hotspot excluded | `VpnPermissionDenied`, no listener |
| policy revoked while running | listener closes, typed waiting state |

### Activation context matrix

1. User enables in foreground with grant: service activates.
2. Grant consumed after successful activation.
3. Replaying consumed grant: rejected.
4. Expired grant: `ActivationRequired`.
5. Persisted enabled after process restart, background context: `ActivationRequired`, no FGS start.
6. User opens app and taps Resume: new grant activates service.
7. Background VPN/daemon return while service already active: recovery without new FGS start.
8. `ForegroundServiceStartNotAllowedException`: actionable activation state, no undefined `enterWaiting` call.

### Typed probes

- app-UID bind permission denial -> `VpnPermissionDenied`;
- TCP timeout -> `TcpProbeFailed`;
- DNS timeout -> `DnsProbeFailed`;
- listener readiness failure -> `ListenerNotReady`;
- UDP enabled + UDP failure -> `UdpProbeFailed`;
- UDP disabled + missing/failing UDP result -> startup can still pass;
- missing required result -> typed incomplete/internal readiness failure;
- no typed expected failure is collapsed into generic `IllegalStateException`.

### Hev UDP topology

- record control TCP FD;
- record returned `BND.ADDR/BND.PORT`;
- record relay and upstream FDs;
- determine shared/separate sockets;
- prove all Internet-facing UDP FDs pass network hook;
- record remote-reply ingress interface;
- record conntrack states;
- prove exact narrow return rule if required;
- prove no broad VPN-interface allow;
- prove all ports stay in configured range;
- prove association closes with control TCP;
- prove range exhaustion fails safely.

### Resolver behavior

With working and blackholed VPN DNS:

- one and many concurrent resolutions;
- unrelated TCP/UDP sessions remain responsive;
- timeout/cancellation bounded;
- no physical/process-default DNS packet;
- VPN loss invalidates outstanding result.

### IPv6 behavior

- scan listener TCP over native IPv6;
- scan entire UDP range over IPv6;
- test dual-stack wildcard and IPv4-mapped paths;
- verify explicit IPv6 deny;
- verify IPv4 path still works.

## 4. Unit tests — domain and selection

- existing install migrates to `VPN_ROUTING`;
- settings validate TCP/UDP ranges and association limit;
- effective UDP capacity calculation;
- credentials redaction;
- runtime key excludes client ordering and irrelevant link churn;
- multiple VPN candidates fail closed;
- app-UID denial maps to typed state;
- physical primary override rejected;
- explicit IPv4 deny flag is preserved independently from ACL emptiness.

## 5. Unit tests — activation grant

- grant issued only by foreground enable/resume action;
- grant has unique identity and expiration;
- successful activation consumes grant;
- failed activation does not silently mark service active;
- persisted enabled without grant -> `ActivationRequired`;
- disabled state checked before activation;
- controller never calls `enterWaiting` before activation;
- service client pre-activation commands return no-op report;
- user disable with inactive service remains safe;
- process restart does not auto-activate.

## 6. Unit tests — typed probes

- required kinds always include bind/TCP/DNS/readiness;
- UDP kind included only when enabled;
- bind permission error maps to `VpnPermissionDenied`;
- TCP failure maps to TCP reason;
- DNS failure maps to DNS reason;
- UDP failure maps to UDP reason only when required;
- listener readiness maps distinctly;
- missing required result is diagnosed;
- expected probe failure performs controlled cleanup and waiting transition;
- generic recovery reserved for unexpected exceptions.

## 7. Unit tests — exception-safe worker

Inject failures at:

1. fast-path firewall replace;
2. fast-path ACL replace;
3. deny-runtime start;
4. backend start;
5. typed probe execution;
6. allow replacement;
7. state publication;
8. deny during cleanup;
9. backend stop;
10. emergency close;
11. firewall stop;
12. daemon Clean;
13. feature stop;
14. reporter callback.

Assertions:

- worker remains alive after recoverable iteration failure;
- parent cancellation is rethrown;
- worker `finally` runs terminal cleanup;
- later cleanup steps run after earlier failures;
- publication/reporting failures do not kill worker;
- newest desired snapshot is processed after recovery;
- no listener remains active without firewall policy.

## 8. Unit tests — cleanup debt content

### Creation

- backend-stop failure retains service handle;
- emergency-close failure sets listener pending;
- deny failure sets deny pending;
- firewall-stop failure retains firewall handle and stop pending;
- daemon unavailable sets Clean pending;
- feature-stop failure sets feature-stop pending;
- successful item does not remain pending;
- idle `applied == null` creates no emergency-close call/debt.

### Partial resolution

1. Create debt from healthy-daemon firewall-stop failure.
2. Listener is already closed.
3. First retry deny succeeds, firewall stop still fails.
4. Debt retains firewall handle/stop only.
5. Second retry firewall stop succeeds.
6. Debt clears.
7. Only then can startup resume.

Additional cases:

- emergency close succeeds but firewall stop fails: debt remains;
- firewall stop succeeds but daemon Clean remains: debt remains;
- service handle clears independently from firewall handle;
- feature-stop debt clears only after actual feature stop;
- failure history is retained/updated with context.

## 9. Unit tests — retry scheduler

- unresolved debt schedules one retry job;
- retry delay increases with bounded backoff;
- jitter remains within configured range;
- duplicate snapshot does not create duplicate retry jobs;
- stale retry generation ignored;
- current debt generation accepted;
- debt clears with no external state change;
- successful cleanup cancels/resets retry;
- user disable still allows required safety cleanup;
- scheduler cancellation during app shutdown still reaches terminal cleanup.

## 10. ProxyService tests

- service activates only with validated grant;
- service owns backend exclusively;
- controller has no backend reference;
- waiting states retain FGS but no listener;
- pre-activation commands are no-op reports;
- `startBackend` before activation fails deterministically and is not called by controller;
- emergency close is idempotent;
- backend stop is idempotent and structured;
- feature stop occurs once;
- background VPN/daemon recovery uses existing FGS;
- process death is not treated as guaranteed restart.

## 11. Native/host-CI tests

### Socket hook

- exactly once per outbound FD;
- before TCP connect;
- before first UDP connect/send;
- resolver socket paths covered;
- retries/fallback covered;
- hook failure closes FD;
- host target uses injected shim.

### Backend

- unique opaque handles;
- repeated start/stop leaks no FD/thread;
- no callback after stop;
- no live network replacement;
- typed probe report includes per-kind failures;
- listener readiness does not traverse downstream firewall.

### UDP

- association tied to control TCP;
- `FRAG != 0` dropped;
- source peer enforced;
- relay range enforced;
- capacity enforced;
- range exhaustion metric;
- topology events identify FD roles accurately.

## 12. Rust firewall tests

- uses existing `IptablesRule` ledger;
- repeated apply/delete idempotent;
- explicit `deny_all_ipv4` installs deny independent of ACL contents;
- explicit `deny_all_ipv6` covers TCP/range;
- iface+IPv4+MAC required for allow;
- MAC mismatch denied;
- IPv4 terminal reject present;
- proxy jumps/chains included in deterministic Clean;
- range replacement has no broad allow window;
- failed mutation never broadens policy;
- return rule absent without verified topology;
- verified return rule precedes reject and is narrow;
- no broad `-i <vpn> -j ACCEPT`;
- counters distinguish TCP/UDP and MAC/downstream.

## 13. Rooted Android integration tests

### Baseline direct path

- ordinary tethering direct IP with Proxy-only disabled;
- phone VPN does not capture direct laptop traffic in Proxy-only architecture.

### TCP/DNS proxy

```bash
curl --socks5-hostname USER:PASS@PHONE_IPV4:10808 https://ifconfig.me
```

Verify VPN exit IP, auth failure and no physical DNS leak.

### UDP/WARP

- UDP echo;
- DNS over UDP;
- QUIC where supported;
- WARP WireGuard through FlClash `dialer-proxy`;
- returned relay port inside range;
- second client cannot inject;
- control TCP close destroys association.

### Daemon loss and debt

1. Start listener with allows committed.
2. Kill `vpnhotspotd`.
3. Confirm kernel rules may remain.
4. Confirm listener closes immediately.
5. Confirm firewall/Clean debt exists.
6. Restore daemon while UI remains backgrounded.
7. Confirm internal retry resolves Clean/stop.
8. Confirm no new FGS start.
9. Confirm backend restarts only after debt clears.

### Quiescent debt retry

1. Inject transient firewall-stop failure.
2. Hold settings, VPN, tethering and clients constant.
3. Confirm `CleanupDegraded`.
4. Confirm backoff event occurs without external emission.
5. Remove failure injection.
6. Confirm retry clears exact firewall debt.
7. Confirm latest snapshot reconciles and proxy resumes.

### Process restart activation

1. Enable Proxy-only and persist enabled state.
2. Kill app process.
3. Allow system/background component to recreate process without UI.
4. Confirm no FGS start attempt.
5. Confirm `ActivationRequired` is persisted/exposed when UI opens.
6. Tap Resume.
7. Confirm new grant and activation.

### ACL spoofing

Two clients, block one, reuse allowed IP from wrong MAC, confirm denial.

## 14. Corrected failure matrix

| Failure | Expected result |
| --- | --- |
| no root permission | no listener exposure |
| non-VPN primary | rejected |
| zero VPN candidates | waiting |
| multiple VPN candidates | typed fail-closed/waiting state |
| app excluded by VPN | `VpnPermissionDenied` |
| persisted enabled without activation grant | `ActivationRequired`, no FGS start |
| TCP probe failure | typed TCP reason |
| DNS probe failure | typed DNS reason |
| UDP failure with UDP enabled | typed UDP reason |
| UDP failure with UDP disabled | ignored as non-required |
| deny failure | listener still closed; deny debt retained |
| backend-stop failure | emergency close attempted; service debt retained |
| firewall-stop failure | firewall handle/stop debt retained |
| daemon killed | listener closes; Clean/firewall debt retained |
| no new external state | internal retry still runs |
| partial retry success | only successful debt items clear |
| debt remains | no new runtime start |
| feature stop failure | one feature-stop debt item retained |
| IPv6 attempt | rejected |
| process restart | no automatic background FGS resurrection |

## 15. Build/static validation

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew check
./gradlew test
```

Also run:

- host-native Hev fork tests;
- Rust daemon tests;
- Android lint and release/R8 build;
- native sanitizers where practical;
- license/notice verification;
- APK native-library inspection;
- manifest/FGS policy validation.

## 16. Release gate

The experimental feature may proceed beyond Phase 0 only after:

- exact Hev pin and hook coverage pass;
- VPN selection/policy matrix passes;
- activation-grant/process-restart tests pass;
- typed, config-aware probes pass;
- UDP topology and return policy are proven;
- DNS blackhole tests pass;
- explicit IPv4/IPv6 denial passes;
- itemized debt creation/partial resolution passes;
- quiescent retry test passes;
- no restart over debt is proven;
- daemon death and background recovery pass;
- MAC/IP spoof tests pass;
- deterministic Clean passes;
- FGS policy is resolved;
- notices and limitations are complete.
