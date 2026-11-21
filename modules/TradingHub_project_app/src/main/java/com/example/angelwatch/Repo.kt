package com.example.angelwatch

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Shared live state, written by the background service and read by the UI. */
object Repo {
    val quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val indices = MutableStateFlow<List<Quote>>(emptyList())
    val holdings = MutableStateFlow<List<Holding>>(emptyList())
    val orders = MutableStateFlow<List<OrderRow>>(emptyList())
    val status = MutableStateFlow("Not started")
    val running = MutableStateFlow(false)

    /** Daily/weekly/monthly pivots per symbol, used by the Watchlist / Portfolio pivot filters. */
    val pivots = MutableStateFlow<Map<String, PivotSet>>(emptyMap())
    private val pivotFail = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun putPivots(sym: String, a: Analysis) {
        pivots.value = pivots.value + (sym to PivotSet(LocalDate.now(), a.daily.p, a.weekly?.p, a.monthly?.p))
    }

    /** Computes missing pivots for up to [max] symbols. Returns how many it tried (0 = nothing left to do). */
    fun ensurePivots(symbols: List<String>, max: Int): Int {
        val today = LocalDate.now()
        var done = 0
        for (s in symbols) {
            if (done >= max) break
            if (pivots.value[s]?.day == today) continue
            val failedAt = pivotFail[s]
            if (failedAt != null && System.currentTimeMillis() - failedAt < 600_000) continue
            val token = Instruments.get(s)?.token ?: holdings.value.find { it.symbol == s }?.token ?: continue
            try {
                val c = candlesFor(s, token)
                val ltp = quotes.value[s]?.ltp?.takeIf { it > 0 } ?: c.last().c
                putPivots(s, Analyzer.analyze(c, ltp))
            } catch (e: Exception) {
                pivotFail[s] = System.currentTimeMillis()
            }
            done++
        }
        return done
    }

    private val idx = mapOf("99926000" to ("NSE" to "NIFTY"), "99919000" to ("BSE" to "SENSEX"))
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()

    fun tick() {
        // tokens can repeat across exchanges, so everything is keyed "EXCHANGE:token"
        val items = ArrayList<Pair<String, String>>()
        val keySym = HashMap<String, String>()
        Store.lists.value.flatMap { it.symbols }.distinct().forEach { s ->
            Instruments.get(s)?.let { keySym[it.exch + ":" + it.token] = s }
        }
        holdings.value.forEach { if (it.qty > 0 && it.token.isNotBlank()) keySym["NSE:" + it.token] = it.symbol }
        keySym.keys.forEach { k -> items.add(k.substringBefore(':') to k.substringAfter(':')) }
        idx.forEach { (t, v) -> items.add(v.first to t) }
        val q = HashMap<String, Quote>()
        val ix = ArrayList<Quote>()
        for (o in Api.quotes(items)) {
            val t = o.optString("symbolToken")
            val qt = Quote(o.optString("tradingSymbol"), t, o.optDouble("ltp", 0.0),
                o.optDouble("netChange", 0.0), o.optDouble("percentChange", 0.0), o.optDouble("close", 0.0))
            val i = idx[t]
            if (i != null) ix.add(qt.copy(symbol = i.second))
            else {
                val ex = o.optString("exchange")
                val s = keySym[ex + ":" + t] ?: keySym["NSE:" + t]
                if (s != null) q[s] = qt.copy(symbol = s)
            }
        }
        quotes.value = q
        indices.value = ix.sortedBy { it.symbol }
    }

    fun refreshHoldings() { holdings.value = Api.holdings() }
    fun refreshOrders() { orders.value = Api.orders() }

    fun candlesFor(sym: String, token: String): List<Candle> {
        cache[sym]?.let { if (System.currentTimeMillis() - it.first < 1_800_000) return it.second }
        val c = Api.candles(Instruments.get(sym)?.exch ?: "NSE", token)
        if (c.isEmpty()) throw IOException("No price history for $sym")
        cache[sym] = System.currentTimeMillis() to c
        return c
    }

    private fun tickRound(x: Double) = Math.round(x * 20) / 20.0

    fun placeExits(sym: String, token: String, qty: Int, sl: Double?, tp: Double?): String {
        val out = ArrayList<String>()
        if (sl != null) {
            out.add(try {
                Api.gttSell(sym, token, qty, tickRound(sl), tickRound(sl * 0.995))
                "Stop-loss GTT set: trigger ₹" + f2(tickRound(sl))
            } catch (e: Exception) { "Stop-loss GTT failed: ${e.message}" })
            Thread.sleep(600)
        }
        if (tp != null) {
            out.add(try {
                Api.gttSell(sym, token, qty, tickRound(tp), tickRound(tp * 0.998))
                "Target GTT set: trigger ₹" + f2(tickRound(tp))
            } catch (e: Exception) { "Target GTT failed: ${e.message}" })
        }
        return out.joinToString("\n")
    }

    private fun roundTo(x: Double, tick: Double): Double = Math.round(x / tick) * tick

    /**
     * F&O / MCX order. [limit] null = market. When [sl] is given a STOPLOSS_LIMIT order on the opposite
     * side is placed right after the entry (only used with market entries so there is a position to protect).
     */
    fun placeDeriv(i: Instrument, side: String, qty: Int, limit: Double?, product: String, sl: Double?): String {
        val id = Api.order(i, side, qty, limit?.let { roundTo(it, i.tick) }, product)
        var msg = "$side order placed (id $id)."
        if (sl != null) {
            Thread.sleep(800)
            val exit = if (side == "BUY") "SELL" else "BUY"
            val trig = roundTo(sl, i.tick)
            val px = roundTo(if (side == "BUY") sl * 0.99 else sl * 1.01, i.tick)
            msg += try {
                val sid = Api.order(i, exit, qty, px, product, orderType = "STOPLOSS_LIMIT", trigger = trig, variety = "STOPLOSS")
                "\nStop-loss order placed (id $sid): trigger ₹" + f2(trig) + ", limit ₹" + f2(px)
            } catch (e: Exception) {
                "\nStop-loss order failed: ${e.message}"
            }
        }
        return msg
    }

    fun placeBuy(sym: String, token: String, qty: Int, limit: Double?, sl: Double?, tp: Double?, gtt: Boolean): String {
        val id = Api.buy(sym, token, qty, limit)
        var msg = "Buy order placed (id $id)."
        if (gtt) {
            Thread.sleep(800)
            msg += "\n" + placeExits(sym, token, qty, sl, tp) +
                "\n\nNote: Angel One may reject the GTT sell until the shares appear in your holdings. If so, use \"Set SL & Target GTT\" later."
        }
        return msg
    }
}

fun f2(v: Double): String = String.format(java.util.Locale.US, "%,.2f", v)
