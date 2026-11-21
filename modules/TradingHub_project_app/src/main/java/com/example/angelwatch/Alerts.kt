package com.example.angelwatch

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class AlertItem(val time: Long, val symbol: String, val kind: String, val portfolio: Boolean, val msg: String)
data class Lvl(val name: String, val spoken: String, val v: Double)
private data class LevelSet(val day: LocalDate, val levels: List<Lvl>, val failedAt: Long = 0)

/** Support-breach / near-resistance alerts for portfolio (voice) and watchlist (silent) stocks. */
object Alerts {
    private const val NEAR_PCT = 1.0
    val items = MutableStateFlow<List<AlertItem>>(emptyList())

    /** When false nothing is evaluated: no new alerts, no voice, no notifications. */
    val enabled = MutableStateFlow(true)

    fun setEnabled(on: Boolean) {
        Store.alertsOn = on
        enabled.value = on
    }
    private val levels = ConcurrentHashMap<String, LevelSet>()
    private val seen = HashSet<String>()
    private var seenDay = ""
    private var inited = false

    @Synchronized
    fun init() {
        if (inited) return
        inited = true
        enabled.value = Store.alertsOn
        runCatching {
            val a = JSONArray(Store.getRaw("alerts") ?: "[]")
            val out = ArrayList<AlertItem>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(AlertItem(o.getLong("t"), o.getString("s"), o.getString("k"), o.getBoolean("p"), o.getString("m")))
            }
            items.value = out
        }
        runCatching {
            val o = JSONObject(Store.getRaw("seen") ?: "{}")
            seenDay = o.optString("d")
            val k = o.optJSONArray("k") ?: JSONArray()
            for (i in 0 until k.length()) seen.add(k.getString(i))
        }
    }

    private fun saveItems() {
        val a = JSONArray()
        items.value.forEach { a.put(JSONObject().put("t", it.time).put("s", it.symbol).put("k", it.kind).put("p", it.portfolio).put("m", it.msg)) }
        Store.putRaw("alerts", a.toString())
    }

    private fun saveSeen() {
        Store.putRaw("seen", JSONObject().put("d", seenDay).put("k", JSONArray(seen.toList())).toString())
    }

    @Synchronized
    fun clear() { items.value = emptyList(); saveItems() }

    private fun watched(): List<String> =
        (Repo.holdings.value.filter { it.qty > 0 }.map { it.symbol } + Store.lists.value.flatMap { it.symbols }).distinct()

    private fun levelsOf(a: Analysis): List<Lvl> {
        val out = ArrayList<Lvl>()
        fun piv(tag: String, p: Pivot?) {
            if (p == null) return
            out.add(Lvl("$tag pivot", "$tag pivot", p.p))
            listOf(1 to p.s1, 2 to p.s2, 3 to p.s3).forEach { (n, v) -> out.add(Lvl("$tag S$n", "$tag support $n", v)) }
            listOf(1 to p.r1, 2 to p.r2, 3 to p.r3).forEach { (n, v) -> out.add(Lvl("$tag R$n", "$tag resistance $n", v)) }
        }
        piv("Daily", a.daily); piv("Weekly", a.weekly); piv("Monthly", a.monthly)
        a.fib.forEach { (n, v) -> out.add(Lvl(n, n.replace("%", " percent"), v)) }
        a.sma50?.let { out.add(Lvl("SMA 50", "SMA 50", it)) }
        a.sma200?.let { out.add(Lvl("SMA 200", "SMA 200", it)) }
        return out.filter { it.v > 0 }
    }

    /** Computes levels for a few stocks per call (history download is rate limited). */
    fun prepare(max: Int) {
        val today = LocalDate.now()
        var done = 0
        for (s in watched()) {
            if (done >= max) break
            val ls = levels[s]
            if (ls != null && ls.day == today && (ls.levels.isNotEmpty() || System.currentTimeMillis() - ls.failedAt < 1_800_000)) continue
            if (Instruments.get(s)?.isOption == true) continue   // option premiums: no support/resistance alerts
            val token = Instruments.get(s)?.token ?: Repo.holdings.value.find { it.symbol == s }?.token ?: continue
            levels[s] = try {
                val c = Repo.candlesFor(s, token)
                val ltp = Repo.quotes.value[s]?.ltp?.takeIf { it > 0 } ?: c.last().c
                val a = Analyzer.analyze(c, ltp)
                Repo.putPivots(s, a)
                LevelSet(today, levelsOf(a))
            } catch (e: Exception) {
                LevelSet(today, emptyList(), System.currentTimeMillis())
            }
            done++
        }
    }

    @Synchronized
    fun evaluate(speak: (String) -> Unit, notify: (AlertItem) -> Unit) {
        if (!enabled.value || !marketOpen()) return
        val day = LocalDate.now().toString()
        if (seenDay != day) { seen.clear(); seenDay = day }
        val held = Repo.holdings.value.filter { it.qty > 0 }.map { it.symbol }.toSet()
        val quotes = Repo.quotes.value
        for (s in watched()) {
            val q = quotes[s] ?: continue
            val ls = levels[s]?.levels ?: continue
            if (ls.isEmpty() || q.ltp <= 0 || q.close <= 0) continue
            val ltp = q.ltp
            val prev = q.close
            val portfolio = s in held

            // Support broken today: price was at/above the level at yesterday's close and is now below it
            ls.filter { prev >= it.v && ltp < it.v }.minByOrNull { it.v }?.let { lv ->
                val key = "$s|B|${lv.name}"
                if (seen.add(key)) {
                    val msg = "Support broken: fell below ${lv.name} ₹${f2(lv.v)} (now ₹${f2(ltp)}, prev close ₹${f2(prev)}). Check this stock."
                    val voice = "Alert. $s. Support broken. Price fell below ${lv.spoken} at rupees ${f2(lv.v)}. Current price rupees ${f2(ltp)}."
                    post(AlertItem(System.currentTimeMillis(), s, "BREACH", portfolio, msg), voice, speak, notify)
                }
            }

            // Within NEAR_PCT below a resistance level
            ls.filter { it.v > ltp && (it.v - ltp) / it.v * 100 <= NEAR_PCT }.minByOrNull { it.v }?.let { lv ->
                val key = "$s|N|${lv.name}"
                if (seen.add(key)) {
                    val gap = String.format("%.2f", (lv.v - ltp) / lv.v * 100)
                    val msg = "Near resistance: ${lv.name} ₹${f2(lv.v)} is $gap% above (now ₹${f2(ltp)}). Check this stock."
                    val voice = "Alert. $s. Near resistance. Price is $gap percent below ${lv.spoken} at rupees ${f2(lv.v)}. Current price rupees ${f2(ltp)}."
                    post(AlertItem(System.currentTimeMillis(), s, "NEAR", portfolio, msg), voice, speak, notify)
                }
            }
        }
        saveSeen()
    }

    /** loud = speak + notification. Portfolio alerts are loud by default; watchlist support/resistance alerts stay silent. */
    fun post(a: AlertItem, voice: String, speak: (String) -> Unit, notify: (AlertItem) -> Unit, loud: Boolean = a.portfolio) {
        items.value = (listOf(a) + items.value).take(100)
        saveItems()
        if (loud) { speak(voice); notify(a) }
    }
}
