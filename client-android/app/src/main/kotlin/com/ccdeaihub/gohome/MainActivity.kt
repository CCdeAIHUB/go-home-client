package com.ccdeaihub.gohome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : Activity() {
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
        webView.addJavascriptInterface(GoHomeBridge(), "GoHomeNative")
        webView.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        setContentView(webView)
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

    class GoHomeBridge {
        @JavascriptInterface
        fun platform(): String = "android"

        @JavascriptInterface
        fun status(): String = "native-shell-ready"
    }
}
