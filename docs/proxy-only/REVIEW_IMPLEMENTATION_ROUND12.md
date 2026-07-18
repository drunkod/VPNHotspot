# Implementation review, round 12 — R11 generation-dominance remediation

Reviewed source commit: `379352e8e9a769624f29946c050e2a97e3f8c077`

Baseline: `docs/proxy-only/REVIEW_IMPLEMENTATION_ROUND11.md`

Scope: generation safety as a prerequisite for lower-epoch dominance in `cleanupApplied()` and `retryCleanupDebtSafely()`.

## Verdict

**The round-11 source blocker is fixed.** Both cleanup paths now require daemon-generation equality before treating a same-session, lower-epoch firewall handle as safely dominated. A generation mismatch therefore falls through to unknown/conflict handling and produces `daemonCleanPending`; no handle-specific IPC is attempted and sanitation remains required.

No new source regression was found in this targeted two-condition change.

The PR should remain a draft Phase 0 branch because the daemon token protocol, concrete cleanup supervisor, bounded failure history, normalization diagnostics and focused controller/protocol tests remain unfinished.

## Resolution audit

| Round-11 item | Status | Assessment |
| --- | --- | --- |
| `cleanupApplied()` lower-epoch dominance ignores generation | Fixed | `firewallDominated` now requires `generationSafe`. Generation mismatch cannot suppress `firewallNeedsClean`. |
| Debt-retry lower-epoch dominance ignores generation | Fixed | `handleDominated` now requires `generationSafe`. Generation mismatch forces `daemonCleanPending`. |
| Generation-matrix tests | Not added | The logic is corrected, but deterministic tests for all generation/session/epoch combinations are still missing. |
| Root-daemon enforcement | Not fixed | Kotlin contracts are still not enforced by protobuf/Rust acknowledgements and stale-token rejection. |
| Persistent cleanup owner | Not fixed | `cleanupScope` remains a constructor lifetime contract rather than a concrete service-owned supervisor. |

## Verified source behavior

### `cleanupApplied()`

The classification is now:

```text
generation safe + exact session/epoch -> current, handle IPC allowed
generation safe + same session + lower epoch -> dominated by sanitation
generation mismatch/unknown -> daemonCleanPending, no handle IPC
all other provenance conflicts -> daemonCleanPending, no handle IPC
```

### `retryCleanupDebtSafely()`

`handleDominated` is gated by the same captured-generation equality used for `handleCurrent`. A generation-mismatched lower-epoch handle is therefore stale/unknown, clears concrete handle operations and retains sanitation debt.

## CI status

At review time, both `Test` and `Dependency Review` runs for `379352e8` were still in progress. The previous source commit passed the main `Test` workflow, including Rust checks/audit and Gradle build/tests. Dependency Review had continued to fail without an available complete diagnostic.

Do not weaken the dependency-review severity policy or suppress the check without identifying its actual failure.

## Remaining structural blockers

1. Implement sanitation/start/replace/deny/stop acknowledgements, daemon session identity and stale-token rejection in protobuf/Rust.
2. Wire a concrete service-owned cleanup supervisor and authoritative service-state reconciliation.
3. Bound or deduplicate ordinary cleanup failure history.
4. Add normalization diagnostics and deterministic downstream-address selection.
5. Add focused generation/session/epoch matrix tests, teardown-dominance tests and daemon-restart command-race tests.

## Approval boundary

Status after round 12: **round-11 source blocker resolved; PR remains draft for structural integration and test gaps.**
