package com.ccdeaihub.gohome

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Native WebSocket signalling client for Android.
 * Uses OkHttp WebSocket — the standard for Android WebSocket communication.
 *
 * Architecture: all network I/O is handled by the native Kotlin shell;
 * the WebView/JS UI layer is display-only and communicates via GoHomeBridge.
 */
object GoHomeSignalClient {
    private const val TAG = "GoHomeSignal"
    private const val RPC_TIMEOUT_MS = 12_000L
    private const val HEARTBEAT_INTERVAL_MS = 25_000L
    private const val RECONNECT_DELAY_MS = 1_800L
    private const val GRACE_DURATION_MS = 30_000L

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
    private var latency = 0L
    private var graceUntil = 0L
    private var socketGeneration = 0

    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, (Result<JSONObject>) -> Unit>()
    private var heartbeatThread: Thread? = null
    private var reconnectThread: Thread? = null

    private var callback: SignalCallback? = null

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
        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<String?>("")
        val generation = synchronized(lock) { socketGeneration }

        val wsURL = buildWSURL(server)
        val request = Request.Builder().url(wsURL).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Authenticate immediately
                val timestamp = System.currentTimeMillis() / 1000
                val timeKey = computeTimeKey(authCode, timestamp)
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
                webSocket.send(authMsg.toString())

                synchronized(lock) {
                    if (socketGeneration != generation) return
                    connected = true
                    graceUntil = 0
                    lastError = ""
                }
                startHeartbeat(generation)
                resultHolder[0] = JSONObject().put("ok", true).toString()
                latch.countDown()
                callback?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClose(generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    lastError = t.message ?: "websocket error"
                    connected = false
                    stopHeartbeat()
                    rejectAllPending(lastError)
                }
                resultHolder[0] = JSONObject().put("error", lastError).toString()
                latch.countDown()

                val err: String
                synchronized(lock) { err = lastError }
                callback?.onDisconnected(0, err)

                if (!synchronized(lock) { intentionalClose }) {
                    scheduleReconnect(generation)
                }
            }
        }

        val newWs = httpClient.newWebSocket(request, listener)
        synchronized(lock) { ws = newWs }

        return try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                synchronized(lock) { lastError = "connection timeout" }
                JSONObject().put("error", "connection timeout").toString()
            } else {
                resultHolder[0] ?: JSONObject().put("error", "unknown error").toString()
            }
        } catch (e: InterruptedException) {
            JSONObject().put("error", "interrupted").toString()
        }
    }

    fun disconnect(): String {
        synchronized(lock) {
            intentionalClose = true
            stopInternal()
        }
        return JSONObject().put("ok", true).toString()
    }

    fun rpc(action: String, paramsJSON: String): String {
        val id = "android-${seq.incrementAndGet()}"
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("action", action)
            .put("params", JSONObject(paramsJSON))

        val currentWs: WebSocket?
        synchronized(lock) { currentWs = ws }

        if (currentWs == null) {
            return JSONObject().put("error", "not connected").toString()
        }

        val result = arrayOf<Result<JSONObject>?>(null)
        val latch = CountDownLatch(1)

        pending[id] = { res ->
            result[0] = res
            latch.countDown()
        }

        if (!currentWs.send(envelope.toString())) {
            pending.remove(id)
            return JSONObject().put("error", "send failed").toString()
        }

        return try {
            if (!latch.await(RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
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
            .put("last_error", synchronized(lock) { lastError })
            .toString()
    }

    fun isConnected(): Boolean {
        synchronized(lock) { return connected }
    }

    fun getLatency(): Long {
        synchronized(lock) { return latency }
    }

    // ── Internal ──────────────────────────────────────────

    private fun handleClose(generation: Int) {
        synchronized(lock) {
            if (socketGeneration != generation) return
            connected = false
            stopHeartbeat()
            rejectAllPending("server signal disconnected")
        }
        val graceSec = graceSecondsRemaining()
        val err: String
        synchronized(lock) { err = lastError }
        callback?.onDisconnected(graceSec, err)

        if (!synchronized(lock) { intentionalClose }) {
            val tunnel = GoHomeTunnelRuntime.status()
            if (tunnel.optString("udp") == "connected" && graceSecondsRemaining() > 0) {
                scheduleReconnect(generation)
            }
        }
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
        synchronized(lock) { ws?.send(envelope.toString()) }
    }

    private fun startHeartbeat(generation: Int) {
        stopHeartbeat()
        heartbeatThread = thread(name = "go-home-heartbeat", isDaemon = true) {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                    if (synchronized(lock) { socketGeneration != generation || !connected }) return@thread
                    val timestamp = System.currentTimeMillis() / 1000
                    val timeKey = computeTimeKey(authCode, timestamp)
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
                val latch = CountDownLatch(1)
                val wsURL = buildWSURL(server)
                val request = Request.Builder().url(wsURL).build()
                val currentAuthCode = authCode
                val currentDeviceId = deviceId

                val newWs = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val timestamp = System.currentTimeMillis() / 1000
                        val timeKey = computeTimeKey(currentAuthCode, timestamp)
                        val authMsg = JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", "auth-reconnect-${seq.incrementAndGet()}")
                            .put("action", "device.auth")
                            .put("params", JSONObject()
                                .put("device_id", currentDeviceId)
                                .put("device_type", "client")
                                .put("auth_code", currentAuthCode)
                                .put("time_key", timeKey)
                                .put("timestamp", timestamp))
                        webSocket.send(authMsg.toString())
                        synchronized(lock) {
                            if (socketGeneration != generation) { webSocket.close(1000, "stale"); return }
                            ws = webSocket
                            connected = true
                            graceUntil = 0
                            lastError = ""
                        }
                        startHeartbeat(generation)
                        callback?.onConnected()
                        latch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        handleClose(generation)
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        synchronized(lock) {
                            lastError = t.message ?: "reconnect failed"
                            connected = false
                        }
                        latch.countDown()
                        scheduleReconnect(generation)
                    }
                })
                synchronized(lock) { ws = newWs }
                latch.await(15, TimeUnit.SECONDS)
            } catch (_: InterruptedException) { }
        }
    }

    private fun stopInternal() {
        stopHeartbeat()
        reconnectThread?.interrupt()
        reconnectThread = null
        try { ws?.close(1000, "client disconnect") } catch (_: Exception) {}
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
        val uri = java.net.URI(value)
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
