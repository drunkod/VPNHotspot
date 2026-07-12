# Track C — service-owned cleanup supervisor + authoritative service state

Status: **complete and verified**

Verified clean source head: `6f80321f1a0134f67e8982a611126aa2bb793db8`

## Goal

Replace the abstract `cleanupScope: CoroutineScope` lifetime promise with a named,
owned cleanup supervisor that outlives controller workers and has one explicit final
shutdown path. Make `CleanupDebt.serviceWasActivated` the authoritative source for
service-IPC retry eligibility so mutable controller-local state drift cannot strand debt
or authorize an old debt incorrectly.

## Delivered implementation

### Concrete cleanup supervisor

`ProxyCleanupSupervisor` owns a `SupervisorJob` and retry `CoroutineScope`. Controllers
can launch retry work through `retryScope`, but only the lifecycle owner can call the
idempotent `shutdown(reason)` method. `isActive` checks both the explicit lifecycle bit
and the job state.

A failed retry cannot cancel sibling work because the scope is backed by
`SupervisorJob`.

### Named Phase-0 owner

`ProxyServiceCleanupOwner` is the Phase-0 service/application lifecycle holder. It owns
one `ProxyCleanupSupervisor` and exposes one authoritative teardown site through
`close()`. The production Android foreground service will own this holder for its full
lifecycle; Track C does not claim that the real foreground service or RPC transport is
implemented.

### Controller ownership contract

`ProxyOnlyController` no longer accepts a raw `cleanupScope`. It receives a
`ProxyCleanupSupervisor` and routes every cleanup-debt retry through the supervisor's
scope.

The retry scheduler now:

- refuses to schedule after supervisor shutdown while retaining and reporting debt;
- deduplicates an already-active retry for the same debt generation;
- cancels superseded retry generations;
- clears its scheduled-generation marker atomically under the controller mutex;
- continues retrying terminal debt after the controller worker scope is cancelled.

The worker's final block cancels only its currently scheduled retry before performing
terminal cleanup. Any debt produced by that terminal cleanup is rescheduled into the
service-owned supervisor, not the dying worker scope.

### Authoritative service retry state

`retryCleanupDebtSafely()` now derives service-IPC eligibility from the immutable
`debt.serviceWasActivated` value captured when the debt was created.

- When `serviceWasActivated == true`, pending backend/listener/feature teardown IPC is
  attempted even if mutable controller-local `serviceActivated` has drifted to false.
- When `serviceWasActivated == false`, service/listener/feature items are treated as
  void and cleared without IPC instead of becoming permanently retriable debt.
- A newer controller-local activation is not overwritten merely because an older,
  non-activated debt resolves as void.
- Emergency-close debt records the activation and service-handle values from the debt
  retry transaction rather than re-reading unrelated mutable controller state.

## Regression coverage

`CleanupSupervisorLifecycleTest` verifies:

1. a cleanup retry executes after the controller worker scope is cancelled;
2. owner shutdown cancels an in-flight delayed retry;
3. service debt captured with `serviceWasActivated=false` clears without service IPC;
4. service debt captured with `serviceWasActivated=true` performs backend and feature
   teardown despite controller-local activation drift.

`ControllerHarnessContractTest` additionally asserts that the raw `cleanupScope` field
is absent and the concrete supervisor/scheduler contract remains present. The shared
controller harness now owns a `ProxyServiceCleanupOwner` and closes it explicitly.

## Verification

The restored normal CI workflow passed on the exact clean source head:

- `cargo check --locked --all-targets`;
- `cargo test --locked --lib`;
- `cargo clippy --locked --all-targets -- -D warnings`;
- `cargo audit`;
- `./gradlew assembleDebug check --no-daemon`;
- `./gradlew :mobile:verifyReleaseCoroutineDebugR8 --no-daemon`;
- Dependency Review with `fail-on-severity: moderate`.

Temporary patch machinery and elevated workflow permissions were removed before this
verification.

## Acceptance criteria audit

- ✅ `cleanupScope: CoroutineScope` constructor parameter removed.
- ✅ Concrete `ProxyCleanupSupervisor` and named lifecycle owner added.
- ✅ Terminal retries survive worker cancellation.
- ✅ Owner shutdown cancels pending retries and prevents rescheduling.
- ✅ Service IPC eligibility is driven by `CleanupDebt.serviceWasActivated`.
- ✅ False-activation service/listener debt resolves as void instead of stranding.
- ✅ New lifecycle tests and the existing suite pass.

## Remaining integration boundary

Track C closes persistent cleanup ownership and authoritative cleanup-debt service state.
It does **not** implement the production Android foreground service, concrete
`ProxyFirewallRpc`/health composition, Hev/JNI backend, VPN-bound socket integration or
physical-device verification. Those remain production integration work, while Tracks D
and E remain the outstanding Phase-0 controller/model tracks.
