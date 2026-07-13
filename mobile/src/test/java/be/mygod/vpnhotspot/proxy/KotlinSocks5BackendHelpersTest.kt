package be.mygod.vpnhotspot.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSocks5BackendHelpersTest {
    @Test
    fun relayCopyFlushesEveryChunk() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val output = TrackingOutput()

        val copied = input.copyToAndFlush(output, bufferSize = 2)

        assertEquals(5L, copied)
        assertEquals(3, output.flushCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.toByteArray())
    }

    @Test
    fun udpRemoteAllowlistIsBoundedAndRefreshesRecentEntries() {
        val allowlist = UdpRemoteAllowlist(maxEntries = 2)
        val first = endpoint(1, 53)
        val second = endpoint(2, 443)
        val third = endpoint(3, 123)
        val fourth = endpoint(4, 853)

        allowlist.record(first)
        allowlist.record(second)
        allowlist.record(third)
        assertFalse(first in allowlist)
        assertTrue(second in allowlist)
        assertTrue(third in allowlist)
        assertEquals(2, allowlist.size())

        allowlist.record(second)
        allowlist.record(fourth)
        assertTrue(second in allowlist)
        assertFalse(third in allowlist)
        assertTrue(fourth in allowlist)
        assertEquals(2, allowlist.size())
    }

    private fun endpoint(lastOctet: Int, port: Int) = InetSocketAddress(
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, lastOctet.toByte())),
        port,
    )

    private class TrackingOutput : ByteArrayOutputStream() {
        var flushCount = 0
            private set

        override fun flush() {
            flushCount += 1
            super.flush()
        }
    }
}
