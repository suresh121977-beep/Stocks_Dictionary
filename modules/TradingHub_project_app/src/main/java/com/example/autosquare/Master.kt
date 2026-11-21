package com.example.autosquare
import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object Master {
    private val http = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private fun JsonReader.str(): String = if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()

    /** Returns list of (NSE symbol, Angel token) for Nifty 500. Downloaded once a week and cached. */
    fun nifty500(ctx: Context, say: (String) -> Unit): List<Pair<String, String>> {
        val f = File(ctx.filesDir, "n500.txt")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86400_000L)
            return f.readLines().filter { it.contains(",") }.map { it.split(",").let { p -> p[0] to p[1] } }
        say("Downloading Nifty 500 list…")
        val csv = http.newCall(Request.Builder().url("https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv")
            .header("User-Agent", "Mozilla/5.0").build()).execute().use { it.body!!.string() }
        val syms = csv.lines().drop(1).mapNotNull { l -> l.split(",").let { c -> if (c.size >= 3) c[c.size - 3].trim() else null } }.filter { it.isNotEmpty() }
        say("Downloading Angel scrip master (large, one time)…")
        val map = HashMap<String, String>()
        http.newCall(Request.Builder().url("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json").build()).execute().use { r ->
            val jr = JsonReader(r.body!!.charStream()); jr.beginArray()
            while (jr.hasNext()) {
                jr.beginObject(); var tok = ""; var sym = ""; var name = ""; var seg = ""
                while (jr.hasNext()) when (jr.nextName()) {
                    "token" -> tok = jr.str(); "symbol" -> sym = jr.str(); "name" -> name = jr.str(); "exch_seg" -> seg = jr.str()
                    else -> jr.skipValue()
                }
                jr.endObject()
                if (seg == "NSE" && sym.endsWith("-EQ")) map[name] = tok
            }
        }
        val out = syms.mapNotNull { s -> map[s]?.let { s to it } }
        f.writeText(out.joinToString("\n") { "${it.first},${it.second}" })
        return out
    }
}
