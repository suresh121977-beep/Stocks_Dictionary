package com.example.autosquare
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MonitorService : Service() {
    companion object {
        @Volatile var running = false
        val log = StringBuilder()
        fun add(m: String) { synchronized(log) { log.insert(0, "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}  $m\n"); if (log.length > 6000) log.setLength(6000) } }
    }
    private var t: Thread? = null
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val ch = "mon"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "Monitor", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, ch).setContentTitle("AutoSquare running").setContentText("Watching positions")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi).build()
        // id 2: AngelWatch's service already uses id 1 in this combined app
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(2, n)
        if (!running) {
            running = true
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autosquare:wl").also { it.acquire() }
            t = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun nowIst() = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
    private fun marketOpen(): Boolean {
        val z = nowIst()
        if (z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY) return false
        return z.toLocalTime().isAfter(LocalTime.of(9, 15)) && z.toLocalTime().isBefore(LocalTime.of(15, 30))
    }

    private fun loop() {
        val s = Store(this); val api = Session.get(this); val sent = HashMap<String, Int>(); var lastLive = 0L
        add("Started")
        while (running) {
            try {
                if (!marketOpen()) { Thread.sleep(60_000); continue }
                val sl = s.get("sl", "2").toDoubleOrNull() ?: 2.0
                val tp = s.get("tp", "4").toDoubleOrNull() ?: 4.0
                val hsl = s.get("hsl", "0").toDoubleOrNull() ?: 0.0
                val trades = Trades.list(s)
                val managed = trades.map { it.getString("token") }.toSet()

                // 1) open positions: global SL% / target%
                val pos = api.positions()
                for (k in 0 until pos.length()) try {
                    val p = pos.getJSONObject(k)
                    val q = p.optString("netqty").toIntOrNull() ?: 0
                    val key = p.optString("symboltoken") + p.optString("producttype")
                    if (q == 0) { sent.remove(key); continue }
                    if (p.optString("symboltoken") in managed) continue
                    val ltp = p.optString("ltp").toDoubleOrNull() ?: continue
                    var entry = (if (q > 0) p.optString("buyavgprice") else p.optString("sellavgprice")).toDoubleOrNull() ?: 0.0
                    if (entry <= 0) entry = p.optString("avgnetprice").toDoubleOrNull() ?: 0.0
                    if (entry <= 0 || ltp <= 0) continue
                    val pct = (ltp - entry) / entry * 100 * (if (q > 0) 1 else -1)
                    if ((pct <= -sl || pct >= tp) && sent[key] != q) {
                        val why = if (pct >= tp) "TARGET" else "STOPLOSS"
                        val oid = api.order(p.getString("tradingsymbol"), p.getString("symboltoken"), p.getString("exchange"),
                            if (q > 0) "SELL" else "BUY", Math.abs(q), p.getString("producttype"), ltp)
                        sent[key] = q; add("$why ${p.optString("tradingsymbol")} %.2f%% -> $oid".format(pct))
                    }
                } catch (e: AuthException) { throw e } catch (e: Exception) { add("Pos error: ${e.message}") }

                // 2) holdings: stop-loss for all stocks (global hsl, per-stock override hsl_<token>)
                if (hsl > 0 || s.get("anyH") == "1") {
                    val hs = api.holdings()
                    for (k in 0 until hs.length()) try {
                        val h = hs.getJSONObject(k); val tok = h.optString("symboltoken")
                        val per = s.get("hsl_$tok").toDoubleOrNull() ?: hsl
                        val q = h.optString("quantity").toDoubleOrNull()?.toInt() ?: 0
                        val avg = h.optString("averageprice").toDoubleOrNull() ?: 0.0
                        val ltp = h.optString("ltp").toDoubleOrNull() ?: 0.0
                        if (per <= 0 || q <= 0 || avg <= 0 || ltp <= 0 || tok in managed) continue
                        val pct = (ltp - avg) / avg * 100
                        if (pct <= -per && sent["H$tok"] != q) {
                            val oid = api.order(h.getString("tradingsymbol"), tok, h.optString("exchange", "NSE"), "SELL", q, "DELIVERY", ltp)
                            sent["H$tok"] = q; add("HOLDING SL ${h.optString("tradingsymbol")} %.2f%% -> $oid".format(pct))
                        }
                    } catch (e: AuthException) { throw e } catch (e: Exception) { add("Holding error: ${e.message}") }
                }

                // 3) trades placed from app: partial booking at R2/R3/R4 + trailing stop
                if (trades.isNotEmpty()) {
                    val ltps = api.ltp(trades.map { it.getString("token") })
                    val eod = nowIst().toLocalTime().isAfter(LocalTime.of(15, 15))
                    for (t in trades) try {
                        val tok = t.getString("token"); val ltp = ltps[tok] ?: continue
                        if (ltp <= 0) continue
                        val d = t.optInt("dir", 1); val entry = t.getDouble("entry"); var sl2 = t.getDouble("sl")
                        val hi = if (d == 1) maxOf(t.getDouble("high"), ltp) else minOf(t.getDouble("high"), ltp)
                        var stage = t.getInt("stage"); var qty = t.getInt("qty"); val part = maxOf(1, t.getInt("q0") / 3)
                        val td = t.optDouble("trailD", ltp * t.optDouble("trail", 1.0) / 100)
                        val exitSide = if (d == 1) "SELL" else "BUY"
                        if (d * (ltp - entry) > 0) sl2 = if (d == 1) maxOf(sl2, hi - td) else minOf(sl2, hi + td)
                        fun fin(n: Int, why: String) {
                            val oid = api.order(t.getString("sym"), tok, "NSE", exitSide, n, t.getString("product"), ltp)
                            add("$why ${t.getString("sym")} x$n @~%.2f -> $oid".format(ltp))
                        }
                        fun reached(x: Double) = d * (ltp - x) >= 0
                        val cut = eod && t.getString("product") == "INTRADAY"
                        if ((if (d == 1) ltp <= sl2 else ltp >= sl2) || cut) { fin(qty, if (cut) "EOD EXIT" else "STOP/TRAIL"); Trades.remove(s, t.getString("id")); continue }
                        if (stage == 0 && reached(t.getDouble("t1")) && qty > part) { fin(part, "BOOK T1"); qty -= part; stage = 1; sl2 = if (d == 1) maxOf(sl2, entry) else minOf(sl2, entry) }
                        if (stage == 1 && reached(t.getDouble("t2")) && qty > part) { fin(part, "BOOK T2"); qty -= part; stage = 2 }
                        if (reached(t.getDouble("t3"))) { fin(qty, "BOOK T3"); Trades.remove(s, t.getString("id")); continue }
                        t.put("sl", sl2).put("high", hi).put("stage", stage).put("qty", qty); Trades.put(s, t)
                    } catch (e: AuthException) { throw e } catch (e: Exception) { add("Trade error: ${e.message}") }
                }
                // 4) live CSV/XLS in Downloads/AutoSquare
                if (s.get("live") == "1" && System.currentTimeMillis() - lastLive > 10_000) {
                    lastLive = System.currentTimeMillis()
                    try { Live.update(this, api, s) } catch (e: AuthException) { throw e } catch (e: Exception) { add("File error: ${e.message}") }
                }
            } catch (e: AuthException) { add("Auth expired, re-login") }
            catch (e: InterruptedException) { break }
            catch (e: Exception) { add("Error: ${e.message}"); try { Thread.sleep(20_000) } catch (x: InterruptedException) { break } }
            try { Thread.sleep(((s.get("sec", "5").toLongOrNull() ?: 5L).coerceAtLeast(2)) * 1000) } catch (e: InterruptedException) { break }
        }
        add("Stopped")
    }

    override fun onDestroy() { running = false; t?.interrupt(); wl?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
