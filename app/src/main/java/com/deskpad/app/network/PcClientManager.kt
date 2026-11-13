package com.deskpad.app.network

import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.model.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Handles Wi-Fi LAN auto-discovery, reliable TCP control commands, and low-latency UDP motion datagrams.
 */
class PcClientManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private var tcpSocket: Socket? = null
    private var tcpWriter: BufferedWriter? = null
    private var udpSocket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remoteUdpPort: Int = ProtocolCodec.DEFAULT_UDP_PORT
    private var activeHostName: String = ""
    private var activeHostIp: String = ""

    private val tcpQueue = Channel<String>(capacity = 256)
    private val udpQueue = Channel<String>(capacity = 512)
    private var connectionJobs: MutableList<Job> = mutableListOf()
    private var discoveryJob: Job? = null

    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val found = linkedMapOf<String, DiscoveredHost>()
            _discoveredHosts.value = emptyList()
            runCatching {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 350
                    val queryBytes = ProtocolCodec.DISCOVER_QUERY.toByteArray(Charsets.UTF_8)
                    val targets = collectBroadcastAddresses()
                    repeat(3) {
                        for (addr in targets) {
                            runCatching {
                                sock.send(
                                    DatagramPacket(
                                        queryBytes,
                                        queryBytes.size,
                                        addr,
                                        ProtocolCodec.DEFAULT_DISCOVERY_PORT
                                    )
                                )
                            }
                        }
                        val deadline = System.currentTimeMillis() + 650L
                        val buf = ByteArray(512)
                        while (isActive && System.currentTimeMillis() < deadline) {
                            try {
                                val pkt = DatagramPacket(buf, buf.size)
                                sock.receive(pkt)
                                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                                val senderIp = pkt.address.hostAddress ?: continue
                                val host = ProtocolCodec.parseDiscoveryOffer(msg, senderIp)
                                if (host != null) {
                                    found[host.ip] = host
                                    _discoveredHosts.value = found.values.toList()
                                }
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
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
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ifAddr in nif.interfaceAddresses) {
                    ifAddr.broadcast?.let { result.add(it) }
                }
            }
        }
        return result.toList()
    }

    fun connect(
        ip: String,
        tcpPort: Int = ProtocolCodec.DEFAULT_TCP_PORT,
        udpPort: Int = ProtocolCodec.DEFAULT_UDP_PORT,
        displayName: String = ip
    ) {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) {
            _connectionState.value = ConnectionState.Error("Enter a valid PC IP address")
            return
        }
        disconnect()
        _connectionState.value = ConnectionState.Connecting(displayName)

        val job = scope.launch {
            try {
                val addr = InetAddress.getByName(cleanIp)
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(addr, tcpPort), 4000)

                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val dgram = DatagramSocket()

                tcpSocket = socket
                tcpWriter = writer
                udpSocket = dgram
                remoteAddress = addr
                remoteUdpPort = udpPort
                activeHostName = displayName.ifBlank { cleanIp }
                activeHostIp = cleanIp

                _connectionState.value = ConnectionState.Connected(
                    hostName = activeHostName,
                    detail = "$cleanIp:$tcpPort",
                    latencyMs = null,
                    mode = TransportMode.WIFI
                )

                // TCP writer loop
                val writerJob = launch {
                    for (line in tcpQueue) {
                        if (!isActive) break
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                    }
                }

                // UDP writer loop
                val udpJob = launch {
                    for (msg in udpQueue) {
                        if (!isActive) break
                        val bytes = msg.toByteArray(Charsets.UTF_8)
                        dgram.send(DatagramPacket(bytes, bytes.size, addr, udpPort))
                    }
                }

                // Heartbeat ping loop
                val pingJob = launch {
                    while (isActive && !socket.isClosed) {
                        tcpQueue.trySend(ProtocolCodec.tcpPingPacket(System.currentTimeMillis()))
                        delay(2000)
                    }
                }

                // TCP reader loop
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    handleServerLine(line)
                }

                writerJob.cancel()
                udpJob.cancel()
                pingJob.cancel()
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error(
                        e.message ?: "Unable to connect to $cleanIp"
                    )
                }
            } finally {
                closeSocketsQuietly()
            }
        }
        connectionJobs.add(job)
    }

    private fun handleServerLine(line: String) {
        if (line.contains("\"pong\"") && line.contains("\"ts\":")) {
            val tsStart = line.indexOf("\"ts\":") + 5
            val digits = line.substring(tsStart).takeWhile { it.isDigit() }
            val sentTs = digits.toLongOrNull() ?: return
            val rtt = (System.currentTimeMillis() - sentTs).coerceIn(1L, 999L).toInt()
            val current = _connectionState.value
            if (current is ConnectionState.Connected) {
                _connectionState.value = current.copy(latencyMs = rtt)
            }
        } else if (line.contains("\"hello\"") && line.contains("\"name\":\"")) {
            val start = line.indexOf("\"name\":\"") + 8
            val name = line.substring(start).takeWhile { it != '"' }
            if (name.isNotBlank()) {
                activeHostName = name
                val current = _connectionState.value
                if (current is ConnectionState.Connected) {
                    _connectionState.value = current.copy(hostName = name)
                }
            }
        }
    }

    fun disconnect() {
        connectionJobs.forEach { it.cancel() }
        connectionJobs.clear()
        closeSocketsQuietly()
        while (tcpQueue.tryReceive().isSuccess) Unit
        while (udpQueue.tryReceive().isSuccess) Unit
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun closeSocketsQuietly() {
        runCatching { tcpWriter?.close() }
        runCatching { tcpSocket?.close() }
        runCatching { udpSocket?.close() }
        tcpWriter = null
        tcpSocket = null
        udpSocket = null
    }

    fun sendMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        udpQueue.trySend(ProtocolCodec.udpMovePacket(dx, dy))
    }

    fun sendScroll(sx: Int, sy: Int) {
        if (sx == 0 && sy == 0) return
        udpQueue.trySend(ProtocolCodec.udpScrollPacket(sx, sy))
    }

    fun sendZoom(delta: Int) {
        if (delta == 0) return
        udpQueue.trySend(ProtocolCodec.udpZoomPacket(delta))
    }

    fun sendClick(button: MouseButton, count: Int = 1) {
        tcpQueue.trySend(ProtocolCodec.tcpClickPacket(button, count))
    }

    fun sendMouseButton(button: MouseButton, down: Boolean) {
        tcpQueue.trySend(ProtocolCodec.tcpMouseButtonPacket(button, down))
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        tcpQueue.trySend(ProtocolCodec.tcpTextPacket(text))
    }

    fun sendKey(key: SpecialKey, modifiers: ModifierState = ModifierState()) {
        tcpQueue.trySend(ProtocolCodec.tcpKeyPacket(key.wireName, modifiers))
    }

    fun sendCharKey(ch: Char, modifiers: ModifierState = ModifierState()) {
        tcpQueue.trySend(ProtocolCodec.tcpKeyPacket(ch.toString(), modifiers))
    }
}
