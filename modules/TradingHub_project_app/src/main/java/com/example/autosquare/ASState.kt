package com.example.autosquare

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

fun n2(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)

/** A portfolio holding with its computed levels. */
class Hd(val sym: String, val tok: String, val qty: Int, val avg: Double, val ltp: Double, val lv: JSONObject?)

/** A screener candidate. */
class Sr(val sym: String, val tok: String, val ltp: Double, val lv: JSONObject, val close: Double = 0.0)

/** Live price and previous close. */
class Snap(val ltp: Double, val close: Double)

class ScreenOut(val rows: List<Sr>, val msg: String)

/**
 * Screens and portfolio loads can take minutes, so they run here (not in a composable) and keep going
 * when the user switches tabs. The UI just reads these observable fields.
 */
object ASState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: Store? = null

    @Synchronized
    fun store(ctx: Context): Store {
        var s = store
        if (s == null) { s = Store(ctx.applicationContext); store = s }
        return s
    }

    fun toast(ctx: Context, m: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, m, Toast.LENGTH_LONG).show() }
    }

    // ---------------- Portfolio ----------------
    var holdings by mutableStateOf<List<Hd>>(emptyList())
    var pStatus by mutableStateOf("")
    var pLoading by mutableStateOf(false)
    var pVersion by mutableStateOf(0)   // bumped when a per-stock stop-loss changes, to redraw the list

    fun loadPortfolio(ctx: Context) {
        if (pLoading) return
        val app = ctx.applicationContext
        pLoading = true
        pStatus = "Loading holdings…"
        scope.launch {
            try {
                val api = Session.get(app)
                val hs = api.holdings()
                val out = ArrayList<Hd>()
                for (i in 0 until hs.length()) {
                    val o = hs.getJSONObject(i)
                    val tok = o.optString("symboltoken")
                    pStatus = "Levels ${i + 1}/${hs.length()}…"
                    out.add(Hd(o.optString("tradingsymbol"), tok, o.optString("quantity").toDoubleOrNull()?.toInt() ?: 0,
                        o.optString("averageprice").toDoubleOrNull() ?: 0.0, o.optString("ltp").toDoubleOrNull() ?: 0.0,
                        Data.levels(app, api, tok)))
                }
                Data.save(app)
                holdings = out
                pStatus = "${out.size} holdings. Tap a stock for levels / its own stop-loss."
            } catch (e: Exception) {
                pStatus = "Error: ${e.message}"
            }
            pLoading = false
        }
    }

    fun updateLiveNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                Live.update(app, Session.get(app), store(app))
                toast(app, "Files updated in Downloads/AutoSquare")
            } catch (e: Exception) {
                toast(app, "Error: ${e.message}")
            }
        }
    }

    fun csvText(): String {
        val rows = ArrayList<String>()
        rows.add("Symbol,Qty,AvgPrice,LTP,PnL%,52W High,52W Low,PrevHigh,PrevLow,Fib23.6,Fib38.2,Fib50,Fib61.8,Fib78.6")
        for (x in holdings) {
            val pnl = if (x.avg > 0) (x.ltp - x.avg) / x.avg * 100 else 0.0
            val lv = x.lv
            if (lv != null) {
                val fb = Calc.fibs(lv.getDouble("h52"), lv.getDouble("l52"))
                rows.add(listOf(x.sym, x.qty.toString(), n2(x.avg), n2(x.ltp), n2(pnl), n2(lv.getDouble("h52")), n2(lv.getDouble("l52")),
                    n2(lv.getDouble("pdh")), n2(lv.getDouble("pdl"))).joinToString(",") + "," + fb.joinToString(",") { n2(it.second) })
            } else {
                rows.add("${x.sym},${x.qty},${n2(x.avg)},${n2(x.ltp)},${n2(pnl)}")
            }
        }
        return rows.joinToString("\n")
    }

    // ---------------- Screeners ----------------
    var sBusy by mutableStateOf(false)
    var sProgress by mutableStateOf("")
    var sKind by mutableStateOf(-1)
    var sOut by mutableStateOf<Map<Int, ScreenOut>>(emptyMap())

    /** kind 0 = intraday BUY, 1 = positional trend BUY, 2 = intraday SELL (short). */
    fun runScreener(ctx: Context, kind: Int) {
        if (sBusy) { toast(ctx, "Already running"); return }
        val app = ctx.applicationContext
        sBusy = true
        sKind = kind
        sProgress = "Starting…"
        scope.launch {
            try {
                val api = Session.get(app)
                val st = Master.nifty500(app) { m -> sProgress = m }
                val data = ArrayList<Pair<Pair<String, String>, JSONObject>>()
                st.forEachIndexed { i, p ->
                    if (i % 5 == 0) sProgress = "Loading levels $i/${st.size}…"
                    val lv = Data.levels(app, api, p.second)
                    if (lv != null) data.add(p to lv)
                    if (i % 25 == 0) Data.save(app)
                }
                Data.save(app)
                sProgress = "Fetching live prices…"
                val lt = api.snap(data.map { it.first.second })
                val rs = data.mapNotNull { (p, lv) -> lt[p.second]?.takeIf { it.ltp > 0 }?.let { Sr(p.first, p.second, it.ltp, lv, it.close) } }
                val cand = when (kind) {
                    0 -> rs.filter { it.ltp > it.lv.getDouble("pdh") }.sortedBy { it.ltp / it.lv.getDouble("pdh") }
                    2 -> rs.filter { it.ltp < it.lv.getDouble("pdl") }.sortedByDescending { it.ltp / it.lv.getDouble("pdl") }
                    else -> rs.filter { r ->
                        listOf("sma20", "sma50", "sma100", "sma200").all { r.lv.getDouble(it) > 0 && r.ltp > r.lv.getDouble(it) } &&
                            r.ltp > r.lv.getDouble("wp")
                    }.sortedBy { it.ltp / it.lv.getDouble("wp") }
                }
                val valid = cand.filter { Plan.make(it.lv, it.ltp, kind).err == null }
                val out = if (valid.isNotEmpty())
                    ScreenOut(valid.take(80), "${valid.size} valid setups (${cand.size} pass the basic filter, ${rs.size} scanned). Tap a stock to place an order.")
                else
                    ScreenOut(cand.take(30), "No setup passes all rules. ${cand.size} of ${rs.size} stocks pass the basic filter – shown in grey with the reason.")
                sOut = sOut + (kind to out)
            } catch (e: Exception) {
                sOut = sOut + (kind to ScreenOut(emptyList(), "Error: ${e.message}"))
            }
            sBusy = false
            sKind = -1
            sProgress = ""
        }
    }

    /** Limit order, wait up to 15s for the fill, then hand the trade to the service (partial booking + trailing stop). */
    fun placeOrder(ctx: Context, r: Sr, pl: Plan.P, qty: Int) {
        val app = ctx.applicationContext
        val s = store(app)
        scope.launch {
            try {
                val api = Session.get(app)
                val sym = r.sym + "-EQ"
                val dir = if (pl.side == "BUY") 1 else -1
                val oid = api.order(sym, r.tok, "NSE", pl.side, qty, pl.product, r.ltp, true)
                toast(app, "Order $oid placed, waiting for fill…")
                val fill = api.waitFill(oid, 15)
                if (fill == null) {
                    toast(app, "Not filled in 15s – order cancelled, nothing to manage.")
                } else {
                    val en = if (fill.first > 0) fill.first else r.ltp
                    Trades.add(s, JSONObject().put("id", r.tok + System.currentTimeMillis()).put("sym", sym).put("token", r.tok)
                        .put("product", pl.product).put("dir", dir).put("q0", fill.second).put("qty", fill.second).put("entry", en)
                        .put("sl", pl.sl).put("trailD", pl.trailD).put("high", en)
                        .put("t1", pl.t[0]).put("t2", pl.t[1]).put("t3", pl.t[2]).put("stage", 0))
                    app.startForegroundService(Intent(app, MonitorService::class.java))
                    toast(app, "Filled ${fill.second} @ ${n2(en)}. Auto stop-loss / ATR trailing is running.")
                }
            } catch (e: Exception) {
                toast(app, "Order failed: ${e.message}")
            }
        }
    }
}
