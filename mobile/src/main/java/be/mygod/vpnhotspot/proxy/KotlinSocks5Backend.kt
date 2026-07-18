package be.mygod.vpnhotspot.proxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import be.mygod.vpnhotspot.App.Companion.app
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Copies a relay stream and flushes every chunk so small/interactive responses are delivered promptly. */
internal fun InputStream.copyToAndFlush(
    output: OutputStream,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
): Long {
    require(bufferSize > 0) { "bufferSize must be positive" }
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        output.flush()
        copied += read
    }
    return copied
}

/** Bounded set of remote UDP endpoints that this association has actually contacted. */
internal class UdpRemoteAllowlist(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = LinkedHashSet<InetSocketAddress>()

    @Synchronized
    fun record(endpoint: InetSocketAddress) {
        entries.remove(endpoint)
        entries.add(endpoint)
        while (entries.size > maxEntries) entries.remove(entries.first())
    }

    @Synchronized
    operator fun contains(endpoint: InetSocketAddress): Boolean = endpoint in entries

    @Synchronized
    internal fun size(): Int = entries.size
}

/**
 * App-owned SOCKS5 backend for the MVP.
 *
 * It implements authenticated TCP CONNECT and UDP ASSOCIATE. Every Internet-facing socket is
 * bound to the selected Android VPN [Network] before connect/send. Domain resolution uses a
 * small bounded dispatcher plus a per-request timeout around [Network.getAllByName], so VPN DNS
 * blackholes cannot create unbounded resolver concurrency or indefinitely wedge proxy sessions.
 */
class KotlinSocks5Backend(
    private val connectivity: ConnectivityManager = checkNotNull(app.getSystemService()),
) : ProxyBackend {
    private class Runtime(
        val id: Long,
        val config: ProxyBackendConfig,
        val network: Network,
        val server: ServerSocket,
        val rootJob: Job,
        val scope: CoroutineScope,
    ) {
        val closeMutex = Mutex()
        val closeables = ConcurrentHashMap.newKeySet<Closeable>()
        val activeTcp = AtomicInteger()
        val activeUdp = AtomicInteger()
    }

    private sealed interface Target {
        data class Address(val address: InetAddress) : Target
        data class Domain(val name: String) : Target
    }

    private data class Request(val command: Int, val target: Target, val port: Int)
    private data class UdpRequest(val target: Target, val port: Int, val payload: ByteArray)

    private val lock = Mutex()
    private val nextId = AtomicLong(1L)
    private val runtimes = mutableMapOf<Long, Runtime>()
    private val random = SecureRandom()
    private val dnsDispatcher = Dispatchers.IO.limitedParallelism(DNS_WORKER_COUNT)

    override suspend fun start(config: ProxyBackendConfig): ProxyBackendHandle {
        require(config.tcpPort in 1..65_535)
        require(config.udpPortRange == null ||
            (config.udpPortRange.first in 1..65_535 &&
                config.udpPortRange.last in config.udpPortRange.first..65_535))
        val network = withContext(Dispatchers.IO) { resolveNetwork(config.vpnNetworkHandle) }
        val server = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(anyIpv4(), config.tcpPort), 128)
            }
        }
        val id = nextId.getAndIncrement()
        val rootJob = SupervisorJob()
        val scope = CoroutineScope(rootJob + Dispatchers.IO + CoroutineName("proxy-backend-$id"))
        val runtime = Runtime(id, config, network, server, rootJob, scope)
        runtime.closeables += server
        lock.withLock { runtimes[id] = runtime }
        scope.launch { acceptLoop(runtime) }
        return ProxyBackendHandle(id)
    }

    override suspend fun replaceAcl(handle: ProxyBackendHandle, clients: List<AllowedClient>) {
        requireRuntime(handle)
        // Client admission is enforced authoritatively by the root firewall. The backend still
        // requires SOCKS username/password authentication for every control connection.
    }

    override suspend fun runOutboundProbes(
        handle: ProxyBackendHandle,
        requirements: ProbeRequirements,
    ): ProbeReport {
        val runtime = requireRuntime(handle)
        val results = linkedMapOf<ProbeKind, ProbeResult>()
        results[ProbeKind.INTERNAL_LISTENER_READY] = if (!runtime.server.isClosed && runtime.server.isBound) {
            ProbeResult.Success
        } else ProbeResult.Failed(ProbeFailure.Internal("listener is closed"))
        results[ProbeKind.APP_UID_BIND] = probe("VPN socket bind") {
            Socket().use { runtime.network.bindSocket(it) }
        }
        results[ProbeKind.VPN_DNS] = probe("VPN DNS") {
            resolveDomain(runtime.network, "example.com")
        }
        results[ProbeKind.OUTBOUND_TCP] = probe("outbound TCP") {
            val address = resolveDomain(runtime.network, "example.com")
            Socket().use { socket ->
                runtime.network.bindSocket(socket)
                socket.connect(InetSocketAddress(address, 443), PROBE_TIMEOUT_MS)
            }
        }
        if (requirements.udpRequired) {
            results[ProbeKind.OUTBOUND_UDP] = probe("outbound UDP") { probeUdp(runtime) }
        }
        return ProbeReport(results)
    }

    override suspend fun stats(handle: ProxyBackendHandle): ProxyBackendStats {
        val runtime = requireRuntime(handle)
        return ProxyBackendStats(runtime.activeTcp.get(), runtime.activeUdp.get())
    }

    override suspend fun emergencyCloseListener(
        handle: ProxyBackendHandle,
        reason: String,
    ): CleanupReport {
        val runtime = lock.withLock { runtimes[handle.id] }
            ?: return CleanupReport.noOp("backend absent")
        return closeRuntime(runtime, remove = false, reason = "emergency: $reason")
    }

    override suspend fun stop(handle: ProxyBackendHandle): CleanupReport {
        val runtime = lock.withLock { runtimes[handle.id] }
            ?: return CleanupReport.noOp("backend absent")
        return closeRuntime(runtime, remove = true, reason = "stop")
    }

    private suspend fun closeRuntime(runtime: Runtime, remove: Boolean, reason: String): CleanupReport =
        runtime.closeMutex.withLock {
            val failures = mutableListOf<CleanupFailure>()
            runtime.closeables.toList().forEach { closeable ->
                runCatching { closeable.close() }.exceptionOrNull()?.let {
                    failures += CleanupFailure("backend_close", it)
                }
            }
            runtime.rootJob.cancel(CancellationException("proxy backend $reason"))
            try {
                runtime.rootJob.join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failures += CleanupFailure("backend_runtime_join", failure)
            }
            if (remove && failures.isEmpty()) lock.withLock {
                if (runtimes[runtime.id] === runtime) runtimes.remove(runtime.id)
            }
            CleanupReport(failures = failures)
        }

    private suspend fun requireRuntime(handle: ProxyBackendHandle): Runtime = lock.withLock {
        runtimes[handle.id] ?: throw IllegalStateException("unknown proxy backend handle ${handle.id}")
    }

    private fun resolveNetwork(handle: Long): Network {
        val network = connectivity.allNetworks.firstOrNull { it.networkHandle == handle }
            ?: throw IOException("selected VPN network disappeared")
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: throw IOException("selected VPN capabilities unavailable")
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw SecurityException("selected network is not a VPN")
        }
        return network
    }

    private suspend fun acceptLoop(runtime: Runtime) {
        while (runtime.scope.isActive && !runtime.server.isClosed) {
            val client = try {
                runtime.server.accept()
            } catch (e: SocketException) {
                if (runtime.server.isClosed || !runtime.scope.isActive) return
                throw e
            }
            client.tcpNoDelay = true
            runtime.closeables += client
            runtime.scope.launch {
                try {
                    handleClient(runtime, client)
                } catch (_: EOFException) {
                } catch (_: SocketException) {
                } finally {
                    runtime.closeables.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(runtime: Runtime, client: Socket) {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        if (input.readUnsignedByte() != SOCKS_VERSION) return
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (METHOD_USERNAME_PASSWORD.toByte() !in methods) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NONE_ACCEPTABLE.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_USERNAME_PASSWORD.toByte()))
        output.flush()
        if (!authenticate(input, output, runtime.config.credentials)) return

        val request = readRequest(input)
        when (request.command) {
            COMMAND_CONNECT -> handleConnect(runtime, client, input, output, request)
            COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(runtime, client, input, output)
            else -> sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
        }
    }

    private fun authenticate(
        input: DataInputStream,
        output: DataOutputStream,
        credentials: ProxyCredentials,
    ): Boolean {
        if (input.readUnsignedByte() != AUTH_VERSION) return false
        val username = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val password = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val expectedUsername = credentials.username.toByteArray(StandardCharsets.UTF_8)
        val expectedPassword = credentials.password.toByteArray(StandardCharsets.UTF_8)
        val success = try {
            MessageDigest.isEqual(username, expectedUsername) &&
                MessageDigest.isEqual(password, expectedPassword)
        } finally {
            username.fill(0)
            password.fill(0)
            expectedUsername.fill(0)
            expectedPassword.fill(0)
        }
        output.write(byteArrayOf(AUTH_VERSION.toByte(), if (success) 0 else 1))
        output.flush()
        return success
    }

    private fun readRequest(input: DataInputStream): Request {
        if (input.readUnsignedByte() != SOCKS_VERSION) throw IOException("invalid SOCKS request version")
        val command = input.readUnsignedByte()
        if (input.readUnsignedByte() != 0) throw IOException("invalid SOCKS reserved byte")
        val target = readTarget(input, input.readUnsignedByte())
        val port = input.readUnsignedShort()
        return Request(command, target, port)
    }

    private fun readTarget(input: DataInputStream, addressType: Int): Target = when (addressType) {
        ADDRESS_IPV4 -> Target.Address(InetAddress.getByAddress(ByteArray(4).also(input::readFully)))
        ADDRESS_DOMAIN -> {
            val bytes = ByteArray(input.readUnsignedByte()).also(input::readFully)
            Target.Domain(bytes.toString(StandardCharsets.US_ASCII))
        }
        ADDRESS_IPV6 -> {
            input.skipBytes(16)
            throw UnsupportedAddressException()
        }
        else -> throw UnsupportedAddressException()
    }

    private suspend fun handleConnect(
        runtime: Runtime,
        client: Socket,
        clientInput: DataInputStream,
        clientOutput: DataOutputStream,
        request: Request,
    ) {
        val outbound = Socket()
        runtime.closeables += outbound
        try {
            establishSocksConnectThenRelay(
                establish = {
                    val address = resolveTarget(runtime.network, request.target)
                    runtime.network.bindSocket(outbound)
                    outbound.connect(InetSocketAddress(address, request.port), CONNECT_TIMEOUT_MS)
                },
                onSetupFailure = { failure ->
                    when (failure) {
                        is UnsupportedAddressException -> sendReply(clientOutput, REPLY_ADDRESS_NOT_SUPPORTED)
                        is ConnectException -> sendReply(clientOutput, REPLY_CONNECTION_REFUSED)
                        is IOException -> sendReply(clientOutput, REPLY_HOST_UNREACHABLE)
                        else -> throw failure
                    }
                },
                onEstablished = {
                    sendReply(clientOutput, REPLY_SUCCEEDED, outbound.localAddress, outbound.localPort)
                    client.soTimeout = 0
                    outbound.soTimeout = 0
                },
                relay = {
                    runtime.activeTcp.incrementAndGet()
                    try {
                        coroutineScope {
                            val clientToRemote = launch(Dispatchers.IO) {
                                try {
                                    clientInput.copyTo(outbound.getOutputStream())
                                } finally {
                                    runCatching { outbound.shutdownOutput() }
                                }
                            }
                            val remoteToClient = launch(Dispatchers.IO) {
                                try {
                                    outbound.getInputStream().copyToAndFlush(clientOutput)
                                } finally {
                                    runCatching { client.shutdownOutput() }
                                }
                            }
                            joinAll(clientToRemote, remoteToClient)
                        }
                    } finally {
                        runtime.activeTcp.decrementAndGet()
                    }
                },
            )
        } finally {
            runtime.closeables.remove(outbound)
            runCatching { outbound.close() }
        }
    }

    private suspend fun handleUdpAssociate(
        runtime: Runtime,
        client: Socket,
        clientInput: DataInputStream,
        clientOutput: DataOutputStream,
    ) {
        val range = runtime.config.udpPortRange
        if (range == null || runtime.activeUdp.incrementAndGet() > runtime.config.maxUdpAssociations) {
            if (range != null) runtime.activeUdp.decrementAndGet()
            sendReply(clientOutput, REPLY_GENERAL_FAILURE)
            return
        }
        val relay = try {
            openRelay(range)
        } catch (e: IOException) {
            runtime.activeUdp.decrementAndGet()
            sendReply(clientOutput, REPLY_GENERAL_FAILURE)
            return
        }
        val outbound = DatagramSocket(null)
        runtime.closeables += relay
        runtime.closeables += outbound
        try {
            outbound.reuseAddress = false
            outbound.bind(InetSocketAddress(anyIpv4(), 0))
            runtime.network.bindSocket(outbound)
            // RFC 1928 clients need a reachable relay address; use the downstream address chosen
            // by the TCP control connection rather than the ambiguous 0.0.0.0 wildcard.
            sendReply(clientOutput, REPLY_SUCCEEDED, client.localAddress, relay.localPort)
            client.soTimeout = 0
            val clientEndpoint = AtomicReference<InetSocketAddress?>(null)
            val remoteAllowlist = UdpRemoteAllowlist(MAX_UDP_REMOTE_ENDPOINTS)
            coroutineScope {
                val control = launch(Dispatchers.IO) {
                    try {
                        while (clientInput.read() >= 0) Unit
                    } finally {
                        relay.close()
                        outbound.close()
                    }
                }
                val toVpn = launch(Dispatchers.IO) {
                    udpClientToVpn(runtime, client, relay, outbound, clientEndpoint, remoteAllowlist)
                }
                val toClient = launch(Dispatchers.IO) {
                    udpVpnToClient(relay, outbound, clientEndpoint, remoteAllowlist)
                }
                joinAll(control, toVpn, toClient)
            }
        } finally {
            runtime.activeUdp.decrementAndGet()
            runtime.closeables.remove(relay)
            runtime.closeables.remove(outbound)
            relay.close()
            outbound.close()
        }
    }

    private suspend fun udpClientToVpn(
        runtime: Runtime,
        control: Socket,
        relay: DatagramSocket,
        outbound: DatagramSocket,
        clientEndpoint: AtomicReference<InetSocketAddress?>,
        remoteAllowlist: UdpRemoteAllowlist,
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        while (!relay.isClosed && runtime.scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            relay.receive(packet)
            if (packet.address != control.inetAddress) continue
            val endpoint = InetSocketAddress(packet.address, packet.port)
            val known = clientEndpoint.get()
            if (known == null) clientEndpoint.compareAndSet(null, endpoint) else if (known != endpoint) continue
            val request = parseUdpRequest(packet.data, packet.offset, packet.length) ?: continue
            val address = try {
                resolveTarget(runtime.network, request.target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                continue
            }
            val remote = InetSocketAddress(address, request.port)
            remoteAllowlist.record(remote)
            outbound.send(DatagramPacket(request.payload, request.payload.size, remote))
        }
    }

    private fun udpVpnToClient(
        relay: DatagramSocket,
        outbound: DatagramSocket,
        clientEndpoint: AtomicReference<InetSocketAddress?>,
        remoteAllowlist: UdpRemoteAllowlist,
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        while (!outbound.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            outbound.receive(packet)
            val endpoint = clientEndpoint.get() ?: continue
            val source = packet.address as? Inet4Address ?: continue
            if (InetSocketAddress(source, packet.port) !in remoteAllowlist) continue
            val encoded = ByteArrayOutputStream(packet.length + 10).use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeShort(0)
                    output.writeByte(0)
                    output.writeByte(ADDRESS_IPV4)
                    output.write(source.address)
                    output.writeShort(packet.port)
                    output.write(packet.data, packet.offset, packet.length)
                }
                bytes.toByteArray()
            }
            relay.send(DatagramPacket(encoded, encoded.size, endpoint))
        }
    }

    private fun parseUdpRequest(data: ByteArray, offset: Int, length: Int): UdpRequest? = runCatching {
        DataInputStream(data.copyOfRange(offset, offset + length).inputStream()).use { input ->
            if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) return null
            val target = readTarget(input, input.readUnsignedByte())
            val port = input.readUnsignedShort()
            UdpRequest(target, port, input.readBytes())
        }
    }.getOrNull()

    private fun openRelay(range: IntRange): DatagramSocket {
        var last: IOException? = null
        for (port in range) {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(anyIpv4(), port))
                return socket
            } catch (e: IOException) {
                last = e
                socket.close()
            }
        }
        throw BindException("no free UDP relay port in $range").also { last?.let(it::initCause) }
    }

    private suspend fun resolveTarget(network: Network, target: Target): Inet4Address = when (target) {
        is Target.Address -> target.address as? Inet4Address
            ?: throw UnsupportedAddressException()
        is Target.Domain -> resolveDomain(network, target.name)
    }

    private suspend fun resolveDomain(network: Network, domain: String): Inet4Address = try {
        withTimeout(DNS_TIMEOUT_MS) {
            withContext(dnsDispatcher) {
                network.getAllByName(domain).firstOrNull { it is Inet4Address } as? Inet4Address
                    ?: throw IOException("VPN DNS returned no IPv4 address for $domain")
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw SocketTimeoutException("VPN DNS timed out for $domain").apply { initCause(timeout) }
    }

    private fun sendReply(
        output: DataOutputStream,
        reply: Int,
        boundAddress: InetAddress = anyIpv4(),
        boundPort: Int = 0,
    ) {
        val ipv4 = boundAddress as? Inet4Address ?: anyIpv4()
        output.writeByte(SOCKS_VERSION)
        output.writeByte(reply)
        output.writeByte(0)
        output.writeByte(ADDRESS_IPV4)
        output.write(ipv4.address)
        output.writeShort(boundPort)
        output.flush()
    }

    private suspend fun probe(operation: String, block: suspend () -> Unit): ProbeResult = try {
        block()
        ProbeResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        ProbeResult.Failed(ProbeFailure.PermissionDenied)
    } catch (_: SocketTimeoutException) {
        ProbeResult.Failed(ProbeFailure.Timeout(operation))
    } catch (e: IOException) {
        ProbeResult.Failed(ProbeFailure.NetworkError(e.message ?: operation))
    } catch (e: Throwable) {
        ProbeResult.Failed(ProbeFailure.Internal(e.message ?: e.javaClass.simpleName))
    }

    private fun probeUdp(runtime: Runtime) {
        val resolver = connectivity.getLinkProperties(runtime.network)?.dnsServers
            ?.firstOrNull { it is Inet4Address }
            ?: throw IOException("VPN has no IPv4 DNS server")
        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(anyIpv4(), 0))
            runtime.network.bindSocket(socket)
            socket.soTimeout = PROBE_TIMEOUT_MS
            val id = random.nextInt(0x10000)
            val query = dnsQuery(id)
            socket.send(DatagramPacket(query, query.size, resolver, 53))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            if (response.length < 2 ||
                response.data[response.offset].toInt() and 0xff != id ushr 8 ||
                response.data[response.offset + 1].toInt() and 0xff != id and 0xff) {
                throw IOException("invalid VPN DNS UDP response")
            }
        }
    }

    private fun dnsQuery(id: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeShort(id)
            output.writeShort(0x0100)
            output.writeShort(1)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(0)
            for (label in listOf("example", "com")) {
                output.writeByte(label.length)
                output.write(label.toByteArray(StandardCharsets.US_ASCII))
            }
            output.writeByte(0)
            output.writeShort(1)
            output.writeShort(1)
        }
        bytes.toByteArray()
    }

    private class UnsupportedAddressException : IOException()

    private companion object {
        const val SOCKS_VERSION = 5
        const val AUTH_VERSION = 1
        const val METHOD_USERNAME_PASSWORD = 2
        const val METHOD_NONE_ACCEPTABLE = 0xff
        const val COMMAND_CONNECT = 1
        const val COMMAND_UDP_ASSOCIATE = 3
        const val ADDRESS_IPV4 = 1
        const val ADDRESS_DOMAIN = 3
        const val ADDRESS_IPV6 = 4
        const val REPLY_SUCCEEDED = 0
        const val REPLY_GENERAL_FAILURE = 1
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_CONNECTION_REFUSED = 5
        const val REPLY_COMMAND_NOT_SUPPORTED = 7
        const val REPLY_ADDRESS_NOT_SUPPORTED = 8
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val PROBE_TIMEOUT_MS = 5_000
        const val DNS_TIMEOUT_MS = 5_000L
        const val DNS_WORKER_COUNT = 2
        const val MAX_UDP_PACKET = 65_535
        const val MAX_UDP_REMOTE_ENDPOINTS = 256

        fun anyIpv4(): Inet4Address = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)) as Inet4Address
    }
}
