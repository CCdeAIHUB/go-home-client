package com.ccdeaihub.gohome

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Native WebSocket signalling client for Android.
 * All network I/O is handled by the native Kotlin shell;
 * the WebView/JS UI layer is display-only.
 */
object GoHomeSignalClient {
    private const val TAG = "GoHomeSignal"
    private const val RPC_TIMEOUT_S = 12L
    private const val HEARTBEAT_MS = 25_000L
    private const val RECONNECT_MS = 1_800L
    private const val UDP_REGISTER_MS = 15_000L

    // GHU1 magic bytes for UDP registration packets
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'H'.code.toByte(), 'U'.code.toByte(), '1'.code.toByte())
    private const val VERSION: Byte = 1
    private const val PACKET_REGISTER: Byte = 4

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val lock = Object()
    private var ws: WebSocket? = null
    private var server = ""
    private var authCode = ""
    private var deviceId = ""
    private var connected = false
    private var intentionalClose = false
    private var lastError = ""
    private var socketGeneration = 0

    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, (ok: Boolean, data: String) -> Unit>()
    private var heartbeatThread: Thread? = null
    private var udpRegisterThread: Thread? = null

    // ── Public API ──

    fun connect(server: String, authCode: String, deviceId: String): String {
        synchronized(lock) {
            this.intentionalClose = true
            closeInternal()
            this.server = server
            this.authCode = authCode
            this.deviceId = deviceId
            this.intentionalClose = false
            this.lastError = ""
            this.socketGeneration += 1
        }
        val gen = synchronized(lock) { socketGeneration }
        val latch = CountDownLatch(1)
        val out = arrayOf("")

        doConnect(gen, latch, out)

        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                synchronized(lock) { lastError = "connection timeout" }
                return """{"error":"connection timeout"}"""
            }
        } catch (_: InterruptedException) {
            return """{"error":"interrupted"}"""
        }
        return out[0]
    }

    fun disconnect(): String {
        synchronized(lock) {
            intentionalClose = true
            closeInternal()
        }
        return """{"ok":true}"""
    }

    fun rpc(action: String, paramsJSON: String): String {
        val id = "a-${seq.incrementAndGet()}"
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("action", action)
            .put("params", JSONObject(paramsJSON))

        val currentWs: WebSocket?
        synchronized(lock) { currentWs = ws }

        if (currentWs == null) return """{"error":"not connected"}"""

        val latch = CountDownLatch(1)
        val out = arrayOf("""{"error":"timeout"}""")

        pending[id] = { ok, data ->
            out[0] = if (ok) data else """{"error":${JSONObject.quote(data)}}"""
            latch.countDown()
        }

        if (!currentWs.send(envelope.toString())) {
            pending.remove(id)
            return """{"error":"send failed"}"""
        }

        try {
            latch.await(RPC_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            pending.remove(id)
        }
        return out[0]
    }

    fun getStatus(): String {
        val st: String
        synchronized(lock) {
            st = if (connected) "connected" else "idle"
        }
        val tunnel = GoHomeTunnelRuntime.status()
        return JSONObject()
            .put("websocket", st)
            .put("udp", tunnel.optString("udp", "idle"))
            .put("grace_seconds", 0)
            .put("last_error", synchronized(lock) { lastError })
            .toString()
    }

    // ── Internal ──

    private fun doConnect(gen: Int, latch: CountDownLatch, out: Array<String>) {
        val wsURL = buildWSURL(server)
        val request = Request.Builder().url(wsURL).build()
        val currentAuth = authCode
        val currentDev = deviceId
        val currentServer = server

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val authId = "auth-${seq.incrementAndGet()}"
                val ts = System.currentTimeMillis() / 1000
                val tk = computeTimeKey(currentAuth, ts)
                val authMsg = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", authId)
                    .put("action", "device.auth")
                    .put("params", JSONObject()
                        .put("device_id", currentDev)
                        .put("device_type", "client")
                        .put("auth_code", currentAuth)
                        .put("time_key", tk)
                        .put("timestamp", ts))

                // Register handler for auth response to get server_udp_port
                pending[authId] = { ok, data ->
                    if (ok) {
                        try {
                            val result = JSONObject(data)
                            val serverUdpPort = result.optInt("server_udp_port", 0)
                            val token = result.optString("token", "")
                            if (serverUdpPort > 0 && token.isNotEmpty()) {
                                startUDPRegistration(gen, currentServer, serverUdpPort, currentDev, token)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "parse auth result", e)
                        }
                    }
                    val shouldContinue = synchronized(lock) {
                        if (socketGeneration != gen) false else {
                            connected = true
                            lastError = ""
                            true
                        }
                    }
                    if (shouldContinue) {
                        startHeartbeat(gen)
                        out[0] = """{"ok":true}"""
                        latch.countDown()
                    }
                }

                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    lastError = t.message ?: "websocket error"
                    connected = false
                    stopHeartbeat()
                    failAllPending(lastError)
                }
                out[0] = """{"error":${JSONObject.quote(lastError)}}"""
                latch.countDown()
                if (!synchronized(lock) { intentionalClose }) {
                    scheduleReconnect(gen)
                }
            }
        }

        val newWs = httpClient.newWebSocket(request, listener)
        synchronized(lock) { ws = newWs }
    }

    private fun handleMessage(text: String) {
        try {
            val env = JSONObject(text)

            if (env.has("id")) {
                val id = env.getString("id")
                val handler = pending.remove(id) ?: return
                if (env.has("error")) {
                    val msg = env.optJSONObject("error")?.optString("message")
                        ?: env.optJSONObject("error")?.optString("code")
                        ?: "unknown error"
                    handler(false, msg)
                } else {
                    // Handle both JSON object and JSON array results
                    val resultStr = if (env.has("result")) {
                        val resultVal = env.get("result")
                        when (resultVal) {
                            is JSONObject -> resultVal.toString()
                            is JSONArray -> resultVal.toString()
                            else -> JSONObject().toString()
                        }
                    } else {
                        JSONObject().toString()
                    }
                    handler(true, resultStr)
                }
                return
            }

            val action = env.optString("action", "")
            if (action == "device.latency_probe") {
                val probeId = env.optJSONObject("params")?.optString("probe_id") ?: ""
                rpcInternal("stats.latency_pong", JSONObject().put("probe_id", probeId))
            } else if (action == "device.force_offline") {
                synchronized(lock) { intentionalClose = true }
                closeInternal()
                GoHomeTunnelRuntime.stop(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage error", e)
        }
    }

    private fun rpcInternal(action: String, params: JSONObject) {
        val id = "i-${seq.incrementAndGet()}"
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("action", action)
            .put("params", params)
        synchronized(lock) { ws?.send(envelope.toString()) }
    }

    private fun handleDisconnect(gen: Int) {
        synchronized(lock) {
            if (socketGeneration != gen) return
            connected = false
            stopHeartbeat()
            stopUDPRegistration()
            failAllPending("disconnected")
        }
        if (!synchronized(lock) { intentionalClose }) {
            scheduleReconnect(gen)
        }
    }

    private fun startHeartbeat(gen: Int) {
        stopHeartbeat()
        heartbeatThread = thread(name = "go-home-hb", isDaemon = true) {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    if (synchronized(lock) { socketGeneration != gen || !connected }) return@thread
                    val ts = System.currentTimeMillis() / 1000
                    val tk = computeTimeKey(authCode, ts)
                    rpcInternal("ping", JSONObject().put("time_key", tk).put("timestamp", ts))
                } catch (_: InterruptedException) { return@thread }
                catch (e: Exception) { Log.w(TAG, "heartbeat", e) }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    // ── UDP Registration (NAT Endpoint Discovery) ──

    private fun startUDPRegistration(gen: Int, serverURL: String, serverUDPPort: Int, deviceID: String, token: String) {
        stopUDPRegistration()
        udpRegisterThread = thread(name = "go-home-udp-reg", isDaemon = true) {
            try {
                // Parse server host from WebSocket URL
                val host = parseServerHost(serverURL) ?: return@thread
                val serverAddr = InetAddress.getByName(host)
                val registerPacket = buildRegisterPacket(deviceID, token) ?: return@thread

                // Send immediately
                sendUDPRegister(serverAddr, serverUDPPort, registerPacket)

                while (!Thread.interrupted()) {
                    Thread.sleep(UDP_REGISTER_MS)
                    if (synchronized(lock) { socketGeneration != gen || !connected }) return@thread
                    sendUDPRegister(serverAddr, serverUDPPort, registerPacket)
                }
            } catch (_: InterruptedException) { }
            catch (e: Exception) { Log.w(TAG, "UDP register error", e) }
        }
    }

    private fun stopUDPRegistration() {
        udpRegisterThread?.interrupt()
        udpRegisterThread = null
    }

    private fun sendUDPRegister(addr: InetAddress, port: Int, packet: ByteArray) {
        try {
            val socket = DatagramSocket()
            socket.send(DatagramPacket(packet, packet.size, addr, port))
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "send UDP register", e)
        }
    }

    private fun buildRegisterPacket(deviceID: String, token: String): ByteArray? {
        try {
            val json = JSONObject()
                .put("device_id", deviceID)
                .put("token", token)
                .toString()
                .toByteArray(Charsets.UTF_8)
            return MAGIC + byteArrayOf(VERSION, PACKET_REGISTER) + json
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseServerHost(serverURL: String): String? {
        try {
            var v = serverURL.trim()
            if (!v.contains("://")) v = "ws://$v"
            return URI(v).host
        } catch (e: Exception) {
            return null
        }
    }

    // ── Reconnection ──

    private fun scheduleReconnect(gen: Int) {
        thread(name = "go-home-reconn", isDaemon = true) {
            try {
                Thread.sleep(RECONNECT_MS)
                synchronized(lock) {
                    if (intentionalClose || socketGeneration != gen) return@thread
                }
                val latch = CountDownLatch(1)
                val out = arrayOf("")
                doConnect(gen, latch, out)
                latch.await(15, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
    }

    private fun closeInternal() {
        stopHeartbeat()
        stopUDPRegistration()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        connected = false
        failAllPending("closed")
    }

    private fun failAllPending(msg: String) {
        for ((_, h) in pending.entries) { h(false, msg) }
        pending.clear()
    }

    private fun buildWSURL(server: String): String {
        var v = server.trim()
        if (!v.contains("://")) v = "ws://$v"
        val uri = URI(v)
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"; "http" -> "ws"; else -> uri.scheme ?: "ws"
        }
        val path = uri.path.let { if (it.isNullOrEmpty() || it == "/") "/ws" else it }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://${uri.host}$port$path"
    }
}
