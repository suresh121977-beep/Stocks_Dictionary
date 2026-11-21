package com.example.hub

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hosts the Screener watchlist page (assets/screener.html).
 * A WebView cannot call screener.in directly (CORS), so the page asks this class to fetch for it
 * (window.NativeHttp.get) and to save CSV files (window.NativeHttp.saveCsv).
 */
class ScreenerActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).followRedirects(true).build()
    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.url?.let { loader.shouldInterceptRequest(it) }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                return true
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true      // the page keeps the watchlist in localStorage
        web.addJavascriptInterface(Bridge(), "NativeHttp")
        web.loadUrl("https://appassets.androidplatform.net/assets/screener.html")

        // Back closes an open stock detail first, then leaves to the hub.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.goBack&&window.goBack())") { r -> if (r != "true") finish() }
            }
        })
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun reply(id: Int, status: Int, body: String) {
        runOnUiThread { web.evaluateJavascript("window.__nh($id,$status,${JSONObject.quote(body)})", null) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun get(id: Int, url: String) {
            val host = Uri.parse(url).host ?: ""
            if (!url.startsWith("https://") || !(host == "screener.in" || host.endsWith(".screener.in"))) {
                reply(id, 0, "blocked: only screener.in is allowed")
                return
            }
            val req = Request.Builder().url(url).header("User-Agent", ua)
                .header("Accept", "text/html,application/json,*/*").build()
            http.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { reply(id, 0, e.message ?: "network error") }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r -> reply(id, r.code, r.body?.string().orEmpty()) }
                }
            })
        }

        /** Returns "ok:<where>" or "err:<reason>". */
        @JavascriptInterface
        fun saveCsv(name: String, text: String): String = try {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val data = text.toByteArray()
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TradingHub")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IOException("Could not create the file")
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                "ok:Downloads/TradingHub/$safe"
            } else {
                val f = File(getExternalFilesDir(null), safe)
                f.writeBytes(data)
                "ok:" + f.path
            }
        } catch (e: Exception) {
            "err:" + (e.message ?: "failed")
        }
    }
}
