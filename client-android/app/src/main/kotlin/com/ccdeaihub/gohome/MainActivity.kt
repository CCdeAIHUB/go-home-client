package com.ccdeaihub.gohome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.bouncycastle.crypto.digests.SM3Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.util.UUID

class MainActivity : Activity() {
    private var vpnPermissionGranted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.webViewClient = LocalContentClient(assetLoader)
        webView.addJavascriptInterface(GoHomeBridge(this), "GoHomeNative")
        webView.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        setContentView(webView)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            vpnPermissionGranted = resultCode == RESULT_OK
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
        fun deviceId(): String {
            val prefs = activity.getSharedPreferences("go-home", Context.MODE_PRIVATE)
            return prefs.getString("device_id", null) ?: "client-android-${UUID.randomUUID()}".also {
                prefs.edit().putString("device_id", it).apply()
            }
        }

        @JavascriptInterface
        fun timeKey(secret: String, timestampSeconds: Long): String = staticTimeKey(secret, timestampSeconds)

        @JavascriptInterface
        fun vpnPermissionStatus(): String =
            if (activity.vpnPermissionGranted || VpnService.prepare(activity) == null) "granted" else "required"

        @JavascriptInterface
        fun requestVpnPermission(): String {
            val prepareIntent = VpnService.prepare(activity)
            if (prepareIntent == null) {
                activity.vpnPermissionGranted = true
                return "granted"
            }
            activity.runOnUiThread {
                activity.startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST)
            }
            return "requested"
        }

        // ── Signal (WebSocket) API ──

        @JavascriptInterface
        fun signalConnect(server: String, authCode: String): String {
            val devId = deviceId()
            // Run on background thread to avoid blocking the JS thread
            val result = arrayOf<String>("")
            val latch = java.util.concurrent.CountDownLatch(1)
            Thread {
                result[0] = GoHomeSignalClient.connect(server, authCode, devId)
                latch.countDown()
            }.start()
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun signalDisconnect(): String = GoHomeSignalClient.disconnect()

        @JavascriptInterface
        fun signalRPC(action: String, paramsJSON: String): String {
            val result = arrayOf<String>("")
            val latch = java.util.concurrent.CountDownLatch(1)
            Thread {
                result[0] = GoHomeSignalClient.rpc(action, paramsJSON)
                latch.countDown()
            }.start()
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            return result[0]
        }

        @JavascriptInterface
        fun signalStatus(): String = GoHomeSignalClient.getStatus()

        // ── Tunnel (UDP) API ──

        @JavascriptInterface
        fun localNetworkConflict(cidr: String): Boolean = GoHomeTunnelRuntime.localNetworkConflict(cidr)

        @JavascriptInterface
        fun prepareTunnel(deviceID: String): String = GoHomeTunnelRuntime.prepare(deviceID).toString()

        @JavascriptInterface
        fun connectTunnel(offer: String, mode: String, virtualCIDR: String): String =
            GoHomeTunnelRuntime.connect(activity, offer, mode, virtualCIDR).toString()

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
            fun staticTimeKey(secret: String, timestampSeconds: Long): String {
                val mac = HMac(SM3Digest())
                mac.init(KeyParameter(secret.toByteArray(Charsets.UTF_8)))
                val window = (timestampSeconds / 30L).toString().toByteArray(Charsets.UTF_8)
                mac.update(window, 0, window.size)
                val digest = ByteArray(mac.macSize)
                mac.doFinal(digest, 0)
                return digest.joinToString(separator = "") { "%02x".format(it) }
            }
        }
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4701
    }
}
