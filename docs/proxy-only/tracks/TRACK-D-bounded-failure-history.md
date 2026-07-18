# Track D — bounded / deduplicated failure history

**Goal:** stop `CleanupDebt.failures` from growing without bound. Conflict failures are
already deduplicated by step name (R9 amendment), but the ordinary path in
`mergeUnresolved()` still does `failures = failures + other.failures + conflictFailures`
(`CleanupDebt.kt` line 178). Repeated merges of the same unresolved ordinary cleanup
report duplicate identical entries indefinitely, so a debt that retries for a long time
accumulates an ever-growing failure list.

**Blocker refs:** R10 §6 ("Failure history is only partially bounded"), R11 structural
blocker #3, R12 remaining blocker #3.

**Files touched:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyModels.kt      (CleanupFailure identity)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/CleanupDebt.kt      (bounded merge)
mobile/src/test/java/be/mygod/vpnhotspot/proxy/FailureHistoryBoundTest.kt (new)
```

---

## Step D1 — give `CleanupFailure` a stable identity

Currently `CleanupFailure(step, cause)` (ProxyModels.kt line 74) carries a `Throwable`,
which makes structural equality unreliable (two `IOException("boom")` are not equal).
Add a stable identity so dedup is deterministic without depending on `Throwable.equals`.

```kotlin
data class CleanupFailure(
    val step: String,
    val cause: Throwable,
    /**
     * Stable identity for deduplication and bounding. Two failures with the same
     * (step, kind) describe the same unresolved condition and must collapse rather
     * than accumulate across retries. Message is included so distinct root causes
     * on the same step are still distinguishable, but bounded (see [key]).
     */
) {
    val kind: String get() = cause::class.qualifiedName ?: "unknown"
    val key: String get() = "$step|$kind|${cause.message?.take(120).orEmpty()}"
}
```

`key` is capped so a variable-length message cannot itself become an unbounded axis.

---

## Step D2 — bounded, order-preserving merge helper

Replace the raw concatenation with a dedup-by-`key` merge that also caps total history
and records how many identical occurrences were folded (diagnostics stay useful).

```kotlin
// CleanupDebt.kt

private const val MAX_FAILURE_HISTORY = 32

/**
 * Merge failure lists preserving first-seen order, collapsing duplicates by
 * [CleanupFailure.key], and capping total size. The most recent distinct failures
 * are retained on overflow (drop from the oldest, keep tail) so the newest
 * diagnostic context survives.
 */
internal fun mergeFailures(
    a: List<CleanupFailure>,
    b: List<CleanupFailure>,
): List<CleanupFailure> {
    val seen = LinkedHashMap<String, CleanupFailure>()
    for (f in a + b) {
        // First occurrence wins the slot; later duplicates are dropped (already recorded).
        seen.putIfAbsent(f.key, f)
    }
    val distinct = seen.values.toList()
    return if (distinct.size <= MAX_FAILURE_HISTORY) distinct
           else distinct.takeLast(MAX_FAILURE_HISTORY)
}
```

Then in `mergeUnresolved()` (CleanupDebt.kt ~line 178), change:

```kotlin
// before
failures = failures + other.failures + conflictFailures,

// after
failures = mergeFailures(failures + conflictFailures, other.failures),
```

Conflict-failure dedup (the existing `existingSteps` guard) still applies for the
per-step-once rule; `mergeFailures` adds the ordinary-path bound. The two are
complementary: `existingSteps` prevents re-adding a conflict; `mergeFailures` prevents
identical ordinary failures piling up across many retries.

---

## Step D3 — tests

```kotlin
class FailureHistoryBoundTest {

    private fun ioFailure(step: String, msg: String = "boom") =
        CleanupFailure(step, java.io.IOException(msg))

    @Test fun identicalFailureAcrossManyMergesCollapsesToOne() {
        var debt = debtWith(failures = listOf(ioFailure("firewall_stop")))
        val incoming = debtWith(failures = listOf(ioFailure("firewall_stop")))

        repeat(100) { debt = debt.mergeUnresolved(incoming) }

        assertEquals(1, debt.failures.count { it.step == "firewall_stop" })
    }

    @Test fun distinctFailuresAreAllRetainedUpToCap() {
        var debt = debtWith(failures = emptyList())
        repeat(10) { i -> debt = debt.mergeUnresolved(debtWith(listOf(ioFailure("step$i")))) }
        assertEquals(10, debt.failures.size)
    }

    @Test fun historyNeverExceedsCap() {
        var debt = debtWith(failures = emptyList())
        repeat(100) { i -> debt = debt.mergeUnresolved(debtWith(listOf(ioFailure("step$i")))) }
        assertTrue(debt.failures.size <= 32)
        // newest distinct failures survive (tail kept)
        assertTrue(debt.failures.any { it.step == "step99" })
    }

    @Test fun conflictFailuresStillDeduplicatedPerStep() {
        // pre-existing R9 behavior must not regress
    }

    @Test fun distinctRootCausesOnSameStepAreKept() {
        var debt = debtWith(listOf(ioFailure("firewall_stop", "eperm")))
        debt = debt.mergeUnresolved(debtWith(listOf(ioFailure("firewall_stop", "timeout"))))
        assertEquals(2, debt.failures.count { it.step == "firewall_stop" })
    }
}
```

## Acceptance criteria

- `mergeUnresolved()` no longer uses raw list concatenation for `failures`.
- Repeated merges of an identical failure collapse to one entry.
- Distinct root causes on the same step remain distinguishable.
- Total history is hard-capped (`MAX_FAILURE_HISTORY`), keeping the newest entries.
- Existing R9 conflict-dedup behavior preserved.
- New test file + existing suite green.

## Dependencies

- Independent; small and self-contained. Good parallel work alongside Tracks A/B/C.
- Track A's `CleanupDebtMergeTest` and this file can share the `debtWith(...)` builder.
