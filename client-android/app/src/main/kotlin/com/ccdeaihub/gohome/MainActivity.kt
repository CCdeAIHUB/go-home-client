package com.ccdeaihub.gohome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.bouncycastle.crypto.digests.SM3Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private var vpnPermissionGranted = false
    private var vpnPermissionLatch: CountDownLatch? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars("light")

        val root = FrameLayout(this)
        val wv = WebView(this)
        webView = wv
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.webViewClient = LocalContentClient(assetLoader)
        wv.addJavascriptInterface(GoHomeBridge(this), "GoHomeNative")
        wv.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        root.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyContentInsets(root)
        setContentView(root)

        // 服务器推送事件回调：家庭服务器状态变更时通知 WebView 刷新
        GoHomeSignalClient.onEventCallback = { action, params ->
            runOnUiThread {
                try {
                    webView?.evaluateJavascript(
                        "if(window._goHomeServerEvent)window._goHomeServerEvent(${JSONObject.quote(action)},${JSONObject.quote(params)});",
                        null
                    )
                } catch (e: Exception) {
                    Log.e("GoHome", "Failed to post server event", e)
                }
            }
        }
    }

    private fun applyContentInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun applySystemBars(themeName: String) {
        val dark = themeName == "dark"
        runOnUiThread {
            window.statusBarColor = Color.parseColor(if (dark) "#0F1413" else "#245F73")
            window.navigationBarColor = Color.parseColor(if (dark) "#0F1413" else "#EEF3EF")
            var flags = window.decorView.systemUiVisibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (dark) {
                    flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            vpnPermissionGranted = resultCode == RESULT_OK
            vpnPermissionLatch?.countDown()
        }
    }

    private class LocalContentClient(
        private val assetLoader: WebViewAssetLoader
    ) : WebViewClientCompat() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return assetLoader.shouldInterceptRequest(request.url)
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
            return assetLoader.shouldInterceptRequest(Uri.parse(url))
        }
    }

    class GoHomeBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun platform(): String = "android"

        @JavascriptInterface
        fun status(): String = "native-shell-ready"

        @JavascriptInterface
        fun setTheme(themeName: String): Boolean {
            activity.applySystemBars(themeName)
            return true
        }

        @JavascriptInterface
        fun deviceId(): String {
            val prefs = activity.getSharedPreferences("go-home", Context.MODE_PRIVATE)
            return prefs.getString("device_id", null) ?: "client-android-${UUID.randomUUID()}".also {
                prefs.edit().putString("device_id", it).apply()
            }
        }

        @JavascriptInterface
        fun timeKey(secret: String, timestampSeconds: Long): String = computeTimeKey(secret, timestampSeconds)

        @JavascriptInterface
        fun vpnPermissionStatus(): String =
            if (activity.vpnPermissionGranted || VpnService.prepare(activity) == null) "granted" else "required"

        /**
         * Request VPN permission and wait for the user's response.
         * Returns "granted" if permission is already granted or user grants it,
         * "denied" if user rejects, "timeout" if waiting too long.
         * This method blocks the calling thread (should be called from a background thread).
         */
        @JavascriptInterface
        fun requestVpnPermission(): String {
            // Already granted?
            if (activity.vpnPermissionGranted || VpnService.prepare(activity) == null) {
                activity.vpnPermissionGranted = true
                return "granted"
            }
            // Set up latch and launch permission intent on UI thread
            val latch = CountDownLatch(1)
            activity.vpnPermissionLatch = latch
            activity.runOnUiThread {
                val prepareIntent = VpnService.prepare(activity)
                if (prepareIntent == null) {
                    activity.vpnPermissionGranted = true
                    latch.countDown()
                } else {
                    activity.startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
                }
            }
            // Wait for user response (up to 60 seconds)
            val granted = latch.await(60, TimeUnit.SECONDS)
            activity.vpnPermissionLatch = null
            return if (granted && activity.vpnPermissionGranted) "granted" else "denied"
        }

        // ── Signal (WebSocket) API ──

        @JavascriptInterface
        fun signalConnect(server: String, authCode: String): String {
            val devId = deviceId()
            val result = arrayOf<String>("")
            val latch = CountDownLatch(1)
            Thread {
                result[0] = GoHomeSignalClient.connect(server, authCode, devId)
                latch.countDown()
            }.start()
            latch.await(15, TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun signalDisconnect(): String = GoHomeSignalClient.disconnect()

        @JavascriptInterface
        fun signalRPC(action: String, paramsJSON: String): String {
            val result = arrayOf<String>("")
            val latch = CountDownLatch(1)
            Thread {
                result[0] = GoHomeSignalClient.rpc(action, paramsJSON)
                latch.countDown()
            }.start()
            latch.await(15, TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun signalStatus(): String = GoHomeSignalClient.getStatus()

        // ── Tunnel (UDP) API ──

        @JavascriptInterface
        fun localNetworkConflict(cidr: String): Boolean = GoHomeTunnelRuntime.localNetworkConflict(cidr)

        @JavascriptInterface
        fun prepareTunnel(deviceID: String): String {
            val result = arrayOf<String>("")
            val latch = CountDownLatch(1)
            Thread {
                try {
                    GoHomeTunnelRuntime.stop(activity)
                    val prepared = GoHomeTunnelRuntime.prepare(deviceID)
                    val protectLatch = CountDownLatch(1)
                    activity.runOnUiThread {
                        try {
                            GoHomeVpnService.protectTunnelSocket(activity)
                            prepared.put("socket_protected", true)
                        } catch (e: Exception) {
                            Log.w("GoHome", "Failed to protect UDP socket before handshake", e)
                            prepared.put("socket_protected", false)
                            prepared.put("protect_error", e.message ?: "protect failed")
                        } finally {
                            protectLatch.countDown()
                        }
                    }
                    protectLatch.await(3, TimeUnit.SECONDS)
                    result[0] = prepared.toString()
                } catch (e: Exception) {
                    result[0] = """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
                }
                latch.countDown()
            }.start()
            latch.await(5, TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun registerTunnelEndpoint(): String {
            val result = arrayOf<String>("")
            val latch = CountDownLatch(1)
            Thread {
                result[0] = GoHomeSignalClient.registerTunnelEndpoint()
                latch.countDown()
            }.start()
            latch.await(5, TimeUnit.SECONDS)
            return result[0]
        }

        /**
         * Connect tunnel asynchronously.
         * Returns a callback ID immediately, then posts the result via
         * window._goHomeTunnelResult(callbackId, jsonResult) when done.
         * This ensures the JS thread is not blocked and the UI can show loading state.
         */
        private var tunnelCallbackSeq = 0

        @JavascriptInterface
        fun connectTunnelAsync(offer: String, mode: String, virtualCIDR: String, routePolicy: String): String {
            val callbackId = "tunnel-${tunnelCallbackSeq++}"
            Thread {
                val jsonResult = try {
                    GoHomeTunnelRuntime.connect(activity, offer, mode, virtualCIDR, routePolicy).toString()
                } catch (e: Exception) {
                    """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
                }
                // Post result back to JS on the main thread
                activity.runOnUiThread {
                    try {
                        activity.webView?.evaluateJavascript(
                            "if(window._goHomeTunnelResult)window._goHomeTunnelResult('$callbackId',${JSONObject.quote(jsonResult)});",
                            null
                        )
                    } catch (e: Exception) {
                        Log.e("GoHome", "Failed to post tunnel result", e)
                    }
                }
            }.start()
            return callbackId
        }

        // Keep sync version as fallback (not used by new UI)
        @JavascriptInterface
        fun connectTunnel(offer: String, mode: String, virtualCIDR: String, routePolicy: String): String {
            val result = arrayOf<String>("")
            val latch = CountDownLatch(1)
            Thread {
                try {
                    result[0] = GoHomeTunnelRuntime.connect(activity, offer, mode, virtualCIDR, routePolicy).toString()
                } catch (e: Exception) {
                    result[0] = """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
                }
                latch.countDown()
            }.start()
            latch.await(15, TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun tunnelStatus(): String = GoHomeTunnelRuntime.status().toString()

        @JavascriptInterface
        fun tunnelStats(): String = GoHomeTunnelRuntime.stats().toString()

        @JavascriptInterface
        fun disconnectTunnel(): Boolean {
            GoHomeSignalClient.disconnect()
            GoHomeTunnelRuntime.stop(activity)
            return true
        }

        companion object {
            fun staticTimeKey(secret: String, timestampSeconds: Long): String = computeTimeKey(secret, timestampSeconds)
        }
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4701
    }
}

/** Top-level SM3-HMAC time key function, shared by GoHomeBridge and GoHomeSignalClient. */
fun computeTimeKey(secret: String, timestampSeconds: Long): String {
    val mac = HMac(SM3Digest())
    mac.init(KeyParameter(secret.toByteArray(Charsets.UTF_8)))
    val window = (timestampSeconds / 30L).toString().toByteArray(Charsets.UTF_8)
    mac.update(window, 0, window.size)
    val digest = ByteArray(mac.macSize)
    mac.doFinal(digest, 0)
    return digest.joinToString(separator = "") { "%02x".format(it) }
}
