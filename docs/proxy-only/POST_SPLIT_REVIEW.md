# Post-split review — proxy-only sketch task files

Reviewed after splitting the former single `CODE_SKETCHES.md` into nine ordered task
files under `docs/proxy-only/sketches/`.

## Verification

Before semantic edits:

- all nine uploaded step files matched the committed branch byte-for-byte by Git blob SHA;
- the split contained 20 fenced code blocks with preserved language tags;
- all relative sibling links resolved;
- `CODE_SKETCHES.md` retained an old-section to new-step mapping for review rounds 1–3.

The mechanical split did not lose content.

## Additional correctness findings

The split made several lifecycle interactions easier to inspect. Four issues required
correction before Phase 0 code begins.

### 1. Native handle retention on stop failure

The service sketch cleared `backendHandle` before `backend.stop()` returned. A failed
stop therefore lost the only handle needed for retry, while controller debt still
claimed the handle was unresolved.

Resolution:

- the service retains the backend handle until stop completes without a critical failure;
- `emergencyCloseListener()` is a separate containment operation;
- emergency listener closure does not discard the backend handle;
- stale/mismatched handles are unresolved failures, not successful no-ops.

### 2. Cleanup-debt dominance rules

A deny failure followed by successful firewall-runtime stop could leave
`firewallDenyPending=true` with no firewall handle. That debt is impossible to resolve.

Resolution:

- successful firewall-runtime stop resolves both runtime-stop and deny obligations;
- successful deny alone does not resolve the runtime;
- successful backend stop resolves both service-handle and listener obligations;
- emergency listener close resolves only listener uncertainty.

### 3. Retry-generation starvation

A retry for an old debt generation could fire after debt was merged. The stale event
returned without clearing scheduler ownership, and a newer retry might never be
scheduled.

Resolution:

- retry ownership is cleared before generation comparison;
- stale events explicitly schedule the current generation;
- debt merge assigns a fresh generation;
- a generation change cancels/replaces the old timer;
- only one timer for the current generation may remain active.

### 4. Cold-start stale firewall state

`CleanupDebt` is process memory. Process death can lose debt while kernel rules or a
daemon-side runtime survive. A new controller must not assume an empty in-memory debt
set means the firewall is clean.

Resolution:

- `DesiredProxyState` carries daemon generation;
- before the first runtime in each daemon generation, the controller runs idempotent
  proxy-firewall Clean/deny sanitation;
- sanitation failure creates daemon-clean debt and blocks startup;
- a persisted enable flag or fresh activation grant never bypasses sanitation.

## Probe diagnosis refinement

A non-permission app-UID bind failure was incorrectly represented as an incomplete
report and could be mislabeled as listener readiness failure.

Resolution:

- permission denial maps to `VpnPermissionDenied`;
- other bind failures map to `BindProbeFailed`;
- absent required results map to `ProbeReportIncomplete`;
- listener readiness remains a separate result.

## Cross-reference status

- The task index links to all nine step files.
- Every step dependency/consumer link resolves.
- Historical `CODE_SKETCHES.md §n` citations remain traceable through the mapping table.
- The normative post-split corrections are summarized in the task index and implemented
  in Steps 1, 3, 4 and 5 plus the mandatory Step 6 addendum.

## Approval boundary

With these corrections, the documentation is internally consistent for starting
Phase 0 feasibility work. Production UI, firewall integration and release work remain
blocked by the existing Phase 0 exit criteria.
