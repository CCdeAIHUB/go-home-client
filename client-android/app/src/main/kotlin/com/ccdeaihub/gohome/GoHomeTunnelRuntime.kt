package com.ccdeaihub.gohome

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.math.min

object GoHomeTunnelRuntime {
    private const val VERSION: Byte = 1
    private const val PACKET_PROBE: Byte = 1
    private const val PACKET_HELLO: Byte = 2
    private const val PACKET_FRAME: Byte = 3
    private const val FRAME_READY: Byte = 1
    private const val FRAME_KEEPALIVE: Byte = 2
    private const val FRAME_PING: Byte = 3
    private const val FRAME_PONG: Byte = 4
    private const val FRAME_IPV4: Byte = 5
    private const val GCM_NONCE_SIZE = 12
    private const val PORT_PREDICTION_WINDOW = 16
    private const val AGGRESSIVE_PORT_PREDICTION_WINDOW = 512
    private const val MAX_PUNCH_TARGETS_PER_ATTEMPT = 192
    private const val PUNCH_TIMEOUT_MS = 25_000L
    private const val PUNCH_SOCKET_COUNT = 8
    private val magic = byteArrayOf('G'.code.toByte(), 'H'.code.toByte(), 'U'.code.toByte(), '1'.code.toByte())
    private val random = SecureRandom()
    private val lock = Object()

    private var socket: DatagramSocket? = null
    private var punchSockets: MutableList<DatagramSocket> = mutableListOf()
    private var sessionID = ""
    private var sessionKey: ByteArray? = null
    private var peer: InetSocketAddress? = null
    private var sendSequence = 0L
    private var replay = ReplayWindow()
    private var running = false
    private var udpConnected = false
    private var lastError = ""
    private var uploaded = 0L
    private var downloaded = 0L
    private var tunnelRTTMS = 0L
    private var tunnelFd: ParcelFileDescriptor? = null
    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null

    fun prepare(deviceID: String): JSONObject {
        stop(null)
        val preparedSockets = mutableListOf<DatagramSocket>()
        try {
            repeat(PUNCH_SOCKET_COUNT) {
                preparedSockets.add(DatagramSocket(0).apply { soTimeout = 120 })
            }
        } catch (error: Exception) {
            preparedSockets.forEach { it.close() }
            throw error
        }
        val prepared = preparedSockets.first()
        synchronized(lock) {
            socket = prepared
            punchSockets = preparedSockets
            lastError = ""
            uploaded = 0
            downloaded = 0
            tunnelRTTMS = 0
        }
        android.util.Log.i("GoHomeTunnel", "Prepared UDP sockets localPorts=${preparedSockets.map { it.localPort }}")
        return JSONObject()
            .put("udp_port", prepared.localPort)
            .put("client_virtual_mac", virtualMAC(deviceID))
    }

    fun connect(activity: Activity, rawOffer: String, mode: String, virtualCIDR: String): JSONObject {
        val offer = JSONObject(rawOffer)
        val currentSockets = synchronized(lock) { punchSockets.toList() }
        if (currentSockets.isEmpty()) throw IllegalStateException("UDP socket is not prepared")
        val currentSessionID = offer.getString("session_id")
        val clientID = offer.getJSONObject("client").getString("device_id")
        val server = offer.getJSONObject("server")
        val currentKey = ByteArray(16).also(random::nextBytes)
        val encryptedKey = encryptSessionKey(server.getString("public_key"), currentKey)
        val hello = controlPacket(
            PACKET_HELLO,
            JSONObject()
                .put("session_id", currentSessionID)
                .put("client_device_id", clientID)
                .put("encrypted_session_key", Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
        )
        val candidates = peerBaseCandidates(server)
        if (candidates.isEmpty()) throw IllegalStateException("home server has no usable IPv4 UDP candidate")
        var currentPeer = candidates[0]
        android.util.Log.i("GoHomeTunnel", "UDP punch base candidates for session $currentSessionID: $candidates")
        val deadline = System.currentTimeMillis() + PUNCH_TIMEOUT_MS
        var attempt = 0
        var probesSeen = 0
        var framesSeen = 0
        var lastWindow = -1

        synchronized(lock) {
            sessionID = currentSessionID
            sessionKey = currentKey
            peer = currentPeer
            sendSequence = 0
            replay = ReplayWindow()
            udpConnected = false
            lastError = ""
        }

        while (System.currentTimeMillis() < deadline) {
            val snapshot = punchCandidateBatch(candidates, attempt)
            val window = punchPredictionWindow(attempt)
            if (window != lastWindow) {
                val total = expandCandidates(candidates, window).size
                android.util.Log.i("GoHomeTunnel", "UDP punch stage for session $currentSessionID attempt=$attempt window=+/-$window total=$total batch=${snapshot.size} sockets=${currentSockets.size}")
                lastWindow = window
            }
            // Send Hello packets from every punch socket to the current batch.
            for (sourceSocket in currentSockets) {
                for (candidate in snapshot) {
                    send(sourceSocket, candidate, hello)
                }
            }
            val untilNextHello = min(punchInterval(attempt), (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))
            val packet = receiveAny(currentSockets, untilNextHello)
            if (packet != null) {
                try {
                    when (packetKind(packet.bytes)) {
                        PACKET_PROBE -> {
                            if (controlJSON(packet.bytes).optString("session_id") == currentSessionID) {
                                probesSeen += 1
                                android.util.Log.i("GoHomeTunnel", "Received UDP probe for session $currentSessionID from ${packet.source}")
                                currentPeer = packet.source
                                addCandidate(candidates, currentPeer)
                                synchronized(lock) { peer = currentPeer }
                                send(packet.socket, currentPeer, hello)
                            }
                        }
                        PACKET_FRAME -> {
                            val frame = openFrame(currentKey, packet.bytes)
                            if (frame.sessionID == currentSessionID && frame.type == FRAME_READY && replay.accept(frame.sequence)) {
                                framesSeen += 1
                                android.util.Log.i("GoHomeTunnel", "Received UDP ready for session $currentSessionID from ${packet.source}")
                                synchronized(lock) {
                                    socket = packet.socket
                                    punchSockets.filter { it !== packet.socket }.forEach { it.close() }
                                    punchSockets = mutableListOf(packet.socket)
                                    peer = packet.source
                                    udpConnected = true
                                    running = true
                                }
                                val ready = JSONObject(frame.payload.toString(Charsets.UTF_8))
                                startVpn(activity, ready, mode, virtualCIDR)
                                startUDPLoop(packet.socket, currentKey, currentSessionID)
                                startKeepaliveLoop()
                                return readyToView(ready, mode, virtualCIDR)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("GoHomeTunnel", "ignore UDP packet during punch: ${e.message}")
                }
            }
            attempt += 1
        }
        synchronized(lock) {
            lastError = "UDP direct tunnel handshake timed out (attempts=$attempt probes=$probesSeen frames=$framesSeen sent=$uploaded received=$downloaded maxWindow=$AGGRESSIVE_PORT_PREDICTION_WINDOW)"
        }
        android.util.Log.w("GoHomeTunnel", lastError)
        throw IllegalStateException(lastError)
    }

    fun attachTunnel(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        synchronized(lock) {
            tunnelFd?.close()
            tunInput?.close()
            tunOutput?.close()
            tunnelFd = fd
            tunInput = FileInputStream(fd.fileDescriptor)
            tunOutput = FileOutputStream(fd.fileDescriptor)
        }
        startTunReadLoop()
    }

    fun protectSocket(service: VpnService) {
        synchronized(lock) {
            punchSockets.forEach {
                val ok = service.protect(it)
                android.util.Log.i("GoHomeTunnel", "Protect UDP socket localPort=${it.localPort} result=$ok")
            }
        }
    }

    fun sendRegister(serverHost: String, serverUDPPort: Int, packet: ByteArray): Boolean {
        if (serverUDPPort <= 0) return false
        val currentSockets = synchronized(lock) { punchSockets.toList() }
        if (currentSockets.isEmpty()) return false
        val address = InetAddress.getByName(serverHost)
        if (address !is Inet4Address) {
            throw IllegalArgumentException("server UDP endpoint must be IPv4")
        }
        currentSockets.forEach {
            android.util.Log.i("GoHomeTunnel", "Send UDP register to $serverHost:$serverUDPPort from localPort=${it.localPort}")
            send(it, InetSocketAddress(address, serverUDPPort), packet)
        }
        return true
    }

    fun status(): JSONObject {
        synchronized(lock) {
            return JSONObject()
                .put("udp", if (udpConnected) "connected" else "idle")
                .put("tunnel_rtt_ms", tunnelRTTMS)
                .put("last_error", lastError)
        }
    }

    fun stats(): JSONObject {
        synchronized(lock) {
            return JSONObject()
                .put("up", uploaded)
                .put("down", downloaded)
                .put("loss", 0)
                .put("tunnel_rtt_ms", tunnelRTTMS)
        }
    }

    fun stop(activity: Activity?) {
        synchronized(lock) {
            running = false
            udpConnected = false
            sessionID = ""
            sessionKey = null
            peer = null
            sendSequence = 0
            replay = ReplayWindow()
            tunnelRTTMS = 0
            punchSockets.forEach { it.close() }
            punchSockets = mutableListOf()
            socket?.close()
            socket = null
            tunInput?.close()
            tunInput = null
            tunOutput?.close()
            tunOutput = null
            tunnelFd?.close()
            tunnelFd = null
        }
        activity?.stopService(Intent(activity, GoHomeVpnService::class.java))
    }

    fun localNetworkConflict(cidr: String): Boolean {
        val target = IPv4Prefix.parse(cidr) ?: return false
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        while (interfaces.hasMoreElements()) {
            val item = interfaces.nextElement()
            if (!item.isUp || item.isLoopback) continue
            for (address in item.interfaceAddresses) {
                val ipv4 = address.address as? Inet4Address ?: continue
                val prefix = IPv4Prefix.fromAddress(ipv4.address, address.networkPrefixLength.toInt()) ?: continue
                if (target.overlaps(prefix)) return true
            }
        }
        return false
    }

    private fun startVpn(activity: Activity, ready: JSONObject, mode: String, virtualCIDR: String) {
        val homeIP = ready.optString("client_home_ip")
        val realCIDR = ready.optString("lan_cidr")
        if (homeIP.isBlank() || realCIDR.isBlank()) {
            throw IllegalStateException("home server did not lease a client IPv4 address")
        }
        val routeCIDR = if (mode == "mapped") virtualCIDR else realCIDR
        val clientAddress = if (mode == "mapped") mappedAddress(homeIP, realCIDR, virtualCIDR) else homeIP
        val intent = Intent(activity, GoHomeVpnService::class.java)
            .putExtra(GoHomeVpnService.EXTRA_HOME_CIDR, routeCIDR)
            .putExtra(GoHomeVpnService.EXTRA_VIRTUAL_ADDRESS, clientAddress)
        // Must start the VPN service on the UI thread for Android compatibility
        val latch = java.util.concurrent.CountDownLatch(1)
        activity.runOnUiThread {
            try {
                activity.startService(intent)
            } catch (e: Exception) {
                setError("Failed to start VPN service: ${e.message}")
            }
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        // Give the VPN service a moment to establish the interface
        Thread.sleep(300)
    }

    private fun readyToView(ready: JSONObject, mode: String, virtualCIDR: String): JSONObject {
        val realCIDR = ready.optString("lan_cidr")
        val homeIP = ready.optString("client_home_ip")
        val clientAddress = if (mode == "mapped") mappedAddress(homeIP, realCIDR, virtualCIDR) else homeIP
        return JSONObject()
            .put("mode", mode)
            .put("family_lan_cidr", realCIDR)
            .put("virtual_cidr", virtualCIDR)
            .put("client_home_ip", homeIP)
            .put("client_virtual_ip", clientAddress)
            .put("devices", ready.optJSONArray("devices") ?: JSONArray())
    }

    private fun startUDPLoop(currentSocket: DatagramSocket, currentKey: ByteArray, currentSessionID: String) {
        thread(name = "go-home-android-udp", isDaemon = true) {
            currentSocket.soTimeout = 1_000
            while (isRunning(currentSocket, currentSessionID)) {
                val packet = receive(currentSocket, 1_000) ?: continue
                try {
                    if (packetKind(packet.bytes) != PACKET_FRAME) continue
                    val frame = openFrame(currentKey, packet.bytes)
                    if (frame.sessionID != currentSessionID || !acceptSequence(frame.sequence)) continue
                    synchronized(lock) {
                        peer = packet.source
                        downloaded += packet.bytes.size
                    }
                    when (frame.type) {
                        FRAME_READY -> Unit
                        FRAME_PONG -> recordTunnelPong(frame.payload)
                        FRAME_IPV4 -> writeTunPacket(frame.payload)
                    }
                } catch (error: Exception) {
                    setError(error.message ?: "UDP packet rejected")
                }
            }
        }
    }

    private fun startKeepaliveLoop() {
        thread(name = "go-home-android-keepalive", isDaemon = true) {
            while (synchronized(lock) { running }) {
                try {
                    sendTunnelPing()
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (error: Exception) {
                    setError(error.message ?: "keepalive failed")
                }
            }
        }
    }

    private fun sendTunnelPing() {
        val payload = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(System.currentTimeMillis())
            .array()
        sendFrame(FRAME_PING, payload)
    }

    private fun recordTunnelPong(payload: ByteArray) {
        if (payload.size != Long.SIZE_BYTES) return
        val sentAt = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
        val rtt = System.currentTimeMillis() - sentAt
        if (rtt !in 0..60_000) return
        synchronized(lock) { tunnelRTTMS = rtt }
    }

    private fun startTunReadLoop() {
        thread(name = "go-home-android-vpn-read", isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            while (synchronized(lock) { running }) {
                val input = synchronized(lock) { tunInput } ?: return@thread
                val count = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    return@thread
                }
                if (count <= 0) continue
                try {
                    sendFrame(FRAME_IPV4, buffer.copyOf(count))
                } catch (error: Exception) {
                    setError(error.message ?: "VPN packet send failed")
                }
            }
        }
    }

    private fun sendFrame(type: Byte, payload: ByteArray) {
        val currentSocket: DatagramSocket
        val currentKey: ByteArray
        val currentSession: String
        val currentPeer: InetSocketAddress
        val sequence: Long
        synchronized(lock) {
            currentSocket = socket ?: throw IllegalStateException("UDP socket is closed")
            currentKey = sessionKey ?: throw IllegalStateException("session key is missing")
            currentSession = sessionID
            currentPeer = peer ?: throw IllegalStateException("home server peer is missing")
            sendSequence += 1
            sequence = sendSequence
        }
        val packet = sealFrame(currentKey, currentSession, sequence, type, payload)
        send(currentSocket, currentPeer, packet)
    }

    private fun writeTunPacket(packet: ByteArray) {
        val output = synchronized(lock) { tunOutput } ?: return
        output.write(packet)
        output.flush()
    }

    private fun send(targetSocket: DatagramSocket, target: InetSocketAddress, payload: ByteArray) {
        targetSocket.send(DatagramPacket(payload, payload.size, target))
        synchronized(lock) { uploaded += payload.size }
    }

    private fun receive(targetSocket: DatagramSocket, timeoutMillis: Int): ReceivedPacket? {
        targetSocket.soTimeout = timeoutMillis
        val buffer = ByteArray(64 * 1024)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            targetSocket.receive(packet)
            val source = InetSocketAddress(packet.address, packet.port)
            ReceivedPacket(buffer.copyOf(packet.length), source, targetSocket)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun receiveAny(sources: List<DatagramSocket>, timeoutMillis: Int): ReceivedPacket? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            for (source in sources) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) return null
                val packet = receive(source, min(remaining, 4))
                if (packet != null) return packet
            }
        }
        return null
    }

    private fun punchInterval(attempt: Int): Int {
        return when {
            attempt < 24 -> 35
            attempt < 64 -> 100
            attempt < 100 -> 250
            else -> 500
        }
    }

    private fun packetKind(packet: ByteArray): Byte {
        if (packet.size < magic.size + 2 || !packet.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw IllegalArgumentException("UDP packet magic is invalid")
        }
        if (packet[magic.size] != VERSION) {
            throw IllegalArgumentException("UDP packet version is unsupported")
        }
        return packet[magic.size + 1]
    }

    private fun controlPacket(kind: Byte, payload: JSONObject): ByteArray {
        return magic + byteArrayOf(VERSION, kind) + payload.toString().toByteArray(Charsets.UTF_8)
    }

    private fun controlJSON(packet: ByteArray): JSONObject {
        packetKind(packet)
        return JSONObject(packet.copyOfRange(magic.size + 2, packet.size).toString(Charsets.UTF_8))
    }

    private fun sealFrame(key: ByteArray, currentSessionID: String, sequence: Long, type: Byte, payload: ByteArray): ByteArray {
        require(currentSessionID.isNotBlank() && currentSessionID.toByteArray(Charsets.UTF_8).size <= 255)
        val sessionBytes = currentSessionID.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(magic.size + 2 + 1 + sessionBytes.size + Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION)
            .put(PACKET_FRAME)
            .put(sessionBytes.size.toByte())
            .put(sessionBytes)
            .putLong(sequence)
            .array()
        val nonce = ByteArray(GCM_NONCE_SIZE).also(random::nextBytes)
        val plaintext = byteArrayOf(type) + payload
        val ciphertext = crypt(true, key, nonce, header, plaintext)
        return header + nonce + ciphertext
    }

    private fun openFrame(key: ByteArray, packet: ByteArray): Frame {
        if (packetKind(packet) != PACKET_FRAME || packet.size < magic.size + 2 + 1 + Long.SIZE_BYTES + GCM_NONCE_SIZE) {
            throw IllegalArgumentException("secure frame header is incomplete")
        }
        val sessionLength = packet[magic.size + 2].toInt() and 0xff
        val headerLength = magic.size + 2 + 1 + sessionLength + Long.SIZE_BYTES
        if (packet.size <= headerLength + GCM_NONCE_SIZE) throw IllegalArgumentException("secure frame payload is incomplete")
        val header = packet.copyOfRange(0, headerLength)
        val session = packet.copyOfRange(magic.size + 3, magic.size + 3 + sessionLength).toString(Charsets.UTF_8)
        val sequence = ByteBuffer.wrap(header, headerLength - Long.SIZE_BYTES, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long
        val nonce = packet.copyOfRange(headerLength, headerLength + GCM_NONCE_SIZE)
        val ciphertext = packet.copyOfRange(headerLength + GCM_NONCE_SIZE, packet.size)
        val plaintext = crypt(false, key, nonce, header, ciphertext)
        if (plaintext.isEmpty()) throw IllegalArgumentException("secure frame body is empty")
        return Frame(session, sequence, plaintext[0], plaintext.copyOfRange(1, plaintext.size))
    }

    private fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(SM4Engine())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var count = cipher.processBytes(input, 0, input.size, output, 0)
        count += cipher.doFinal(output, count)
        return output.copyOf(count)
    }

    private fun encryptSessionKey(publicPEM: String, sessionKey: ByteArray): ByteArray {
        val der = publicPEM
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString(separator = "")
            .let { Base64.decode(it, Base64.DEFAULT) }
        val publicKey = PublicKeyFactory.createKey(der)
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(true, ParametersWithRandom(publicKey, random))
        val cipher = engine.processBlock(sessionKey, 0, sessionKey.size)
        if (cipher.size < 97 || cipher[0] != 0x04.toByte()) {
            throw IllegalStateException("SM2 ciphertext is invalid")
        }
        val vector = ASN1EncodableVector()
        vector.add(ASN1Integer(BigInteger(1, cipher.copyOfRange(1, 33))))
        vector.add(ASN1Integer(BigInteger(1, cipher.copyOfRange(33, 65))))
        vector.add(DEROctetString(cipher.copyOfRange(65, 97)))
        vector.add(DEROctetString(cipher.copyOfRange(97, cipher.size)))
        return DERSequence(vector).encoded
    }

    private fun mappedAddress(homeIP: String, realCIDR: String, virtualCIDR: String): String {
        val real = IPv4Prefix.parse(realCIDR) ?: throw IllegalArgumentException("real family CIDR is invalid")
        val virtual = IPv4Prefix.parse(virtualCIDR) ?: throw IllegalArgumentException("virtual CIDR is invalid")
        val home = InetAddress.getByName(homeIP) as? Inet4Address ?: throw IllegalArgumentException("client home IP is invalid")
        if (real.prefix != 24 || virtual.prefix != 24 || !real.contains(home.address)) {
            throw IllegalArgumentException("mapped mode requires matching IPv4 /24 CIDRs")
        }
        val bytes = virtual.network.copyOf()
        bytes[3] = home.address[3]
        return InetAddress.getByAddress(bytes).hostAddress ?: throw IllegalArgumentException("virtual client IP is invalid")
    }

    private fun peerBaseCandidates(peer: JSONObject): MutableList<InetSocketAddress> {
        val out = mutableListOf<InetSocketAddress>()
        val seen = mutableSetOf<String>()
        fun add(endpoint: String) {
            if (endpoint.isBlank()) return
            try {
                val parsed = parseEndpoint(endpoint)
                if (seen.add(candidateKey(parsed))) out.add(parsed)
            } catch (_: Exception) {}
        }

        val serverList = peer.optJSONArray("candidates")
        if (serverList != null) {
            for (index in 0 until serverList.length()) {
                add(serverList.optString(index, ""))
            }
        }
        val observedEndpoint = peer.optString("observed_endpoint", "")
        val reportedEndpoint = peer.optString("endpoint", "")
        val remoteAddr = peer.optString("remote_addr", "")
        add(observedEndpoint)
        add(reportedEndpoint)

        val udpPort = peer.optInt("udp_port", 0)
        if (udpPort > 0) {
            listOf(observedEndpoint, reportedEndpoint, remoteAddr).forEach { endpoint ->
                val host = endpointHost(endpoint)
                if (host.isNotBlank()) add("$host:$udpPort")
            }
        }
        return out
    }

    private fun punchCandidateBatch(base: List<InetSocketAddress>, attempt: Int): List<InetSocketAddress> {
        val candidates = expandCandidates(base, punchPredictionWindow(attempt))
        if (candidates.size <= MAX_PUNCH_TARGETS_PER_ATTEMPT) return candidates
        val baseCount = min(base.size, MAX_PUNCH_TARGETS_PER_ATTEMPT)
        val out = candidates.take(baseCount).toMutableList()
        val room = MAX_PUNCH_TARGETS_PER_ATTEMPT - out.size
        if (room <= 0) return out
        val rotating = candidates.drop(baseCount)
        if (rotating.isEmpty()) return out
        val offset = (attempt * room) % rotating.size
        repeat(room) { index ->
            out.add(rotating[(offset + index) % rotating.size])
        }
        return out
    }

    private fun punchPredictionWindow(attempt: Int): Int {
        return when {
            attempt < 12 -> PORT_PREDICTION_WINDOW
            attempt < 32 -> 64
            attempt < 60 -> 256
            else -> AGGRESSIVE_PORT_PREDICTION_WINDOW
        }
    }

    private fun expandCandidates(base: List<InetSocketAddress>, window: Int): List<InetSocketAddress> {
        val out = mutableListOf<InetSocketAddress>()
        val seen = mutableSetOf<String>()
        fun add(candidate: InetSocketAddress) {
            val address = candidate.address as? Inet4Address ?: return
            if (candidate.port !in 1..65535) return
            val normalized = InetSocketAddress(address, candidate.port)
            if (seen.add(candidateKey(normalized))) out.add(normalized)
        }
        base.forEach(::add)
        out.toList().forEach { endpoint ->
            for (delta in 1..window) {
                if (endpoint.port + delta <= 65535) {
                    add(InetSocketAddress(endpoint.address, endpoint.port + delta))
                }
                if (endpoint.port - delta >= 1) {
                    add(InetSocketAddress(endpoint.address, endpoint.port - delta))
                }
            }
        }
        return out
    }

    private fun addCandidate(candidates: MutableList<InetSocketAddress>, candidate: InetSocketAddress) {
        val key = candidateKey(candidate)
        if (candidates.none { candidateKey(it) == key }) {
            candidates.add(candidate)
        }
    }

    private fun candidateKey(candidate: InetSocketAddress): String {
        return "${candidate.address.hostAddress}:${candidate.port}"
    }

    private fun endpointHost(endpoint: String): String {
        val delimiter = endpoint.lastIndexOf(':')
        if (delimiter <= 0) return ""
        val host = endpoint.substring(0, delimiter).trim('[', ']')
        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        return if (address is Inet4Address) address.hostAddress ?: "" else ""
    }

    private fun parseEndpoint(endpoint: String): InetSocketAddress {
        val delimiter = endpoint.lastIndexOf(':')
        if (delimiter <= 0) throw IllegalArgumentException("endpoint is invalid")
        val port = endpoint.substring(delimiter + 1).toInt()
        if (port !in 1..65535) throw IllegalArgumentException("endpoint port is invalid")
        val host = endpoint.substring(0, delimiter).trim('[', ']')
        val address = InetAddress.getByName(host)
        if (address !is Inet4Address) throw IllegalArgumentException("endpoint must be IPv4")
        return InetSocketAddress(address, port)
    }

    private fun virtualMAC(deviceID: String): String {
        val bytes = byteArrayOf(0x02, 0x47, 0x48, 0, 0, 0)
        deviceID.toByteArray(Charsets.UTF_8).forEachIndexed { index, value ->
            bytes[3 + index % 3] = (bytes[3 + index % 3].toInt() xor value.toInt()).toByte()
        }
        return bytes.joinToString(separator = ":") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun acceptSequence(sequence: Long): Boolean {
        synchronized(lock) { return replay.accept(sequence) }
    }

    private fun isRunning(targetSocket: DatagramSocket, currentSessionID: String): Boolean {
        synchronized(lock) {
            return running && socket === targetSocket && sessionID == currentSessionID
        }
    }

    internal fun setError(message: String) {
        synchronized(lock) { lastError = message }
    }

    private data class ReceivedPacket(val bytes: ByteArray, val source: InetSocketAddress, val socket: DatagramSocket)
    private data class Frame(val sessionID: String, val sequence: Long, val type: Byte, val payload: ByteArray)

    private class ReplayWindow {
        private var max = 0L
        private var seen = 0UL

        fun accept(sequence: Long): Boolean {
            if (sequence <= 0) return false
            if (sequence > max) {
                val shift = sequence - max
                seen = if (shift >= 64) 1UL else (seen shl shift.toInt()) or 1UL
                max = sequence
                return true
            }
            val delta = max - sequence
            if (delta >= 64) return false
            val mask = 1UL shl delta.toInt()
            if ((seen and mask) != 0UL) return false
            seen = seen or mask
            return true
        }
    }

    private data class IPv4Prefix private constructor(val network: ByteArray, val prefix: Int) {
        init {
            require(network.size == 4 && prefix in 0..32)
        }

        fun contains(address: ByteArray): Boolean {
            if (address.size != 4) return false
            return masked(address, prefix).contentEquals(network)
        }

        fun overlaps(other: IPv4Prefix): Boolean {
            val sharedPrefix = min(prefix, other.prefix)
            return masked(network, sharedPrefix).contentEquals(masked(other.network, sharedPrefix))
        }

        companion object {
            fun fromAddress(address: ByteArray, prefix: Int): IPv4Prefix? {
                if (address.size != 4 || prefix !in 0..32) return null
                return IPv4Prefix(masked(address, prefix), prefix)
            }

            fun parse(cidr: String): IPv4Prefix? {
                val parts = cidr.split("/", limit = 2)
                val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val address = runCatching { InetAddress.getByName(parts[0]) as? Inet4Address }.getOrNull() ?: return null
                return fromAddress(address.address, prefix)
            }

            private fun masked(address: ByteArray, prefix: Int): ByteArray {
                val out = address.copyOf()
                var bits = prefix
                for (index in out.indices) {
                    val keep = min(bits, 8)
                    val mask = if (keep == 0) 0 else (0xff shl (8 - keep)) and 0xff
                    out[index] = (out[index].toInt() and mask).toByte()
                    bits = (bits - keep).coerceAtLeast(0)
                }
                return out
            }
        }
    }
}
