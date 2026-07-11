# Proxy-only code sketches — step index

The sketches illustrate ownership and sequencing. They are intentionally incomplete
and must be adapted to current project APIs. Full examples live in one file per step
under [`sketches/`](sketches/).

Recommended reading/implementation order follows the table. Steps 1–5 are
prerequisites of Step 6, which assembles them into the controller worker. Steps 7–9
are the native and daemon boundaries.

| Step | Task | File | Implementation plan phase |
| --- | --- | --- | --- |
| 1 | Settings, state, activation and desired-state models | [sketches/01-models-and-state.md](sketches/01-models-and-state.md) | Phase 2 |
| 2 | VPN-only selection (`TRANSPORT_VPN` validation, fail closed on none/many) | [sketches/02-vpn-selection.md](sketches/02-vpn-selection.md) | Phase 0 / Phase 3 |
| 3 | Service/backend ownership and persistent foreground service | [sketches/03-service-and-backend-ownership.md](sketches/03-service-and-backend-ownership.md) | Phase 2 / Phase 4 |
| 4 | Typed, configuration-aware outbound-only probes | [sketches/04-probes.md](sketches/04-probes.md) | Phase 0 / Phase 4 |
| 5 | Applied state and itemized cleanup debt | [sketches/05-cleanup-debt-model.md](sketches/05-cleanup-debt-model.md) | Phase 3 |
| 6 | Controller worker: reconciliation, cleanup, debt retry, terminal stop | [sketches/06-controller-worker.md](sketches/06-controller-worker.md) | Phase 3 |
| 7 | Testable Hev network hook (host-CI seam) | [sketches/07-native-hook.md](sketches/07-native-hook.md) | Phase 0 / Phase 1 |
| 8 | UDP topology observations (Phase 0 evidence) | [sketches/08-udp-topology.md](sketches/08-udp-topology.md) | Phase 0 |
| 9 | Explicit firewall proto | [sketches/09-firewall-proto.md](sketches/09-firewall-proto.md) | Phase 6 |

## Contract summary

Invariants the steps implement together; each is normative and tested per
[TEST_PLAN.md](TEST_PLAN.md):

- `ProxyOnlyController` never owns a backend/native handle; `ProxyService` is the sole
  backend owner (Step 3). Pre-activation service calls return structured no-op reports.
- Persisted `settings.enabled` is not an activation grant; only a foreground user
  action issues one, and a missing grant yields `ActivationRequired` without an FGS
  start attempt (Steps 1, 6).
- `Upstreams.primary` is never trusted without fresh `TRANSPORT_VPN` validation;
  zero or multiple usable VPN candidates fail closed (Step 2).
- Probes are outbound-only and configuration-aware; expected failures map to typed
  states (`VpnPermissionDenied`, typed `FailClosed` reasons), never to generic
  exceptions (Steps 4, 6).
- Cleanup debt is itemized per resource; no listener-close success can clear a failed
  firewall stop, and no backend or firewall runtime starts while debt exists
  (Steps 5, 6).
- Debt retries are self-triggered with bounded backoff and jitter; they run even when
  all external state is quiescent (Step 6).
- `stopFeature` has one owner: terminal stop (Step 6).
- Deny state is explicit in the proto for both address families; the UDP return-path
  policy exists only after Phase 0 topology evidence (Steps 8, 9).

## Section mapping from the previous single-file layout

For readers of earlier reviews that cite `CODE_SKETCHES.md §n`:

| Old § | New location |
| --- | --- |
| 1–2 | Step 1 |
| 3 | Step 2 |
| 4–5 | Step 3 |
| 6, 8 | Step 5 |
| 7 | Step 4 |
| 9–16 | Step 6 (§9→6.1, §10→6.2, §11→6.3, §12→6.4, §13→6.5, §14→6.6, §15→6.7, §16→6.8) |
| 17 | Step 7 |
| 18 | Step 8 |
| 19 | Step 9 |
