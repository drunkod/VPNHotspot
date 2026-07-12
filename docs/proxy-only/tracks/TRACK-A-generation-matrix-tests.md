# Track A — generation / session / epoch matrix tests

**Goal:** lock in the round-11→12 fix (generation safety gates both `firewallCurrent`
and `firewallDominated`) with deterministic tests, and cover teardown-dominance and
daemon-restart command races. This is the highest-value, lowest-risk next step: the
logic is corrected but currently **unverified** — there are no proxy tests in the tree.

**Blocker refs:** R11 §Required deterministic tests; R12 audit row "Generation-matrix
tests — Not added".

**Files touched (test-only, no source change):**

```text
mobile/src/test/java/be/mygod/vpnhotspot/proxy/
  FakeProxyClients.kt                 (new — test doubles)
  CleanupGenerationMatrixTest.kt      (new — Track A core)
  TeardownDominanceTest.kt            (new)
  DaemonRestartRaceTest.kt            (new)
  CleanupDebtMergeTest.kt             (new — also supports Track D)
```

---

## Step A0 — test harness / dependencies

Confirm the module already has JUnit + coroutines-test (VPN Hotspot uses them for
existing `net/` and `util/` tests). If missing, add to `mobile/build.gradle.kts`:

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<pinned>")
testImplementation("junit:junit:4.13.2")
```

Use the pinned versions already resolved elsewhere in the project; do **not**
introduce a new coroutines version (keeps Track F / dependency review quiet).

---

## Step A1 — test doubles for the two client interfaces

The controller depends on `ProxyServiceClient` and `ProxyFirewallClient`
(`proxy/ProxyServiceClient.kt`). Build scriptable fakes that let each test drive
session/epoch/generation and fail specific steps.

```kotlin
package be.mygod.vpnhotspot.proxy

/**
 * Scriptable firewall client. Every IPC records its call and returns a
 * programmed result so tests can assert "no handle IPC was attempted".
 */
class FakeFirewallClient : ProxyFirewallClient {
    val calls = mutableListOf<String>()

    // Sanitation script: each call to cleanOrDenyBeforeRestart pops the next result.
    val sanitationResults = ArrayDeque<SanitationResult?>()
    // start() returns handles carrying the daemon-issued (sessionId, epoch).
    var nextStartHandle: ProxyFirewallHandle? = null

    var denyResult: CleanupReport = CleanupReport.empty()
    var stopResult: CleanupReport = CleanupReport.empty()

    override suspend fun cleanOrDenyBeforeRestart(): SanitationResult? {
        calls += "sanitize"
        return if (sanitationResults.isEmpty()) null else sanitationResults.removeFirst()
    }

    override suspend fun start(config: ProxyFirewallConfig): ProxyFirewallHandle {
        calls += "start"
        return nextStartHandle ?: error("test did not program a start handle")
    }

    override suspend fun replace(handle: ProxyFirewallHandle, config: ProxyFirewallConfig) {
        calls += "replace(${handle.sessionId}/${handle.epoch}/${handle.id})"
    }

    override suspend fun denyAll(handle: ProxyFirewallHandle): CleanupReport {
        calls += "denyAll(${handle.sessionId}/${handle.epoch}/${handle.id})"
        return denyResult
    }

    override suspend fun stop(handle: ProxyFirewallHandle): CleanupReport {
        calls += "stop(${handle.sessionId}/${handle.epoch}/${handle.id})"
        return stopResult
    }

    fun handleIpcCalls() = calls.filter {
        it.startsWith("denyAll") || it.startsWith("stop") || it.startsWith("replace")
    }
}
```

> Match the fake signatures to the real interface in `ProxyServiceClient.kt`. If
> `cleanOrDenyBeforeRestart()` currently returns `Long?` in some call sites vs
> `SanitationResult?`, reconcile to the interface's declared type before writing tests.

Provide an equivalent `FakeServiceClient : ProxyServiceClient` with programmable
`activateFeature`, `stopBackend`, `emergencyCloseListener`, `stopFeature`, each
recording calls and returning a scripted `CleanupReport` / `ServiceActivation`.

---

## Step A2 — the generation/session/epoch matrix (core)

This is the exact matrix R11 required. Each row asserts the **classification outcome**:
whether `daemonCleanPending` is set and whether **any** handle IPC (`denyAll`/`stop`)
was attempted.

```kotlin
class CleanupGenerationMatrixTest {

    private fun handle(session: Long, epoch: Long, id: Long = 1) =
        ProxyFirewallHandle(sessionId = session, epoch = epoch, id = id)

    /**
     * Table rows. sanitized* = controller's current sanitation identity;
     * latestGen = the newest daemon generation the collector has observed.
     */
    data class Row(
        val name: String,
        val sanitizedSession: Long, val sanitizedEpoch: Long, val sanitizedGen: Long,
        val latestGen: Long,
        val handle: ProxyFirewallHandle,
        val expectDaemonCleanPending: Boolean,
        val expectHandleIpc: Boolean,
    )

    private val rows = listOf(
        // generation match + exact session/epoch → current, handle IPC permitted
        Row("exact-current", 5, 2, gen = 10, latestGen = 10,
            handle(5, 2), expectDaemonCleanPending = false, expectHandleIpc = true),

        // generation match + same session + lower epoch → dominated by sanitation,
        // no IPC, no debt (sanitation already covered it)
        Row("gen-match-lower-epoch-dominated", 5, 3, gen = 10, latestGen = 10,
            handle(5, 2), expectDaemonCleanPending = false, expectHandleIpc = false),

        // R11 CORE: generation MISMATCH + same session + lower epoch.
        // Must NOT be treated as dominated. Falls through to daemonCleanPending,
        // NO handle IPC.
        Row("gen-mismatch-lower-epoch-NOT-dominated", 5, 3, gen = 10, latestGen = 11,
            handle(5, 2), expectDaemonCleanPending = true, expectHandleIpc = false),

        // generation mismatch + exact session/epoch → still not current → daemonClean
        Row("gen-mismatch-exact", 5, 2, gen = 10, latestGen = 11,
            handle(5, 2), expectDaemonCleanPending = true, expectHandleIpc = false),

        // different session → conflict → daemonClean, no IPC
        Row("different-session", 5, 2, gen = 10, latestGen = 10,
            handle(6, 2), expectDaemonCleanPending = true, expectHandleIpc = false),

        // future epoch same session → conflict → daemonClean, no IPC
        Row("future-epoch", 5, 2, gen = 10, latestGen = 10,
            handle(5, 3), expectDaemonCleanPending = true, expectHandleIpc = false),
    )

    @Test fun cleanupApplied_generationMatrix() = runTest {
        for (row in rows) {
            val fw = FakeFirewallClient()
            val controller = newControllerWith(fw)
            controller.seedSanitation(row.sanitizedSession, row.sanitizedEpoch, row.sanitizedGen)
            controller.seedAppliedFirewallHandle(row.handle)
            controller.seedLatestSnapshotGeneration(row.latestGen)

            val outcome = controller.cleanupAppliedForTest("matrix:${row.name}")

            assertEquals("${row.name}: daemonCleanPending",
                row.expectDaemonCleanPending, outcome.debt?.daemonCleanPending == true)
            assertEquals("${row.name}: handle IPC attempted",
                row.expectHandleIpc, fw.handleIpcCalls().isNotEmpty())
        }
    }
}
```

**Test seam decision (pick one, document it):**

- *Preferred:* add `@VisibleForTesting internal` accessors on `ProxyOnlyController`
  (`cleanupAppliedForTest`, `seedSanitation`, `seedAppliedFirewallHandle`,
  `seedLatestSnapshotGeneration`) that only expose existing private state under
  `stateMutex`. No production behavior change.
- *Alternative:* drive the controller purely through its public `start(source)` flow
  by emitting crafted `DesiredProxyState` snapshots. More faithful but slower to set
  up per row; keep for the end-to-end tests in Step A4.

---

## Step A3 — debt-retry generation matrix

Mirror the same six rows against `retryCleanupDebtSafely()`, which uses
`capturedDaemonGeneration == newSanitizedDaemonGen` for `generationSafe` and gates
`handleDominated` on it (controller lines ~727–739).

```kotlin
@Test fun debtRetry_capturedG2_sanitationG1_lowerEpochHandle_daemonCleanNoIpc() = runTest {
    val fw = FakeFirewallClient()
    val controller = newControllerWith(fw)
    // captured (latest) generation G2, but sanitation markers still G1
    controller.seedSanitation(session = 7, epoch = 4, gen = /*G1*/ 20)
    controller.seedDebt(firewallHandlePending = ProxyFirewallHandle(7, 3, id = 9))
    controller.seedLatestSnapshotGeneration(/*G2*/ 21)

    controller.retryCleanupDebtForTest()

    assertTrue(controller.debtForTest()?.daemonCleanPending == true)
    assertEquals(emptyList<String>(), fw.handleIpcCalls())
}

@Test fun debtRetry_matchedG1_lowerEpoch_remainsDominated_noDebt() = runTest {
    val fw = FakeFirewallClient()
    val controller = newControllerWith(fw)
    controller.seedSanitation(session = 7, epoch = 4, gen = 20)
    controller.seedDebt(firewallHandlePending = ProxyFirewallHandle(7, 3, id = 9))
    controller.seedLatestSnapshotGeneration(20) // matches → dominated

    controller.retryCleanupDebtForTest()

    assertFalse(controller.debtForTest()?.daemonCleanPending == true)
    assertEquals(emptyList<String>(), fw.handleIpcCalls())
}
```

---

## Step A4 — teardown-dominance tests (R10 blocker #1)

Assert that a **successful** `stopFeature()` dominates earlier emergency service/listener
debt and cannot be resurrected by the later merge.

```kotlin
class TeardownDominanceTest {
    @Test fun emergencyCloseFails_stopFeatureSucceeds_noServiceOrListenerDebtRemains() = runTest {
        val svc = FakeServiceClient().apply {
            emergencyResult = CleanupReport.failure("emergency", IOException("boom"))
            stopFeatureResult = CleanupReport.empty() // authoritative success
        }
        val controller = newControllerWith(service = svc)
        controller.seedServiceHandleConflict() // forces featureStopPending + listenerPending

        controller.retryCleanupDebtForTest()

        val debt = controller.debtForTest()
        // authoritative teardown must strip service handle + listener + feature-service
        assertTrue(debt == null || debt.unresolved.none {
            it in setOf(CleanupResource.SERVICE_HANDLE,
                        CleanupResource.LISTENER,
                        CleanupResource.FEATURE_SERVICE)
        })
        assertFalse(controller.serviceActivatedForTest())
    }

    @Test fun sameSequenceWithFirewallDebt_firewallDebtSurvives_serviceDebtDoesNot() = runTest {
        // firewall debt must remain retriable; only service/listener debt is dominated.
    }
}
```

---

## Step A5 — daemon-restart command-race tests (R10 blockers #2, #3)

The collector updates `latestSnapshot` outside `stateMutex`; a G1 handle must never
reach a G2 daemon on the fast path.

```kotlin
class DaemonRestartRaceTest {
    @Test fun fastPath_cachedG1Handle_latestSnapshotG2_containsRuntimeNoReplace() = runTest {
        val fw = FakeFirewallClient()
        val controller = newControllerWith(fw)
        controller.seedRunningRuntime(session = 1, epoch = 1, gen = /*G1*/ 100)
        // collector observes G2 while reconcile for G1 holds the mutex
        controller.seedLatestSnapshotGeneration(/*G2*/ 101)

        controller.reconcileForTest(snapshotGeneration = 100) // still G1 desired

        assertEquals("no replace() to a G2 transport",
            emptyList<String>(), fw.calls.filter { it.startsWith("replace") })
        assertTrue(controller.debtForTest()?.daemonCleanPending == true)
        assertNoRunningPublishedForStaleSnapshot(controller)
    }
}
```

---

## Step A6 — CI wiring

These run under the existing `Test` workflow (`./gradlew test`). No new CI job needed.
Confirm the tests are picked up:

```bash
./gradlew :mobile:testDebugUnitTest --tests 'be.mygod.vpnhotspot.proxy.*'
```

## Acceptance criteria

- All six matrix rows pass for both `cleanupApplied()` and `retryCleanupDebtSafely()`.
- Teardown-dominance and daemon-restart race tests pass.
- `./gradlew test` green; no new lint/dependency warnings (keep Track F clean).
- Every test asserts on **both** the debt outcome and whether handle IPC was attempted
  — a generation-mismatched handle must produce zero `denyAll`/`stop`/`replace` calls.
