package com.ccdeaihub.gohome

import android.util.Log
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Native WebSocket signalling client for Android.
 * All server WebSocket communication is handled in native Kotlin,
 * not in the WebView/JS layer — consistent with the architecture principle:
 * "the native shell owns all network I/O; the UI layer is display-only".
 *
 * Uses java.net.http.WebSocket (available on Android API 26+).
 */
object GoHomeSignalClient {
    private const val TAG = "GoHomeSignal"
    private const val RPC_TIMEOUT_MS = 12_000L
    private const val HEARTBEAT_INTERVAL_MS = 25_000L
    private const val RECONNECT_DELAY_MS = 1_800L
    private const val GRACE_DURATION_MS = 30_000L

    private val lock = Object()
    private var ws: java.net.http.WebSocket? = null
    private var server = ""
    private var authCode = ""
    private var deviceId = ""
    private var connected = false
    private var intentionalClose = false
    private var lastError = ""
    private var latency = 0L
    private var graceUntil = 0L
    private var socketGeneration = 0

    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, (Result<JSONObject>) -> Unit>()
    private var heartbeatThread: Thread? = null
    private var reconnectThread: Thread? = null

    private var callback: SignalCallback? = null

    /** Callback interface for pushing events back to the UI layer (via GoHomeBridge). */
    interface SignalCallback {
        fun onConnected()
        fun onDisconnected(graceSeconds: Int, lastError: String)
        fun onPush(action: String, params: String)
    }

    fun setCallback(cb: SignalCallback?) {
        synchronized(lock) { callback = cb }
    }

    // ── Public API (called from GoHomeBridge @JavascriptInterface) ──────

    fun connect(server: String, authCode: String, deviceId: String): String {
        synchronized(lock) {
            this.intentionalClose = true
            stopInternal()
            this.server = server
            this.authCode = authCode
            this.deviceId = deviceId
            this.intentionalClose = false
            this.lastError = ""
            this.graceUntil = 0
            this.socketGeneration += 1
        }
        return try {
            openSignal()
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            synchronized(lock) { lastError = e.message ?: "connection failed" }
            JSONObject().put("error", e.message ?: "connection failed").toString()
        }
    }

    fun disconnect(): String {
        synchronized(lock) {
            intentionalClose = true
            stopInternal()
        }
        return JSONObject().put("ok", true).toString()
    }

    /**
     * Synchronous RPC call. Blocks until the server responds or timeout.
     * Returns the result JSONObject as string, or {"error":"..."} on failure.
     */
    fun rpc(action: String, paramsJSON: String): String {
        val id = "android-${seq.incrementAndGet()}"
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("action", action)
            .put("params", JSONObject(paramsJSON))

        val currentWs: java.net.http.WebSocket?
        synchronized(lock) { currentWs = ws }

        if (currentWs == null || currentWs.isClosed) {
            return JSONObject().put("error", "not connected").toString()
        }

        val result = arrayOf<Result<JSONObject>?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)

        pending[id] = { res ->
            result[0] = res
            latch.countDown()
        }

        try {
            currentWs.sendText(envelope.toString(), true)
        } catch (e: Exception) {
            pending.remove(id)
            return JSONObject().put("error", e.message ?: "send failed").toString()
        }

        return try {
            if (!latch.await(RPC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                pending.remove(id)
                JSONObject().put("error", "server response timeout").toString()
            } else {
                when (val res = result[0]) {
                    is Result.Success -> res.value.toString()
                    is Result.Failure -> JSONObject().put("error", res.exception.message ?: "rpc failed").toString()
                    else -> JSONObject().put("error", "unexpected result").toString()
                }
            }
        } catch (e: InterruptedException) {
            pending.remove(id)
            JSONObject().put("error", "rpc interrupted").toString()
        }
    }

    fun getStatus(): String {
        val graceSeconds = graceSecondsRemaining()
        val wsStatus: String
        synchronized(lock) {
            wsStatus = if (connected) "connected" else if (graceSeconds > 0) "grace" else "idle"
        }
        val tunnel = GoHomeTunnelRuntime.status()
        return JSONObject()
            .put("websocket", wsStatus)
            .put("udp", tunnel.optString("udp", "idle"))
            .put("grace_seconds", graceSeconds)
            .put("last_error", tunnel.optString("last_error", "") ?: synchronized(lock) { lastError })
            .toString()
    }

    fun isConnected(): Boolean {
        synchronized(lock) { return connected }
    }

    fun getLatency(): Long {
        synchronized(lock) { return latency }
    }

    // ── Internal ──────────────────────────────────────────

    private fun openSignal() {
        val wsURL = buildWSURL(server)
        val generation = synchronized(lock) { socketGeneration }

        val listener = object : java.net.http.WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: java.net.http.WebSocket) {
                webSocket.request(1)

                // Authenticate immediately
                val timestamp = System.currentTimeMillis() / 1000
                val timeKey = GoHomeBridge.staticTimeKey(authCode, timestamp)
                val authMsg = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", "auth-${seq.incrementAndGet()}")
                    .put("action", "device.auth")
                    .put("params", JSONObject()
                        .put("device_id", deviceId)
                        .put("device_type", "client")
                        .put("auth_code", authCode)
                        .put("time_key", timeKey)
                        .put("timestamp", timestamp))
                webSocket.sendText(authMsg.toString(), true)

                synchronized(lock) {
                    if (socketGeneration != generation) return
                    connected = true
                    graceUntil = 0
                    lastError = ""
                }
                startHeartbeat(generation)
                callback?.onConnected()
            }

            override fun onText(webSocket: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.net.http.WebSocket.Listener? {
                buffer.append(data)
                if (last) {
                    handleMessage(buffer.toString())
                    buffer.clear()
                }
                webSocket.request(1)
                return this
            }

            override fun onClose(webSocket: java.net.http.WebSocket, statusCode: Int, reason: String) {
                synchronized(lock) {
                    if (socketGeneration != generation) return
                    connected = false
                    stopHeartbeat()
                    rejectAllPending("server signal disconnected")
                }
                val err: String
                synchronized(lock) { err = lastError }
                callback?.onDisconnected(graceSecondsRemaining(), err)

                if (!synchronized(lock) { intentionalClose }) {
                    val tunnel = GoHomeTunnelRuntime.status()
                    if (tunnel.optString("udp") == "connected" && graceSecondsRemaining() > 0) {
                        scheduleReconnect(generation)
                    }
                }
            }

            override fun onError(webSocket: java.net.http.WebSocket, error: Throwable) {
                synchronized(lock) {
                    lastError = error.message ?: "websocket error"
                    connected = false
                    stopHeartbeat()
                    rejectAllPending(lastError)
                }
                val err: String
                synchronized(lock) { err = lastError }
                callback?.onDisconnected(0, err)

                if (!synchronized(lock) { intentionalClose }) {
                    scheduleReconnect(generation)
                }
            }
        }

        val httpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build()

        val newWs = httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(wsURL), listener)
            .get() // block until connected

        synchronized(lock) { ws = newWs }
    }

    private fun handleMessage(text: String) {
        try {
            val env = JSONObject(text)

            // RPC response
            if (env.has("id")) {
                val id = env.getString("id")
                val handler = pending.remove(id) ?: return
                if (env.has("error")) {
                    val errMsg = env.optJSONObject("error")?.optString("message")
                        ?: env.optJSONObject("error")?.optString("code")
                        ?: "unknown error"
                    handler(Result.failure(Exception(errMsg)))
                } else {
                    handler(Result.success(env.optJSONObject("result") ?: JSONObject()))
                }
                return
            }

            // Server push
            val action = env.optString("action", "")
            if (action == "device.latency_probe") {
                val probeId = env.optJSONObject("params")?.optString("probe_id") ?: ""
                rpcInternal("stats.latency_pong", JSONObject().put("probe_id", probeId))
            } else if (action == "device.force_offline") {
                synchronized(lock) { intentionalClose = true }
                stopInternal()
                GoHomeTunnelRuntime.stop(null)
            } else if (action.isNotEmpty()) {
                callback?.onPush(action, env.optJSONObject("params")?.toString() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle message", e)
        }
    }

    private fun rpcInternal(action: String, params: JSONObject) {
        val id = "android-internal-${seq.incrementAndGet()}"
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("action", action)
            .put("params", params)
        synchronized(lock) { ws?.sendText(envelope.toString(), true) }
    }

    private fun startHeartbeat(generation: Int) {
        stopHeartbeat()
        heartbeatThread = thread(name = "go-home-heartbeat", isDaemon = true) {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                    if (synchronized(lock) { socketGeneration != generation || !connected }) return@thread
                    val timestamp = System.currentTimeMillis() / 1000
                    val timeKey = GoHomeBridge.staticTimeKey(authCode, timestamp)
                    rpcInternal("ping", JSONObject()
                        .put("time_key", timeKey)
                        .put("timestamp", timestamp))
                } catch (_: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    private fun scheduleReconnect(generation: Int) {
        reconnectThread?.interrupt()
        reconnectThread = thread(name = "go-home-reconnect", isDaemon = true) {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
                synchronized(lock) {
                    if (intentionalClose || socketGeneration != generation) return@thread
                }
                if (graceSecondsRemaining() == 0) {
                    synchronized(lock) { lastError = "WebSocket grace period expired" }
                    GoHomeTunnelRuntime.stop(null)
                    return@thread
                }
                openSignal()
            } catch (_: InterruptedException) { }
        }
    }

    private fun stopInternal() {
        stopHeartbeat()
        reconnectThread?.interrupt()
        reconnectThread = null
        try { ws?.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "client disconnect") } catch (_: Exception) {}
        ws = null
        connected = false
        rejectAllPending("signal closed")
    }

    private fun rejectAllPending(message: String) {
        for ((_, handler) in pending.entries) {
            handler(Result.failure(Exception(message)))
        }
        pending.clear()
    }

    private fun graceSecondsRemaining(): Int {
        val until = synchronized(lock) { graceUntil }
        if (until == 0L) return 0
        return ((until - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
    }

    private fun buildWSURL(server: String): String {
        var value = server.trim()
        if (!value.contains("://")) value = "ws://$value"
        val uri = URI(value)
        val scheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            else -> uri.scheme ?: "ws"
        }
        val path = uri.path.let { if (it.isNullOrEmpty() || it == "/") "/ws" else it }
        val port = uri.port.let { if (it == -1) "" else ":$it" }
        return "$scheme://${uri.host}$port$path"
    }

    private sealed class Result<out T> {
        data class Success<out T>(val value: T) : Result<T>()
        data class Failure(val exception: Exception) : Result<Nothing>()
    }
}
