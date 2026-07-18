package be.mygod.vpnhotspot.proxy

import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit contract for the Track A reflection harness.
 *
 * The harness intentionally reaches these private controller fields:
 * latestSnapshot, sanitizedDaemonGeneration, sanitizedSessionId, sanitizedEpoch,
 * firewallGeneration, applied, cleanupDebt, serviceActivated, nextDebtGeneration.
 *
 * It also invokes these private methods:
 * cleanupApplied(reason, daemonAvailable), retryCleanupDebtSafely(), reconcile(state),
 * and scheduleDebtRetry(debt).
 *
 * fakeVpnUpstream() allocates ProxyVpnUpstream without an Android Network constructor
 * and writes the private handle and interfaces fields. Tracks C–E must update this
 * contract and the harness together when any of those names or shapes change.
 */
class ControllerHarnessContractTest {
    @Test
    fun privateControllerContract_isExplicitAndCurrent() {
        val fields = setOf(
            "latestSnapshot",
            "sanitizedDaemonGeneration",
            "sanitizedSessionId",
            "sanitizedEpoch",
            "firewallGeneration",
            "applied",
            "cleanupDebt",
            "serviceActivated",
            "scheduledRetryGeneration",
            "nextDebtGeneration",
        )
        fields.forEach { name ->
            assertNotNull("missing ProxyOnlyController field '$name'", ProxyOnlyController::class.java.findField(name))
        }
        assertNotNull(
            "missing concrete cleanup supervisor field",
            ProxyOnlyController::class.java.findField("cleanupSupervisor"),
        )
        assertNull(
            "raw cleanupScope contract must be removed",
            ProxyOnlyController::class.java.findField("cleanupScope"),
        )

        assertSuspendMethod("cleanupApplied", argumentCount = 2)
        assertSuspendMethod("retryCleanupDebtSafely", argumentCount = 0)
        assertSuspendMethod("reconcile", argumentCount = 1)
        assertTrue(
            "missing private scheduleDebtRetry(debt)",
            ProxyOnlyController::class.java.declaredMethods.any { method ->
                method.name == "scheduleDebtRetry" && method.parameterCount == 1
            },
        )
    }

    @Test
    fun fakeVpnUpstream_privateShape_isExplicitAndCurrent() {
        assertNotNull("missing ProxyVpnUpstream.handle", ProxyVpnUpstream::class.java.findField("handle"))
        assertNotNull("missing ProxyVpnUpstream.interfaces", ProxyVpnUpstream::class.java.findField("interfaces"))
        assertEquals(42L, fakeVpnUpstream(42).handle)
        assertEquals(sortedSetOf("tun0"), fakeVpnUpstream(42).interfaces)
    }

    private fun assertSuspendMethod(name: String, argumentCount: Int) {
        assertTrue(
            "missing private suspend method '$name' with $argumentCount arguments",
            ProxyOnlyController::class.java.declaredMethods.any { method ->
                method.name == name &&
                    method.parameterCount == argumentCount + 1 &&
                    Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
            },
        )
    }
}

internal fun ProxyOnlyController.sanitizedDaemonGenerationForTrackATest(): Long? =
    readTrackAField("sanitizedDaemonGeneration")

internal fun ProxyOnlyController.sanitizedSessionIdForTrackATest(): Long? =
    readTrackAField("sanitizedSessionId")

internal fun ProxyOnlyController.sanitizedEpochForTrackATest(): Long? =
    readTrackAField("sanitizedEpoch")

internal fun ProxyOnlyController.firewallGenerationForTrackATest(): Long =
    readTrackAField<AtomicLong>("firewallGeneration").get()

private fun Class<*>.findField(name: String): Field? {
    var type: Class<*>? = this
    while (type != null) {
        try {
            return type.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    return null
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any.readTrackAField(name: String): T {
    val field = javaClass.findField(name) ?: error("No field '$name' on ${javaClass.name}")
    return field.get(this) as T
}
