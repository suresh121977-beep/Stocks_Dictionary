package com.stocklevels

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var web: WebView

    inner class Bridge {
        // Yahoo blocks cross-origin calls from a WebView, so the request is made natively.
        @JavascriptInterface
        fun fetch(url: String, id: String) {
            Thread {
                var err = ""
                var body = ""
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000
                    c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    c.setRequestProperty("Accept", "application/json")
                    if (c.responseCode == 200) body = c.inputStream.bufferedReader().readText()
                    else err = "HTTP " + c.responseCode
                } catch (e: Exception) {
                    err = e.message ?: "Network error"
                }
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.__cb('$id',${JSONObject.quote(err)},${JSONObject.quote(body)})", null
                    )
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/stocklevels/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
