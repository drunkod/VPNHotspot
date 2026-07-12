# Track E — normalization diagnostics + deterministic downstream selection

**Goal:** stop `DesiredProxyState.normalized()` from silently discarding invalid
interface/client records, and make downstream IPv4 selection an explicit deterministic
policy instead of "first routable in observation order".

Today (`ProxyModels.kt` `normalized()`, lines 150–184):

- Invalid interface names, malformed MACs, and non-routable IPv4s are dropped with **no
  diagnostic** — a misconfigured client just vanishes, which is hard to debug.
- Downstream IPv4 is chosen as `group.mapNotNull { it.ipv4Address }.firstOrNull { isRoutableIpv4(it) }`
  — order-dependent: if the collector reports addresses in a different order, a different
  IP is selected, causing avoidable runtime-key churn.

**Blocker refs:** R10 §7 ("Normalization diagnostics and address choice remain open"),
R11 structural blocker #4, R12 remaining blocker #4.

**Files touched:**

```text
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyModels.kt                (diagnostics + deterministic pick)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/ProxyNormalizationReport.kt   (new)
mobile/src/test/java/be/mygod/vpnhotspot/proxy/NormalizationTest.kt          (new)
```

---

## Step E1 — a structured normalization report

`normalized()` currently returns only the cleaned `DesiredProxyState`. Add a sibling
that also returns *what was dropped and why*, so the controller/UI can surface config
problems (README Phase 7 already lists "per-app VPN denial guidance" and diagnostics).

```kotlin
package be.mygod.vpnhotspot.proxy

/** Non-fatal diagnostics produced while normalizing a desired snapshot. */
data class ProxyNormalizationReport(
    val droppedDownstreams: List<Dropped> = emptyList(),
    val droppedClients: List<Dropped> = emptyList(),
    val downstreamAddressChoices: List<AddressChoice> = emptyList(),
) {
    data class Dropped(val identity: String, val reason: Reason)
    data class AddressChoice(
        val interfaceName: String,
        val candidates: List<String>,
        val chosen: String?,
        val policy: String,
    )
    enum class Reason {
        INVALID_INTERFACE_NAME,
        INVALID_MAC,
        NO_ROUTABLE_IPV4,
        NON_ROUTABLE_IPV4_FILTERED,
    }

    val isClean: Boolean get() =
        droppedDownstreams.isEmpty() && droppedClients.isEmpty()
}
```

---

## Step E2 — deterministic downstream IPv4 selection

Replace observation-order `firstOrNull` with an explicit, stable ordering policy. The
policy: among routable candidates, pick the numerically smallest (stable, order-free),
and record the choice.

```kotlin
/** Deterministic: smallest routable IPv4 by 32-bit unsigned value. Order-independent. */
private fun chooseDownstreamIpv4(candidates: List<String>): String? =
    candidates.filter { isRoutableIpv4(it) }
        .map { normalizeIpv4(it) }
        .distinct()
        .minByOrNull { ipv4ToULong(it) }   // stable total order, not observation order

private fun ipv4ToULong(addr: String): Long {
    val o = addr.split('.').map { it.toInt() }
    return (o[0].toLong() shl 24) or (o[1].toLong() shl 16) or
           (o[2].toLong() shl 8) or o[3].toLong()
}
```

Rationale: the previous `firstOrNull` made the runtime key depend on collector ordering.
A total order over the candidate set removes that churn; the exact policy (smallest) is
arbitrary but **documented and stable**. If operators need a preferred subnet later, the
policy string field makes the choice auditable.

---

## Step E3 — `normalizedWithReport()` and keep `normalized()` as a thin wrapper

Preserve the existing call sites; add the reporting variant.

```kotlin
data class NormalizationOutput(
    val state: DesiredProxyState,
    val report: ProxyNormalizationReport,
)

fun DesiredProxyState.normalizedWithReport(): NormalizationOutput {
    val droppedDown = mutableListOf<ProxyNormalizationReport.Dropped>()
    val droppedClients = mutableListOf<ProxyNormalizationReport.Dropped>()
    val choices = mutableListOf<ProxyNormalizationReport.AddressChoice>()

    val ds = downstreams
        .filter {
            val ok = isValidInterfaceName(it.interfaceName.trim())
            if (!ok) droppedDown += ProxyNormalizationReport.Dropped(
                it.interfaceName, ProxyNormalizationReport.Reason.INVALID_INTERFACE_NAME)
            ok
        }
        .groupBy { it.interfaceName.trim() }
        .map { (iface, group) ->
            val candidates = group.mapNotNull { it.ipv4Address }
            val chosen = chooseDownstreamIpv4(candidates)
            choices += ProxyNormalizationReport.AddressChoice(
                iface, candidates, chosen, policy = "smallest-routable-ipv4")
            if (candidates.isNotEmpty() && chosen == null) droppedDown += ProxyNormalizationReport.Dropped(
                iface, ProxyNormalizationReport.Reason.NO_ROUTABLE_IPV4)
            ManagedDownstream(interfaceName = iface, ipv4Address = chosen)
        }
        .sortedBy { it.interfaceName }

    val clients = allowedClients.mapNotNull { client ->
        val mac = canonicalizeMac(client.mac)
        if (mac == null) {
            droppedClients += ProxyNormalizationReport.Dropped(
                client.mac, ProxyNormalizationReport.Reason.INVALID_MAC)
            return@mapNotNull null
        }
        val ips = client.ipv4Addresses.filter { isRoutableIpv4(it) }
            .map { normalizeIpv4(it) }.distinct().sorted()
        if (ips.isEmpty()) {
            droppedClients += ProxyNormalizationReport.Dropped(
                mac, ProxyNormalizationReport.Reason.NO_ROUTABLE_IPV4)
            return@mapNotNull null
        }
        AllowedClient(mac = mac, ipv4Addresses = ips)
    }.groupBy { it.mac }.map { (mac, g) ->
        AllowedClient(mac, g.flatMap { it.ipv4Addresses }.distinct().sorted())
    }.sortedBy { it.mac }

    return NormalizationOutput(
        copy(downstreams = ds, allowedClients = clients),
        ProxyNormalizationReport(droppedDown, droppedClients, choices),
    )
}

// Existing API preserved:
fun DesiredProxyState.normalized(): DesiredProxyState = normalizedWithReport().state
```

The controller collector (`start()` in `ProxyOnlyController.kt`) can optionally forward
`report` to `reporter`/`stateSink` when `!report.isClean`, without changing reconciliation.

---

## Step E4 — tests

```kotlin
class NormalizationTest {
    @Test fun downstreamIpv4SelectionIsOrderIndependent() {
        val a = desired(downstreams = listOf(
            ManagedDownstream("wlan1", "192.168.43.5"),
            ManagedDownstream("wlan1", "10.0.0.9"),
        )).normalized()
        val b = desired(downstreams = listOf(
            ManagedDownstream("wlan1", "10.0.0.9"),
            ManagedDownstream("wlan1", "192.168.43.5"),
        )).normalized()
        assertEquals(a.downstreams, b.downstreams)          // same runtime key
        assertEquals("10.0.0.9", a.downstreams.single().ipv4Address) // smallest chosen
    }

    @Test fun invalidMacIsReportedNotSilentlyDropped() {
        val out = desired(clients = listOf(AllowedClient("ZZ:ZZ", listOf("10.0.0.2"))))
            .normalizedWithReport()
        assertTrue(out.state.allowedClients.isEmpty())
        assertTrue(out.report.droppedClients.any {
            it.reason == ProxyNormalizationReport.Reason.INVALID_MAC })
    }

    @Test fun clientWithNoRoutableIpv4IsReported() {
        val out = desired(clients = listOf(AllowedClient("AA:BB:CC:DD:EE:01", listOf("127.0.0.1"))))
            .normalizedWithReport()
        assertTrue(out.report.droppedClients.any {
            it.reason == ProxyNormalizationReport.Reason.NO_ROUTABLE_IPV4 })
    }

    @Test fun invalidInterfaceNameIsReported() { /* ... INVALID_INTERFACE_NAME ... */ }

    @Test fun cleanInputProducesEmptyReport() {
        val out = desired(clients = listOf(AllowedClient("AA:BB:CC:DD:EE:01", listOf("10.0.0.2"))))
            .normalizedWithReport()
        assertTrue(out.report.isClean)
    }
}
```

## Acceptance criteria

- `normalized()` behavior is byte-compatible for the returned state (existing call sites
  unaffected), except downstream IPv4 is now deterministic (smallest routable).
- `normalizedWithReport()` exposes every dropped record with a typed reason and the
  address-choice policy.
- Selection is proven order-independent by test.
- Existing normalization tests (if any) and the new suite green.

## Dependencies

- Independent of Tracks A–D. The reporting hook into `stateSink` is optional and can be
  deferred to Phase 7 UI without blocking the deterministic-selection fix.
