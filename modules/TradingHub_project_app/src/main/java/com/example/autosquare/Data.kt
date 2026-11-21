package com.example.autosquare
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Data {
    private var cache = JSONObject(); private var loaded = false
    @Synchronized private fun load(ctx: Context) {
        if (loaded) return; loaded = true
        try { val f = File(ctx.filesDir, "lv.json"); if (f.exists()) { val j = JSONObject(f.readText()); if (j.optString("date") == Calc.today()) cache = j.getJSONObject("d") } } catch (e: Exception) { }
    }
    @Synchronized fun save(ctx: Context) { File(ctx.filesDir, "lv.json").writeText(JSONObject().put("date", Calc.today()).put("d", cache).toString()) }

    fun levels(ctx: Context, api: AngelApi, token: String): JSONObject? {
        load(ctx)
        synchronized(this) { cache.optJSONObject(token)?.takeIf { it.has("atr") }?.let { return it } }
        var c: JSONArray? = null
        for (i in 0..1) {
            try { c = api.candles(token); break } catch (e: AuthException) { throw e } catch (e: Exception) { Thread.sleep(1500) }
        }
        Thread.sleep(380) // API limit ~3 candle calls/sec
        val lv = c?.let { Calc.compute(it) } ?: return null
        synchronized(this) { cache.put(token, lv) }
        return lv
    }
}
