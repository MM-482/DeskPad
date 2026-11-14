package com.deskpad.app.network

import android.content.Context
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.model.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** Discovery is an untrusted hint. All control traffic requires an imported pin and authenticated TLS. */
class PcClientManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairings = PairingStore(context.applicationContext)
    private val lock = Any()
    private var closed = false
    private var session: Session? = null
    private var qrJob: Job? = null
    private var qrEndpoints: Set<String> = emptySet()
    private var attemptGeneration = 0L
    private var discoveryJob: Job? = null
    private var discoverySocket: DatagramSocket? = null
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()
    private val _authenticatedHost = MutableStateFlow<DiscoveredHost?>(null)
    val authenticatedHost: StateFlow<DiscoveredHost?> = _authenticatedHost.asStateFlow()

    private class Session(val endpoint: String, val generation: Long, ownerLock: Any) : QrConnectionAttempt {
        val lifetime = SessionLifetime(ownerLock)
        val input = AuthenticatedInputQueue()
        override val authentication = CompletableDeferred<Boolean>()
        @Volatile var socket: Socket? = null
        @Volatile var job: Job? = null
        @Volatile var lastPing: Long = -1

        override fun close() {
            lifetime.close {
                authentication.complete(false)
                input.close()
                // Close before cancelling to unblock a blocking connect/handshake/read/write.
                runCatching { socket?.close() }
                job?.cancel()
            }
        }
    }

    suspend fun hasSavedPairing(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { pairings.load(PairingStore.endpoint(ip, port)) != null }.getOrDefault(false)
    }

    suspend fun forgetPairing(ip: String, port: Int) = withContext(Dispatchers.IO) {
        val endpoint = PairingStore.endpoint(ip, port)
        synchronized(lock) {
            if (session?.endpoint == endpoint || endpoint in qrEndpoints) {
                cancelQrLocked()
                _connectionState.value = ConnectionState.Disconnected
                _authenticatedHost.value = null
            }
            session?.takeIf { it.endpoint == endpoint }?.let { current ->
                session = null
                _connectionState.value = ConnectionState.Disconnected
                _authenticatedHost.value = null
                current.close()
            }
            pairings.forget(endpoint)
        }
    }

    fun startDiscovery() {
        synchronized(lock) {
            if (closed) return
            discoveryJob?.cancel()
            discoverySocket?.close()
            discoverySocket = null
            _discoveredHosts.value = emptyList()
            discoveryJob = scope.launch {
                val found = linkedMapOf<String, DiscoveredHost>()
                val socket = try {
                    DatagramSocket().apply { broadcast = true; soTimeout = 350 }
                } catch (_: IOException) {
                    return@launch
                }
                synchronized(lock) {
                    if (!isActive || closed) { socket.close(); return@launch }
                    discoverySocket = socket
                }
                try {
                    val query = ProtocolCodec.DISCOVER_QUERY.toByteArray(Charsets.UTF_8)
                    val targets = collectBroadcastAddresses()
                    repeat(3) {
                        if (!isActive) return@launch
                        targets.forEach { address ->
                            runCatching { socket.send(DatagramPacket(query, query.size, address, ProtocolCodec.DEFAULT_DISCOVERY_PORT)) }
                        }
                        val deadline = System.nanoTime() + 650_000_000L
                        val bytes = ByteArray(512)
                        while (isActive && System.nanoTime() < deadline) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size)
                                socket.receive(packet)
                                val ip = packet.address.hostAddress ?: continue
                                val offer = ProtocolCodec.parseDiscoveryOffer(
                                    String(packet.data, 0, packet.length, Charsets.UTF_8), ip
                                ) ?: continue
                                if (found.size < 64 || found.containsKey(ip)) found[ip] = offer
                                synchronized(lock) {
                                    if (discoverySocket === socket) _discoveredHosts.value = found.values.toList()
                                }
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Discovery is best effort and never grants trust to a host.
                } finally {
                    socket.close()
                    synchronized(lock) { if (discoverySocket === socket) discoverySocket = null }
                }
            }
        }
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        }
        return result.toList()
    }

    fun connect(
        ip: String,
        tcpPort: Int = ProtocolCodec.DEFAULT_TCP_PORT,
        displayName: String = ip,
        pairingCode: String? = null,
        replacePairing: Boolean = false
    ) {
        val cleanIp = ip.trim()
        val endpoint = try { PairingStore.endpoint(cleanIp, tcpPort) } catch (_: IllegalArgumentException) {
            disconnect()
            _connectionState.value = ConnectionState.Error("Enter a valid PC address and port (1–65535)")
            return
        }
        synchronized(lock) {
            if (closed) return
            cancelQrLocked()
            startSessionLocked(cleanIp, tcpPort, displayName, pairingCode, replacePairing)
        }
    }

    /** A single foreground PIN action enrolls one selected endpoint, with no automatic retry. */
    fun connectPin(ip: String, displayName: String, pin: CharArray, expectedGeneration: Long): Boolean {
        if (!PairingEnvelope.isAllowedHost(ip) || pin.size != 6 || pin.any { it !in '0'..'9' }) {
            pin.fill('\u0000')
            return false
        }
        synchronized(lock) {
            if (closed || attemptGeneration != expectedGeneration) { pin.fill('\u0000'); return false }
            cancelQrLocked()
            session?.close()
            val current = Session(PairingStore.endpoint(ip, ProtocolCodec.DEFAULT_TCP_PORT), attemptGeneration, lock)
            session = current
            _authenticatedHost.value = null
            _connectionState.value = ConnectionState.Connecting(displayName.ifBlank { ip })
            current.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val pairing = PinEnrollmentTransport.enroll(ip, ProtocolCodec.DEFAULT_TCP_PORT, pin) { socket ->
                        synchronized(lock) {
                            if (!isCurrent(current)) { socket.close(); false }
                            else { current.socket = socket; true }
                        }
                    }
                    // Enrollment never saves trust. Reconnect through the normal exact-pin+token path first.
                    if (isCurrent(current)) runSession(current, ip, ProtocolCodec.DEFAULT_TCP_PORT, displayName, pairing.encoded(), true)
                } catch (_: CancellationException) {
                    // An explicit cancellation or newer connection owns the current state.
                } catch (_: Exception) {
                    fail(current, "PIN pairing failed. Open Pair a phone on your PC and enter its current six-digit PIN, then tap Pair again. Your saved pairing is unchanged.")
                } finally {
                    pin.fill('\u0000')
                    current.close()
                }
            }
            current.job!!.invokeOnCompletion { pin.fill('\u0000') }
            current.job!!.start()
            return true
        }
    }

    fun connectionGeneration(): Long = synchronized(lock) { attemptGeneration }
    fun isGenerationCurrent(expected: Long): Boolean = synchronized(lock) { !closed && expected == attemptGeneration }

    /** Only a successful foreground envelope scan calls this explicit out-of-band re-pair path. */
    fun connectPairedHosts(envelope: PairingEnvelope) {
        synchronized(lock) {
            if (closed) return
            cancelQrLocked()
            session?.close()
            session = null
            _authenticatedHost.value = null
            _connectionState.value = ConnectionState.Connecting(envelope.name)
            val generation = attemptGeneration
            qrEndpoints = envelope.hosts.map { PairingStore.endpoint(it, envelope.port) }.toSet()
            qrJob = scope.launch(start = CoroutineStart.LAZY) { runQrCandidates(generation, envelope) }
            qrJob!!.start()
        }
    }

    private fun cancelQrLocked() {
        val previousQr = qrJob
        attemptGeneration++
        qrJob = null
        qrEndpoints = emptySet()
        previousQr?.cancel()
        if (previousQr != null) {
            val abandoned = session
            session = null
            _authenticatedHost.value = null
            abandoned?.close()
        }
    }

    private fun startSessionLocked(
        ip: String, port: Int, name: String, pairingCode: String?, replacePairing: Boolean
    ): Session {
        session?.close()
        val current = Session(PairingStore.endpoint(ip, port), attemptGeneration, lock)
        session = current
        _authenticatedHost.value = null
        _connectionState.value = ConnectionState.Connecting(name.ifBlank { ip })
        current.job = scope.launch(start = CoroutineStart.LAZY) {
            runSession(current, ip, port, name, pairingCode, replacePairing)
        }
        current.job!!.start()
        return current
    }

    private suspend fun runQrCandidates(generation: Long, envelope: PairingEnvelope) {
        try {
            val connected = QrHostConnector.connect(
                hosts = envelope.hosts,
                isCurrent = { synchronized(lock) { !closed && generation == attemptGeneration } },
                start = { host ->
                    synchronized(lock) {
                        if (closed || generation != attemptGeneration) null
                        else startSessionLocked(host, envelope.port, envelope.name, envelope.pairingCode(), true)
                    }
                }
            )
            if (!connected) {
                synchronized(lock) {
                    if (!closed && generation == attemptGeneration) {
                        val old = session
                        session = null
                        _authenticatedHost.value = null
                        _connectionState.value = ConnectionState.Error(
                            "Couldn't reach and verify this PC. Put both devices on the same network, open DeskPad PC, and scan its current QR again."
                        )
                        old?.close()
                    }
                }
            }
        } catch (_: CancellationException) {
            // A new scan, manual connection, mode switch or disconnect owns the new generation.
        } finally {
            synchronized(lock) {
                if (generation == attemptGeneration) {
                    qrJob = null
                    qrEndpoints = emptySet()
                }
            }
        }
    }

    private suspend fun runSession(
        current: Session,
        ip: String,
        port: Int,
        displayName: String,
        suppliedCode: String?,
        replacePairing: Boolean
    ) {
        try {
            val stored = pairings.load(current.endpoint)
            val pairing = if (suppliedCode != null) {
                require(stored == null || replacePairing) { "Choose Replace pairing code before changing a saved PC" }
                PairingCode.parse(suppliedCode)
            } else stored ?: throw IllegalArgumentException("Import the pairing code shown in DeskPad PC first")

            val rawSocket = Socket()
            synchronized(lock) {
                if (session !== current || current.generation != attemptGeneration || current.lifetime.isClosed) { rawSocket.close(); return }
                current.socket = rawSocket
            }
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(ip, port), 5000)
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(PinnedCertificateTrustManager(pairing.pinBytes())), null)
            }
            val tls = context.socketFactory.createSocket(rawSocket, ip, port, true) as SSLSocket
            synchronized(lock) {
                if (session !== current || current.generation != attemptGeneration || current.lifetime.isClosed) { tls.close(); return }
                current.socket = tls
            }
            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            require(tls.enabledProtocols.isNotEmpty()) { "TLS 1.2 or later is required" }
            tls.soTimeout = 5000
            val handshakeDeadline = scope.launch {
                delay(5000)
                fail(current, "PC certificate verification timed out")
            }
            try {
                tls.startHandshake()
            } finally {
                handshakeDeadline.cancel()
            }
            val input = BufferedInputStream(tls.inputStream)
            val output = tls.outputStream
            SecureFrames.write(output, """{"t":"auth","v":2,"token":"${pairing.token}"}""")
            val auth = JSONObject(readFrame(tls, input, 5000) ?: throw IOException("PC closed before authentication"))
            if (auth.optString("t") != "auth_ok" || auth.opt("v") != 2) {
                throw IOException("PC rejected the pairing code or already has a controller")
            }
            val hostName = auth.optString("name").filter { it.code >= 32 && it.code != 127 }.takeIf { it.isNotBlank() }?.take(128)
                ?: displayName.ifBlank { ip }
            synchronized(lock) {
                if (session !== current || current.generation != attemptGeneration || current.lifetime.isClosed) return
                current.lifetime.whileOpen {
                    // Save only after pin+auth succeed and this attempt is still live.
                    if (suppliedCode != null) pairings.save(current.endpoint, pairing)
                    current.input.authenticate()
                    _authenticatedHost.value = DiscoveredHost(hostName, ip, port, "Windows")
                    _connectionState.value = ConnectionState.Connected(hostName, "$ip:$port • Paired TLS", null, TransportMode.WIFI)
                    current.authentication.complete(true)
                }
            }
            coroutineScope {
                launch {
                    try {
                        for (line in current.input.outgoing) {
                            if (!isActive) break
                            SecureFrames.write(output, line)
                        }
                    } catch (_: CancellationException) {
                        // Session teardown owns cleanup.
                    } catch (_: Exception) {
                        fail(current, "Connection lost while sending input")
                    }
                }
                launch {
                    while (isActive && isCurrent(current)) {
                        current.lastPing = System.nanoTime() / 1_000_000L
                        enqueue(current, ProtocolCodec.tcpPingPacket(current.lastPing))
                        delay(2000)
                    }
                }
                while (isActive && isCurrent(current)) {
                    val line = readFrame(tls, input, 10000) ?: break
                    val message = JSONObject(line)
                    when (message.optString("t")) {
                        "pong" -> {
                            val timestamp = message.optLong("ts", -1)
                            if (timestamp != current.lastPing || timestamp < 0) continue
                            val latency = (System.nanoTime() / 1_000_000L - timestamp).coerceIn(1L, 9999L).toInt()
                            synchronized(lock) {
                                val state = _connectionState.value
                                if (session === current && state is ConnectionState.Connected) {
                                    _connectionState.value = state.copy(latencyMs = latency)
                                }
                            }
                        }
                        "error" -> throw IOException("PC ended the authenticated connection")
                        else -> throw IOException("Unexpected message from PC")
                    }
                }
                fail(current, "PC disconnected")
            }
        } catch (_: CancellationException) {
            // Replacement connections must not publish an old error.
        } catch (_: SSLHandshakeException) {
            fail(current, "PC identity could not be verified. Check the PC's pairing code; no insecure connection was made.")
        } catch (error: IllegalArgumentException) {
            fail(current, error.message ?: "Invalid pairing details")
        } catch (_: SocketTimeoutException) {
            fail(current, "PC connection timed out")
        } catch (_: Exception) {
            fail(current, "Unable to establish a paired connection. Check the PC, pairing code, and local network.")
        } finally {
            current.close()
            synchronized(lock) {
                if (session === current) {
                    session = null
                    _authenticatedHost.value = null
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private fun readFrame(socket: SSLSocket, input: InputStream, timeoutMs: Int): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        return SecureFrames.read(input) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException("PC frame timed out")
            socket.soTimeout = remaining.coerceAtMost(timeoutMs.toLong()).coerceAtLeast(1).toInt()
        }
    }

    private fun isCurrent(current: Session): Boolean = synchronized(lock) {
        session === current && !closed && current.generation == attemptGeneration && !current.lifetime.isClosed
    }

    private fun fail(current: Session, message: String) {
        synchronized(lock) {
            if (session === current) {
                session = null
                _authenticatedHost.value = null
                _connectionState.value = ConnectionState.Error(message)
            }
        }
        current.close()
    }

    fun disconnect() {
        val old = synchronized(lock) {
            cancelQrLocked()
            _authenticatedHost.value = null
            session.also { session = null; _connectionState.value = ConnectionState.Disconnected }
        }
        old?.close()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            discoveryJob?.cancel()
            discoverySocket?.close()
            discoverySocket = null
        }
        disconnect()
        scope.cancel()
    }

    private fun enqueue(current: Session, line: String) {
        if (current.input.offer(line) == AuthenticatedInputQueue.Offer.OVERFLOW) {
            fail(current, "Input queue filled. Disconnected to avoid losing a key or button release.")
        }
    }

    private fun send(line: String) {
        val current = synchronized(lock) { session } ?: return
        enqueue(current, line)
    }

    fun sendMove(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) send(ProtocolCodec.tcpMovePacket(dx.coerceIn(-32767, 32767), dy.coerceIn(-32767, 32767)))
    }
    fun sendScroll(sx: Int, sy: Int) {
        if (sx != 0 || sy != 0) send(ProtocolCodec.tcpScrollPacket(sx.coerceIn(-32767, 32767), sy.coerceIn(-32767, 32767)))
    }
    fun sendZoom(delta: Int) {
        if (delta != 0) send(ProtocolCodec.tcpZoomPacket(delta.coerceIn(-32767, 32767)))
    }
    fun sendClick(button: MouseButton, count: Int = 1) = send(ProtocolCodec.tcpClickPacket(button, count.coerceIn(1, 2)))
    fun sendMouseButton(button: MouseButton, down: Boolean) = send(ProtocolCodec.tcpMouseButtonPacket(button, down))
    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (text.length > ProtocolCodec.MAX_TEXT_UNITS) {
            synchronized(lock) { session }?.let { fail(it, "Text is limited to 2048 characters per message") }
            return
        }
        send(ProtocolCodec.tcpTextPacket(text))
    }
    fun sendKey(key: SpecialKey, modifiers: ModifierState = ModifierState()) = send(ProtocolCodec.tcpKeyPacket(key.wireName, modifiers))
    fun sendCharKey(ch: Char, modifiers: ModifierState = ModifierState()) {
        if (ch in ' '..'~') send(ProtocolCodec.tcpKeyPacket(ch.toString(), modifiers))
    }
}
