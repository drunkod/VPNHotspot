package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.DaemonIdentity
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProxyFirewallRpcTest {
    @Test
    fun successfulReplyIsReturnedUnchanged() = runBlocking {
        val command = ProxyFirewallCommand()
        val acknowledgement = ProxyFirewallAck(
            status = ProxyFirewallAck.Status.OK,
            identity = DaemonIdentity(session_id = 5, epoch = 2, generation = 5),
            handle_id = 9,
        )
        var observed: ProxyFirewallCommand? = null
        val rpc = RootProxyFirewallRpc { request ->
            observed = request
            acknowledgement
        }

        assertSame(acknowledgement, rpc.execute(command))
        assertSame(command, observed)
    }

    @Test
    fun nonIoTransportFailureIsMappedToIOException() = runBlocking {
        val rpc = RootProxyFirewallRpc { throw IllegalStateException("root session failed") }

        val failure = runCatching { rpc.execute(ProxyFirewallCommand()) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("root session failed", failure?.cause?.message)
    }

    @Test
    fun ioTransportFailurePreservesIdentity() = runBlocking {
        val expected = IOException("socket closed")
        val rpc = RootProxyFirewallRpc { throw expected }

        val failure = runCatching { rpc.execute(ProxyFirewallCommand()) }.exceptionOrNull()

        assertSame(expected, failure)
    }

    @Test
    fun cancellationPropagatesWithoutTransportWrapping() = runBlocking {
        val expected = CancellationException("controller transaction cancelled")
        val rpc = RootProxyFirewallRpc { throw expected }

        val failure = runCatching { rpc.execute(ProxyFirewallCommand()) }.exceptionOrNull()

        assertSame(expected, failure)
    }
}
