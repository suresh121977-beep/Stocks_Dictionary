package com.example.autosquare
import org.json.JSONObject

/** Trade plan rules. kind 0 = intraday BUY, 1 = positional BUY, 2 = intraday SELL (short). */
object Plan {
    class P(val side: String, val product: String, val sl: Double, val t: List<Double>, val trailD: Double, val note: String, val err: String?, val near: String = "")

    private fun f(d: Double) = String.format("%.2f", d)

    /** Raw pivot targets: Camarilla R2/R3/R4 (buy), S2/S3/S4 (sell), weekly R2/R3 (positional). */
    fun targets(lv: JSONObject, ltp: Double, kind: Int): List<Double> {
        if (kind == 2) {
            val t = listOf("s2", "s3", "s4").map { lv.optDouble(it, 0.0) }.filter { it > 0 && it < ltp }.sortedDescending().toMutableList()
            var k = 1; while (t.size < 3) { t.add(ltp * (1 - 0.01 * k)); k++ }
            return t.sortedDescending()
        }
        val keys = if (kind == 0) listOf("c2", "c3", "c4") else listOf("wr1", "wr2", "wr3")
        val t = keys.map { lv.getDouble(it) }.filter { it > ltp }.sorted().toMutableList()
        var k = 1; while (t.size < 3) { t.add(ltp * (1 + 0.01 * k)); k++ }
        return t.sorted()
    }

    /** Every level we know: 52W/prev-day/weekly/SMA, fib, daily pivots, square-root and Gann levels (up and down). */
    private fun everyLevel(lv: JSONObject, ltp: Double): List<Double> =
        Calc.levels(lv).map { it.second } + listOf(lv.getDouble("dp"), lv.getDouble("dr1"), lv.getDouble("ds1")) +
            Calc.sq(ltp).map { it.second } + Calc.sqDown(ltp).map { it.second }

    /** Pull a target in front of any fib/Gann/square-root/major level sitting between the previous target and it. */
    private fun snap(t: List<Double>, lv: List<Double>, ltp: Double, atr: Double, long: Boolean): List<Double> {
        val out = ArrayList<Double>(); var floor = if (long) ltp + 0.3 * atr else ltp - 0.3 * atr
        for (x in t) {
            var y = x
            val between = lv.filter { if (long) it > floor && it < x else it < floor && it > x }
            if (between.isNotEmpty()) {
                val l = if (long) between.min() else between.max()
                y = if (long) l * 0.998 else l * 1.002
                if (if (long) y <= floor else y >= floor) y = x
            }
            out.add(y); floor = if (long) y * 1.002 else y * 0.998
        }
        return if (long) out.sorted() else out.sortedDescending()
    }

    fun make(lv: JSONObject, ltp: Double, kind: Int): P {
        val atr = lv.optDouble("atr", 0.0)
        val side = if (kind == 2) "SELL" else "BUY"; val prod = if (kind == 1) "DELIVERY" else "INTRADAY"
        val raw = targets(lv, ltp, kind)
        fun bad(m: String) = P(side, prod, 0.0, raw, 0.0, "", m)
        if (atr <= 0) return bad("ATR not available")
        val long = kind != 2; val minRisk = 0.2 * atr
        val major = Calc.levels(lv) + listOf("Daily R1" to lv.getDouble("dr1"), "Daily S1" to lv.getDouble("ds1"))
        val t = snap(raw, everyLevel(lv, ltp), ltp, atr, long)
        val note: String; val sl: Double; val trail: Double
        when (kind) {
            0 -> {
                val dp = lv.getDouble("dp"); val r1 = lv.getDouble("dr1")
                if (ltp <= dp) return bad("Price below daily pivot ${f(dp)}")
                val above = ltp >= r1
                sl = minOf(if (above) r1 else dp, ltp - minRisk); val risk = ltp - sl
                if (risk > atr) return bad("Stop too far (${f(risk)} > 1 ATR ${f(atr)})")
                if (!above && r1 - ltp < 1.5 * risk) return bad("Too close to R1 ${f(r1)}")
                note = if (above) "Above R1: stop below R1 ${f(r1)}" else "Above pivot: stop below pivot ${f(dp)}"; trail = 0.5 * atr
            }
            2 -> {
                val dp = lv.getDouble("dp"); val s1 = lv.getDouble("ds1")
                if (ltp >= dp) return bad("Price above daily pivot ${f(dp)}")
                val below = ltp <= s1
                sl = maxOf(if (below) s1 else dp, ltp + minRisk); val risk = sl - ltp
                if (risk > atr) return bad("Stop too far (${f(risk)} > 1 ATR ${f(atr)})")
                if (!below && ltp - s1 < 1.5 * risk) return bad("Too close to S1 ${f(s1)}")
                note = if (below) "Below S1: stop above S1 ${f(s1)}" else "Below pivot: stop above pivot ${f(dp)}"; trail = 0.5 * atr
            }
            else -> {
                val wp = lv.getDouble("wp")
                if (ltp <= wp) return bad("Price below weekly pivot ${f(wp)}")
                sl = minOf(wp, ltp - minRisk)
                if (ltp - sl > 2 * atr) return bad("Weekly pivot too far (${f(ltp - sl)} > 2 ATR)")
                note = "Stop below weekly pivot ${f(wp)}"; trail = 2 * atr
            }
        }
        val risk = Math.abs(ltp - sl)
        // nearest major level (52W, weekly, SMA, fib, daily pivots) in the trade direction must leave room >= 1x risk
        val mj = if (long) major.filter { it.second > ltp }.minByOrNull { it.second } else major.filter { it.second < ltp }.maxByOrNull { it.second }
        if (mj != null && Math.abs(mj.second - ltp) < risk) return bad("${mj.first} ${f(mj.second)} too close (room < risk)")
        val near = mj?.let { "Nearest ${if (long) "resistance" else "support"}: ${it.first} ${f(it.second)} (${f(Math.abs(it.second - ltp) / ltp * 100)}% away)" } ?: ""
        return P(side, prod, sl, t, trail, "$note. Targets trimmed ahead of fib/Gann/√ levels.", null, near)
    }
}
