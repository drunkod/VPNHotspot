# Structural review — round 2

Reviewed after the round-1 rewrite on branch `agent/proxy-only-design`.

## Verdict

The rewrite genuinely resolves all thirteen round-1 findings and is approvable in direction for Phase 0. Before Phase 0 implementation begins, one high-priority lifecycle defect must be corrected and four medium-priority design points must be made explicit.

## Round-1 resolution audit

| Round-1 finding | Resolution status |
| --- | --- |
| `Upstreams.primary` could be physical | Resolved: proxy-only uses a VPN-only selector with fresh `TRANSPORT_VPN` validation. |
| Per-app VPN exclusion omitted | Resolved: Phase 0 includes allowed/excluded app-policy tests and a distinct permission error. |
| Daemon death incorrectly assumed to deny | Resolved: listener closure is mandatory because kernel allow rules may survive. |
| IPv6 exposure gap | Resolved: IPv4-only MVP plus explicit ip6tables deny. |
| IP-only ACL | Resolved: interface + IPv4 + MAC are mandatory. |
| UDP relay range mismatch | Resolved in model: explicit start/end range, pending pinned-Hev verification. |
| Cancellation-unsafe reconciler | Partially resolved: cancellation is fixed, but exception and terminal-shutdown handling still require correction below. |
| Global versus per-downstream ambiguity | Resolved: global MVP UI, per-downstream internal model. |
| Blocking resolver risk | Resolved: bounded worker or asynchronous resolver is a Phase 0 gate. |
| Foreground-service type deferred too late | Resolved: service type/policy is a Phase 2 merge gate. |
| Combined mode untested | Resolved: combined mode removed from MVP. |
| Native fork CI missing | Resolved: host bind shim plus Android instrumentation. |
| Runtime key underspecified | Resolved: exact normalized runtime key and ACL-only client replacement. |

## Required change before Phase 0 code

### Exception-safe and terminal-safe reconciler

The worker currently prevents cancellation of an in-flight transaction but has no per-iteration or top-level exception boundary. Exceptions from the fast path (`firewall.replace`, ACL replacement), state publication, or rollback can terminate the worker while the listener remains live and unreconciled. Scope cancellation also needs a final `stopApplied()`.

Required contract:

1. One worker consumes the conflated desired-state channel.
2. Every iteration has a top-level `try/catch`.
3. Any unexpected exception enters fail-closed recovery: request deny if possible, close the service-owned backend/listener, then stop the firewall runtime.
4. Cleanup attempts every step even when an earlier step fails.
5. Cleanup failures are aggregated and reported; do not use silent `runCatching`.
6. `publish` failures are contained and reported without killing the resource worker.
7. The worker `finally` block runs terminal `stopApplied()` in `NonCancellable` context.
8. Tests inject failures in fast-path replace, publication, backend stop, firewall stop and scope cancellation.

## Required amendments

### 1. Discover Hev UDP socket topology in Phase 0

The firewall design must not assume the client-facing UDP relay socket and Internet-facing UDP socket are distinct FDs. Phase 0 must identify:

- listener/control and relay FDs;
- whether upstream datagrams share the returned relay port;
- which interface receives remote replies;
- whether conntrack classifies replies as `ESTABLISHED`/`RELATED`;
- the exact rule ordering needed before the terminal relay-range reject.

Capture packets and correlate FDs, local ports, interfaces and hook calls. The firewall policy is not approved until remote replies work without adding a broad VPN-interface allow.

### 2. Define probes as outbound-only

Deny-first startup intentionally blocks client ingress. Therefore the pre-allow probe must not connect to the SOCKS listener through the downstream interface.

Approved probes:

- direct app-UID socket-binding probe;
- backend-created outbound TCP probe;
- backend-created outbound UDP probe;
- VPN-aware DNS probe;
- internal listener readiness signal proving bind/listen succeeded without traversing iptables.

External listener reachability is tested only after per-client allow rules are committed.

### 3. Make `ProxyService` the sole backend owner

`ProxyOnlyController` owns desired-state reconciliation and talks to `ProxyServiceClient`. `ProxyService` owns `ProxyBackend`, native instance handles, sessions and backend statistics. The controller must not hold or invoke `ProxyBackend` directly.

This ownership keeps all native lifecycle operations inside the foreground-service process boundary and makes emergency listener closure possible even when controller reconciliation fails.

### 4. Keep the foreground service alive through recovery states

Android 12+ can reject a new foreground-service start from the background. After the user explicitly enables Proxy-only from an allowed foreground context, `ProxyService` remains alive while the feature is enabled, including:

- `WaitingForTethering`;
- `WaitingForVpn`;
- `FailClosed` after VPN loss;
- `FailClosed` after daemon loss;
- recovery while the app UI is backgrounded.

In these states the service has no active listener and displays a persistent waiting/blocked notification. It stops only when the user disables the feature, the product explicitly abandons recovery, or the OS/process terminates it. Automatic resurrection after process death must obey current Android background-start rules and is not assumed.

## Minor clarifications

### Multiple VPN candidates

Do not select nondeterministically. The MVP fails closed with `MultipleVpnCandidates` unless exactly one usable VPN candidate exists. A future explicit selector may use stable user-facing identity; sorting transient network handles is not a user-intent policy.

### UDP range and capacity

If Hev allocates one relay port per association, the configured range is also a global capacity ceiling. Define and expose an effective association limit:

```text
effective UDP capacity = min(configured association limit, usable relay-port count)
```

Range exhaustion must return a controlled SOCKS failure and metric, never broaden the firewall range dynamically.

### Cleanup reporting

Stop paths must report each failed cleanup step with context. A backend-stop failure must not skip firewall-stop; a firewall-deny failure must not skip listener closure. Silent best-effort cleanup is insufficient for a fail-closed feature.

## Approval boundary

After the documents incorporate the required change and amendments above, the design is approved to begin Phase 0 only. This is not approval to merge UI, production firewall commands or release code before the Phase 0 exit criteria pass.
