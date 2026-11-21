package com.example.autosquare
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

object Calc {
    fun today(): String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    private fun wk(d: LocalDate) = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    fun compute(c: JSONArray): JSONObject? {
        val today = today()
        val dd = ArrayList<String>(); val hh = ArrayList<Double>(); val ll = ArrayList<Double>(); val cc = ArrayList<Double>()
        for (i in 0 until c.length()) {
            val r = c.getJSONArray(i); val d = r.getString(0).substring(0, 10)
            if (d >= today) continue
            dd.add(d); hh.add(r.getDouble(2)); ll.add(r.getDouble(3)); cc.add(r.getDouble(4))
        }
        val n = cc.size
        if (n < 60) return null
        val w = minOf(252, n)
        fun sma(k: Int) = if (n >= k) cc.takeLast(k).average() else 0.0
        val weeks = LinkedHashMap<Int, DoubleArray>()
        for (i in 0 until n) {
            val a = weeks.getOrPut(wk(LocalDate.parse(dd[i]))) { doubleArrayOf(hh[i], ll[i], cc[i]) }
            a[0] = maxOf(a[0], hh[i]); a[1] = minOf(a[1], ll[i]); a[2] = cc[i]
        }
        val cur = wk(LocalDate.parse(today))
        val pk = weeks.keys.filter { it < cur }.maxOrNull() ?: return null
        val pw = weeks[pk]!!
        val p = (pw[0] + pw[1] + pw[2]) / 3
        val pdh = hh[n - 1]; val pdl = ll[n - 1]; val pdc = cc[n - 1]; val rg = pdh - pdl
        var tr = 0.0
        for (i in n - 14 until n) tr += maxOf(hh[i] - ll[i], Math.abs(hh[i] - cc[i - 1]), Math.abs(ll[i] - cc[i - 1]))
        val dp = (pdh + pdl + pdc) / 3
        return JSONObject().put("atr", tr / 14).put("dp", dp).put("dr1", 2 * dp - pdl).put("ds1", 2 * dp - pdh).put("wp", p)
            .put("s2", pdc - rg * 1.1 / 6).put("s3", pdc - rg * 1.1 / 4).put("s4", pdc - rg * 1.1 / 2)
            .put("h52", hh.takeLast(w).max()).put("l52", ll.takeLast(w).min())
            .put("pdh", pdh).put("pdl", pdl).put("pdc", pdc)
            .put("sma20", sma(20)).put("sma50", sma(50)).put("sma100", sma(100)).put("sma200", sma(200))
            .put("wr1", 2 * p - pw[1]).put("wr2", p + (pw[0] - pw[1])).put("wr3", pw[0] + 2 * (p - pw[1]))
            .put("c2", pdc + rg * 1.1 / 6).put("c3", pdc + rg * 1.1 / 4).put("c4", pdc + rg * 1.1 / 2)
    }

    fun fibs(h: Double, l: Double) = listOf(0.236, 0.382, 0.5, 0.618, 0.786).map { String.format("Fib %.1f%%", it * 100) to (h - (h - l) * it) }

    /** Square-root levels: next perfect square + Gann 45/90 degree levels above price. */
    fun sq(p: Double): List<Pair<String, Double>> {
        val s = Math.sqrt(p); var n = Math.ceil(s); if (n * n <= p) n += 1
        return listOf("Square ${n.toInt()}²" to n * n, "Gann 45°" to (s + 0.25) * (s + 0.25), "Gann 90°" to (s + 0.5) * (s + 0.5))
    }

    fun sqDown(p: Double): List<Pair<String, Double>> {
        val s = Math.sqrt(p); var n = Math.floor(s); if (n * n >= p) n -= 1
        return listOf("Square ${n.toInt()}²" to n * n, "Gann -45°" to (s - 0.25) * (s - 0.25), "Gann -90°" to (s - 0.5) * (s - 0.5))
    }

    fun levels(lv: JSONObject): List<Pair<String, Double>> {
        val l = ArrayList<Pair<String, Double>>()
        l += listOf("52W High" to lv.getDouble("h52"), "52W Low" to lv.getDouble("l52"), "Prev Day High" to lv.getDouble("pdh"),
            "Prev Day Low" to lv.getDouble("pdl"), "Weekly R1" to lv.getDouble("wr1"), "Weekly R2" to lv.getDouble("wr2"),
            "Weekly R3" to lv.getDouble("wr3"), "SMA 50" to lv.getDouble("sma50"), "SMA 200" to lv.getDouble("sma200"))
        l += fibs(lv.getDouble("h52"), lv.getDouble("l52"))
        return l.filter { it.second > 0 }
    }
}
