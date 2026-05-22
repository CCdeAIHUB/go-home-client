package com.ccdeaihub.gohome

import android.annotation.SuppressLint
import android.app.Activity
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
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 4701
    }
}
