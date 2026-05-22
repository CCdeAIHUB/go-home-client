package com.ccdeaihub.gohome

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(GoHomeBridge(), "GoHomeNative")
        webView.loadUrl("file:///android_asset/ui/index.html")
        setContentView(webView)
    }

    class GoHomeBridge {
        @JavascriptInterface
        fun platform(): String = "android"

        @JavascriptInterface
        fun status(): String = "native-shell-ready"
    }
}

