package com.example.angelwatch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Minimal Angel One SmartAPI client (blocking; call from a background thread). */
object Api {
    private const val BASE = "https://apiconnect.angelone.in"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()

    @Volatile var jwt: String? = null
    @Volatile private var apiKey = ""
    private val lastCall = HashMap<String, Long>()

    /** Angel One allows ~1 quote/sec and ~3 history calls/sec in total, so all callers share one throttle. */
    private fun throttle(path: String) {
        val key: String
        val gap: Long
        when {
            path.contains("historical") -> { key = "h"; gap = 450L }
            path.contains("quote") -> { key = "q"; gap = 1100L }
            else -> { key = "o"; gap = 300L }
        }
        synchronized(lastCall) {
            val wait = (lastCall[key] ?: 0L) + gap - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[key] = System.currentTimeMillis()
        }
    }

    private fun call(path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        val rb = Request.Builder().url(BASE + path)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-UserType", "USER")
            .header("X-SourceID", "WEB")
            .header("X-ClientLocalIP", "192.168.1.10")
            .header("X-ClientPublicIP", "106.193.147.98")
            .header("X-MACAddress", "fe80::216e:6507:4b90:3719")
            .header("X-PrivateKey", apiKey)
        if (auth) rb.header("Authorization", "Bearer $jwt")
        if (body != null) rb.post(body.toString().toRequestBody(JSON_TYPE)) else rb.get()
        return http.newCall(rb.build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            try {
                JSONObject(txt)
            } catch (e: Exception) {
                throw IOException("HTTP ${r.code}: ${txt.take(150)}")
            }
        }
    }

    @Synchronized
    fun login() {
        val c = Store.creds()
        apiKey = c.apiKey
        val body = JSONObject().put("clientcode", c.client).put("password", c.pin).put("totp", Totp.now(c.totp))
        val j = call("/rest/auth/angelbroking/user/v1/loginByPassword", body, auth = false)
        if (!j.optBoolean("status")) throw IOException(j.optString("message", "Login failed"))
        jwt = j.getJSONObject("data").getString("jwtToken")
    }

    /** Authenticated call with automatic re-login and rate-limit retry. */
    fun secured(path: String, body: JSONObject?): JSONObject {
        var last = "Request failed"
        for (attempt in 0 until 6) {
            if (jwt == null) login()
            throttle(path)
            val j = call(path, body)
            if (j.optBoolean("status")) return j
            val code = j.optString("errorcode")
            val msg = j.optString("message")
            last = msg.ifBlank { "Request failed ($code)" }
            when {
                code == "AG8001" || code == "AG8002" || code == "AG8003" || msg.contains("token", true) -> jwt = null
                msg.contains("access rate", true) || msg.contains("rate limit", true) || msg.contains("exceeding", true) -> Thread.sleep(1500L * (attempt + 1))
                else -> throw IOException(Net.explain(last))
            }
        }
        throw IOException(last)
    }

    /** items = (exchange, token). Returns the raw "fetched" entries. */
    fun quotes(items: List<Pair<String, String>>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        items.chunked(50).forEachIndexed { i, chunk ->
            if (i > 0) Thread.sleep(1100)
            val ex = JSONObject()
            chunk.groupBy({ it.first }, { it.second }).forEach { (k, v) -> ex.put(k, JSONArray(v)) }
            val j = secured("/rest/secure/angelbroking/market/v1/quote/",
                JSONObject().put("mode", "FULL").put("exchangeTokens", ex))
            val f = j.optJSONObject("data")?.optJSONArray("fetched") ?: JSONArray()
            for (k in 0 until f.length()) out.add(f.getJSONObject(k))
        }
        return out
    }

    /** Full daily history (up to ~20 years), oldest first. */
    fun candles(exch: String, token: String): List<Candle> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        var to = LocalDateTime.now()
        val all = ArrayList<Candle>()
        for (i in 0 until 8) {
            val from = to.minusDays(1900)
            try {
                val j = secured("/rest/secure/angelbroking/historical/v1/getCandleData",
                    JSONObject().put("exchange", exch).put("symboltoken", token)
                        .put("interval", "ONE_DAY")
                        .put("fromdate", from.format(fmt)).put("todate", to.format(fmt)))
                val arr = j.optJSONArray("data")
                if (arr == null || arr.length() == 0) break
                for (k in 0 until arr.length()) {
                    val a = arr.getJSONArray(k)
                    all.add(Candle(LocalDate.parse(a.getString(0).substring(0, 10)),
                        a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)))
                }
            } catch (e: IOException) {
                if (i == 0) throw e else break
            }
            to = from.minusMinutes(1)
        }
        return all.distinctBy { it.date }.sortedBy { it.date }
    }

    /** Last ~6 days of 5-minute candles (oldest first), used for the 30-minute breakout signal. */
    fun candles5(exch: String, token: String): List<Bar> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val to = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val from = to.minusDays(6)
        val j = secured("/rest/secure/angelbroking/historical/v1/getCandleData",
            JSONObject().put("exchange", exch).put("symboltoken", token).put("interval", "FIVE_MINUTE")
                .put("fromdate", from.format(fmt)).put("todate", to.format(fmt)))
        val arr = j.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Bar>()
        for (k in 0 until arr.length()) {
            val a = arr.getJSONArray(k)
            out.add(Bar(OffsetDateTime.parse(a.getString(0)).toLocalDateTime(), a.getDouble(2), a.getDouble(3), a.getDouble(4)))
        }
        return out.sortedBy { it.t }
    }

    fun holdings(): List<Holding> {
        val j = secured("/rest/secure/angelbroking/portfolio/v1/getAllHolding", null)
        val d = j.opt("data")
        val arr: JSONArray = when (d) {
            is JSONObject -> d.optJSONArray("holdings")
            is JSONArray -> d
            else -> null
        } ?: JSONArray()
        val out = ArrayList<Holding>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Holding(o.optString("tradingsymbol").removeSuffix("-EQ"), o.optString("symboltoken"),
                o.optInt("quantity"), o.optDouble("averageprice", 0.0), o.optDouble("ltp", 0.0),
                o.optDouble("profitandloss", 0.0), o.optDouble("pnlpercentage", 0.0)))
        }
        return out
    }

    fun orders(): List<OrderRow> {
        val j = secured("/rest/secure/angelbroking/order/v1/getOrderBook", null)
        val arr = j.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<OrderRow>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(OrderRow(o.optString("tradingsymbol"), o.optString("transactiontype"),
                o.optString("quantity"), o.optString("price"), o.optString("status"),
                o.optString("ordertype"), o.optString("updatetime")))
        }
        return out
    }

    /** Equity delivery buy (kept for the stock order panel). */
    fun buy(sym: String, token: String, qty: Int, limit: Double?): String =
        order(Instrument(sym, token, sym), "BUY", qty, limit, "DELIVERY")

    /**
     * Any instrument: equity, F&O (NFO/BFO) or MCX.
     * product: DELIVERY (equity), INTRADAY, CARRYFORWARD (F&O / MCX overnight).
     * orderType null = MARKET or LIMIT depending on [limit]. A STOPLOSS_LIMIT order needs variety = "STOPLOSS" and a trigger.
     */
    fun order(i: Instrument, side: String, qty: Int, limit: Double?, product: String,
              orderType: String? = null, trigger: Double? = null, variety: String = "NORMAL"): String {
        val ts = if (i.exch == "NSE" && !i.symbol.endsWith("-EQ")) i.symbol + "-EQ" else i.symbol
        val ot = orderType ?: if (limit == null) "MARKET" else "LIMIT"
        val b = JSONObject().put("variety", variety).put("tradingsymbol", ts).put("symboltoken", i.token)
            .put("transactiontype", side).put("exchange", i.exch).put("ordertype", ot)
            .put("producttype", product).put("duration", "DAY")
            .put("price", limit?.toString() ?: "0").put("squareoff", "0").put("stoploss", "0")
            .put("quantity", qty.toString())
        if (trigger != null) b.put("triggerprice", trigger.toString())
        val j = secured("/rest/secure/angelbroking/order/v1/placeOrder", b)
        return j.optJSONObject("data")?.optString("orderid").orEmpty()
    }

    /** GTT sell rule (stays active on Angel One's servers, even if this app is closed). */
    fun gttSell(sym: String, token: String, qty: Int, trigger: Double, price: Double): String {
        val b = JSONObject().put("tradingsymbol", "$sym-EQ").put("symboltoken", token).put("exchange", "NSE")
            .put("producttype", "DELIVERY").put("transactiontype", "SELL")
            .put("price", price).put("qty", qty).put("triggerprice", trigger)
            .put("disclosedqty", qty).put("timeperiod", 365)
        val j = secured("/rest/secure/angelbroking/gtt/v1/createRule", b)
        return j.optJSONObject("data")?.optString("id").orEmpty()
    }
}
