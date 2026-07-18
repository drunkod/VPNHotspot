package be.mygod.vpnhotspot.proxy

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksConnectLifecycleTest {
    @Test
    fun relayFailureAfterSuccessDoesNotEmitSecondSocksReply() = runBlocking {
        val replies = mutableListOf<Int>()
        var relayed = false

        establishSocksConnectThenRelay(
            establish = { Unit },
            onSetupFailure = { replies += 4 },
            onEstablished = { replies += 0 },
            relay = {
                relayed = true
                throw IOException("connection reset after success")
            },
        )

        assertTrue(relayed)
        assertEquals(listOf(0), replies)
    }

    @Test
    fun setupFailureEmitsOneFailureAndNeverStartsRelay() = runBlocking {
        val replies = mutableListOf<Int>()
        var relayed = false

        establishSocksConnectThenRelay<Unit>(
            establish = { throw IOException("connect failed") },
            onSetupFailure = { replies += 4 },
            onEstablished = { replies += 0 },
            relay = { relayed = true },
        )

        assertFalse(relayed)
        assertEquals(listOf(4), replies)
    }
}
