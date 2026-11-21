package com.example.angelwatch

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.sqrt

data class Pivot(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

data class Analysis(
    val ltp: Double,
    val ath: Double, val athDate: LocalDate?, val atl: Double, val atlDate: LocalDate?,
    val high52: Double, val low52: Double,
    val prevHigh: Double, val prevLow: Double, val prevClose: Double,
    val sma20: Double?, val sma50: Double?, val sma200: Double?,
    val daily: Pivot, val weekly: Pivot?, val monthly: Pivot?,
    val fib: List<Pair<String, Double>>, val fibSupport: Double?, val fibResistance: Double?,
    val sqrtRes: List<Double>, val sqrtSup: List<Double>,
    val score: Int, val reasons: List<Pair<Boolean, String>>
)

fun marketOpen(): Boolean {
    val n = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
    if (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) return false
    val t = n.toLocalTime()
    return t >= LocalTime.of(9, 15) && t <= LocalTime.of(15, 30)
}

/** MCX trades Mon-Fri, 09:00 to about 23:30 IST. */
fun mcxOpen(): Boolean {
    val n = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
    if (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) return false
    val t = n.toLocalTime()
    return t >= LocalTime.of(9, 0) && t <= LocalTime.of(23, 30)
}

object Analyzer {
    fun pivot(h: Double, l: Double, c: Double): Pivot {
        val p = (h + l + c) / 3
        return Pivot(p, 2 * p - l, p + (h - l), h + 2 * (p - l), 2 * p - h, p - (h - l), l - 2 * (h - p))
    }

    private fun prevPeriod(done: List<Candle>, current: LocalDate, key: (LocalDate) -> LocalDate): Pivot? {
        val g = done.filter { key(it.date) < current }.groupBy { key(it.date) }
        val k = g.keys.maxOrNull() ?: return null
        val c = g.getValue(k)
        return pivot(c.maxOf { it.h }, c.minOf { it.l }, c.last().c)
    }

    fun analyze(all: List<Candle>, ltp: Double): Analysis {
        val today = LocalDate.now()
        // While the market is open today's candle is incomplete, so leave it out.
        val done = (if (marketOpen()) all.filter { it.date < today } else all).ifEmpty { all }

        val hiC = all.maxByOrNull { it.h }!!
        val loC = all.minByOrNull { it.l }!!
        var ath = hiC.h; var athD: LocalDate? = hiC.date
        var atl = loC.l; var atlD: LocalDate? = loC.date
        if (ltp > ath) { ath = ltp; athD = today }
        if (ltp < atl) { atl = ltp; atlD = today }

        val year = all.filter { it.date >= today.minusDays(365) }.ifEmpty { all }
        val h52 = maxOf(year.maxOf { it.h }, ltp)
        val l52 = minOf(year.minOf { it.l }, ltp)

        val prev = done.last()
        fun sma(n: Int): Double? = if (done.size >= n) done.takeLast(n).map { it.c }.average() else null
        val s20 = sma(20); val s50 = sma(50); val s200 = sma(200)

        val daily = pivot(prev.h, prev.l, prev.c)
        val weekly = prevPeriod(done, today.with(DayOfWeek.MONDAY)) { it.with(DayOfWeek.MONDAY) }
        val monthly = prevPeriod(done, today.withDayOfMonth(1)) { it.withDayOfMonth(1) }

        // Fibonacci levels between all-time low and all-time high
        val ratios = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0, 1.272, 1.618)
        val fib = ratios.map { r ->
            val name = when (r) {
                0.0 -> "ATL (0%)"
                1.0 -> "ATH (100%)"
                else -> "Fib " + String.format(Locale.US, "%.1f", r * 100) + "%"
            }
            name to (atl + (ath - atl) * r)
        }
        val fibSup = fib.map { it.second }.filter { it < ltp }.maxOrNull()
        val fibRes = fib.map { it.second }.filter { it > ltp }.minOrNull()

        // Square-root (Gann style) levels around the current price
        val s = sqrt(ltp)
        val sqrtRes = (1..4).map { n -> val x = s + 0.25 * n; x * x }
        val sqrtSup = (1..4).map { n -> s - 0.25 * n }.filter { it > 0 }.map { x -> x * x }

        // Buy score (0-100)
        var score = 0
        val reasons = ArrayList<Pair<Boolean, String>>()
        fun chk(ok: Boolean?, w: Int, good: String, bad: String) {
            if (ok == true) { score += w; reasons.add(true to good) } else reasons.add(false to bad)
        }
        chk(s20?.let { ltp > it }, 10, "Above SMA 20", "Below SMA 20")
        chk(s50?.let { ltp > it }, 10, "Above SMA 50", "Below SMA 50")
        chk(s200?.let { ltp > it }, 20, "Above SMA 200", "Below SMA 200 (or not enough data)")
        chk(if (s20 != null && s50 != null) s20 > s50 else null, 10, "SMA 20 above SMA 50", "SMA 20 below SMA 50")
        chk(if (s50 != null && s200 != null) s50 > s200 else null, 10, "SMA 50 above SMA 200", "SMA 50 below SMA 200")
        chk(ltp > daily.p, 10, "Above daily pivot", "Below daily pivot")
        chk(weekly?.let { ltp > it.p }, 10, "Above weekly pivot", "Below weekly pivot")
        chk(monthly?.let { ltp > it.p }, 10, "Above monthly pivot", "Below monthly pivot")
        val pos = if (h52 > l52) (ltp - l52) / (h52 - l52) else 0.5
        val pct = (pos * 100).toInt()
        chk(pos in 0.3..0.9, 10, "Healthy spot in 52-week range ($pct%)", "Stretched in 52-week range ($pct%)")

        return Analysis(ltp, ath, athD, atl, atlD, h52, l52, prev.h, prev.l, prev.c, s20, s50, s200,
            daily, weekly, monthly, fib, fibSup, fibRes, sqrtRes, sqrtSup, score, reasons)
    }
}
