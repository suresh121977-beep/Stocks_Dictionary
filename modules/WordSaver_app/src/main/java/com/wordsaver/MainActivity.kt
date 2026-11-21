package com.wordsaver

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val outs = ConcurrentHashMap<String, Pair<OutputStream, Uri?>>()

    inner class Bridge {
        @JavascriptInterface
        fun saveBegin(id: String, name: String, mime: String) {
            try {
                val safe = name.replace(Regex("[/:*?\"<>|]"), "_")
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    outs[id] = Pair(contentResolver.openOutputStream(uri)!!, uri)
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    outs[id] = Pair(java.io.FileOutputStream(java.io.File(dir, safe)), null)
                }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun saveChunk(id: String, b64: String) { outs[id]?.first?.write(Base64.decode(b64, Base64.DEFAULT)) }

        @JavascriptInterface
        fun saveEnd(id: String) {
            val p = outs.remove(id) ?: return
            p.first.close()
            p.second?.let {
                contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun speak(text: String, rate: Float) {
            if (!ttsReady) return
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) { tts?.language = Locale.US; ttsReady = true }
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/wordsaver/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
