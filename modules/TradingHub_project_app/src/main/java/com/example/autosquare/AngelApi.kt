package com.example.autosquare
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AuthException(m: String) : Exception(m)

object Session {
    private var a: AngelApi? = null
    @Synchronized fun get(c: Context): AngelApi { if (a == null) a = AngelApi(Store(c.applicationContext)); return a!! }
}

class AngelApi(private val s: Store) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val base = "https://apiconnect.angelone.in"
    @Volatile var jwt: String? = null

    private fun req(path: String) = Request.Builder().url(base + path)
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .header("X-UserType", "USER").header("X-SourceID", "WEB")
        .header("X-ClientLocalIP", "127.0.0.1").header("X-ClientPublicIP", "127.0.0.1")
        .header("X-MACAddress", "00:00:00:00:00:00").header("X-PrivateKey", s.get("apiKey"))
        .also { b -> jwt?.let { b.header("Authorization", "Bearer $it") } }

    private fun body(j: JSONObject) = j.toString().toRequestBody("application/json".toMediaType())

    private fun call(b: Request.Builder): JSONObject {
        http.newCall(b.build()).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            val j = try { JSONObject(txt) } catch (e: Exception) { throw Exception("Bad response (${r.code})") }
            val ec = j.optString("errorcode")
            if (r.code == 401 || ec.startsWith("AG800")) throw AuthException(j.optString("message"))
            if (!j.optBoolean("status", false)) throw Exception(com.example.angelwatch.Net.explain("${j.optString("message")} ($ec)"))
            return j
        }
    }

    @Synchronized fun login() {
        jwt = null
        val j = call(req("/rest/auth/angelbroking/user/v1/loginByPassword").post(body(JSONObject()
            .put("clientcode", s.get("client")).put("password", s.get("pin")).put("totp", Totp.now(s.get("totp"))))))
        jwt = j.getJSONObject("data").getString("jwtToken")
    }

    private fun <T> auth(f: () -> T): T {
        if (jwt == null) login()
        return try { f() } catch (e: AuthException) { login(); f() }
    }

    fun positions(): JSONArray = auth { call(req("/rest/secure/angelbroking/order/v1/getPosition").get()).optJSONArray("data") ?: JSONArray() }

    fun holdings(): JSONArray = auth {
        call(req("/rest/secure/angelbroking/portfolio/v1/getAllHolding").get()).optJSONObject("data")?.optJSONArray("holdings") ?: JSONArray()
    }

    fun ltp(tokens: List<String>): Map<String, Double> = auth {
        val out = HashMap<String, Double>()
        tokens.distinct().chunked(50).forEachIndexed { i, ch ->
            if (i > 0) Thread.sleep(1100)
            val b = body(JSONObject().put("mode", "LTP").put("exchangeTokens", JSONObject().put("NSE", JSONArray(ch))))
            val arr = call(req("/rest/secure/angelbroking/market/v1/quote").post(b)).optJSONObject("data")?.optJSONArray("fetched")
            if (arr != null) for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k); out[o.optString("symbolToken")] = o.optDouble("ltp", 0.0)
            }
        }
        out
    }

    /** Live price + previous close (FULL quote), used for the day change shown in the screener lists. */
    fun snap(tokens: List<String>): Map<String, Snap> = auth {
        val out = HashMap<String, Snap>()
        tokens.distinct().chunked(50).forEachIndexed { i, ch ->
            if (i > 0) Thread.sleep(1100)
            val b = body(JSONObject().put("mode", "FULL").put("exchangeTokens", JSONObject().put("NSE", JSONArray(ch))))
            val arr = call(req("/rest/secure/angelbroking/market/v1/quote").post(b)).optJSONObject("data")?.optJSONArray("fetched")
            if (arr != null) for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val l = o.optDouble("ltp", 0.0)
                var c = o.optDouble("close", 0.0)
                if (c <= 0 && o.has("netChange")) c = l - o.optDouble("netChange", 0.0)
                out[o.optString("symbolToken")] = Snap(l, if (c > 0) c else 0.0)
            }
        }
        out
    }

    fun candles(token: String): JSONArray = auth {
        val z = java.time.ZoneId.of("Asia/Kolkata"); val now = java.time.ZonedDateTime.now(z)
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val b = body(JSONObject().put("exchange", "NSE").put("symboltoken", token).put("interval", "ONE_DAY")
            .put("fromdate", now.minusDays(420).format(f)).put("todate", now.format(f)))
        call(req("/rest/secure/angelbroking/historical/v1/getCandleData").post(b)).optJSONArray("data") ?: JSONArray()
    }

    fun order(sym: String, token: String, exch: String, side: String, qty: Int, product: String, ref: Double = 0.0, limit: Boolean = false): String = auth {
        fun place(type: String, price: String): String {
            val b = body(JSONObject().put("variety", "NORMAL").put("tradingsymbol", sym).put("symboltoken", token)
                .put("transactiontype", side).put("exchange", exch).put("ordertype", type).put("producttype", product)
                .put("duration", "DAY").put("price", price).put("squareoff", "0").put("stoploss", "0").put("quantity", qty.toString()))
            return call(req("/rest/secure/angelbroking/order/v1/placeOrder").post(b)).optJSONObject("data")?.optString("orderid") ?: "ok"
        }
        fun lim(buf: Double) = String.format("%.2f", Math.round(ref * (if (side == "BUY") 1 + buf else 1 - buf) / 0.05) * 0.05)
        if (limit && ref > 0) place("LIMIT", lim(0.002))
        else try { place("MARKET", "0") } catch (e: AuthException) { throw e } catch (e: Exception) {
            val m = (e.message ?: "").lowercase()
            if (ref > 0 && (m.contains("categor") || m.contains("market") || m.contains("price"))) place("LIMIT", lim(0.005))
            else throw Exception("${e.message} [$sym $product]")
        }
    }

    fun orderBook(): JSONArray = auth { call(req("/rest/secure/angelbroking/order/v1/getOrderBook").get()).optJSONArray("data") ?: JSONArray() }

    fun cancel(id: String) { auth { call(req("/rest/secure/angelbroking/order/v1/cancelOrder").post(body(JSONObject().put("variety", "NORMAL").put("orderid", id)))) } }

    /** Waits for the order to fill. Returns (avg price, filled qty) or null if nothing filled (order is cancelled). */
    fun waitFill(id: String, secs: Int): Pair<Double, Int>? {
        fun find(): JSONObject? { val b = orderBook(); for (i in 0 until b.length()) if (b.getJSONObject(i).optString("orderid") == id) return b.getJSONObject(i); return null }
        val end = System.currentTimeMillis() + secs * 1000L
        while (System.currentTimeMillis() < end) {
            val o = find()
            if (o != null) {
                val st = o.optString("orderstatus").lowercase()
                if (st == "complete") return (o.optString("averageprice").toDoubleOrNull() ?: 0.0) to (o.optString("filledshares").toIntOrNull() ?: 0)
                if (st == "rejected" || st == "cancelled") throw Exception("Order $st: ${o.optString("text")}")
            }
            Thread.sleep(1500)
        }
        try { cancel(id) } catch (e: Exception) { }
        Thread.sleep(1000)
        val o = find(); val fq = o?.optString("filledshares")?.toIntOrNull() ?: 0
        return if (fq > 0) (o!!.optString("averageprice").toDoubleOrNull() ?: 0.0) to fq else null
    }
}
