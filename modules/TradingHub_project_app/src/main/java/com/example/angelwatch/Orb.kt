package com.example.angelwatch

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * status: BUY / SELL (confirmed), WAIT_BUY / WAIT_SELL (broke the level, 20 SMA not confirming yet),
 * INSIDE (still inside the 30-min range), FORMING (range not finished), NODATA, CLOSED.
 */
data class OrbSignal(
    val status: String, val high: Double, val low: Double, val sma: Double, val lastClose: Double, val text: String,
    val piv: Pivot? = null, val ltp: Double = 0.0,
    /** BUY: R1, R2, R3. SELL: S1, S2, S3. */
    val targets: List<Double> = emptyList(), val stop: Double = 0.0
)

/**
 * Opening-range breakout on futures and options (NFO / BFO / MCX), checked on 5-minute candles:
 *  BUY  = price above the first-30-minute HIGH, last 5-min close and price above the 5-min 20 SMA, AND price above the daily pivot
 *         targets R1, R2, R3; stop-loss = the nearest pivot level below price (pivot, R1 or R2)
 *  SELL = price below the first-30-minute LOW, last 5-min close and price below the 5-min 20 SMA, AND price below the daily pivot
 *         targets S1, S2, S3; stop-loss = the nearest pivot level above price (pivot, S1 or S2)
 * Daily pivot = previous session's high / low / close (taken from the same 5-minute candles).
 * First 30 minutes = 09:15-09:45 (NFO/BFO) or 09:00-09:30 (MCX).
 */
object Orb {
    val signals = MutableStateFlow<Map<String, OrbSignal>>(emptyMap())
    private val bars = ConcurrentHashMap<String, Pair<Long, List<Bar>>>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val seen = HashSet<String>()
    private var seenDay = ""
    private var inited = false
    private val IST = ZoneId.of("Asia/Kolkata")

    @Synchronized
    private fun init() {
        if (inited) return
        inited = true
        runCatching {
            val o = JSONObject(Store.getRaw("orbSeen") ?: "{}")
            seenDay = o.optString("d")
            val k = o.optJSONArray("k") ?: JSONArray()
            for (i in 0 until k.length()) seen.add(k.getString(i))
        }
    }

    private fun saveSeen() {
        Store.putRaw("orbSeen", JSONObject().put("d", seenDay).put("k", JSONArray(seen.toList())).toString())
    }

    private fun watchedDerivs(): List<Instrument> =
        Store.lists.value.flatMap { it.symbols }.distinct().mapNotNull { Instruments.get(it) }.filter { it.isDeriv }

    private fun sessionOpen(i: Instrument) = if (i.exch == "MCX") mcxOpen() else marketOpen()
    private fun sessionStart(i: Instrument): LocalTime = if (i.exch == "MCX") LocalTime.of(9, 0) else LocalTime.of(9, 15)

    /** Downloads 5-minute candles for a few watched contracts per call (history calls are rate limited). */
    fun refresh(max: Int) {
        var done = 0
        val now = System.currentTimeMillis()
        for (i in watchedDerivs()) {
            if (done >= max) break
            if (!sessionOpen(i)) continue
            val c = bars[i.symbol]
            if (c != null && now - c.first < 60_000) continue
            val f = failed[i.symbol]
            if (f != null && now - f < 120_000) continue
            try {
                bars[i.symbol] = now to Api.candles5(i.exch, i.token)
            } catch (e: Exception) {
                failed[i.symbol] = now
            }
            done++
        }
    }

    private fun sig(status: String, text: String, hi: Double = 0.0, lo: Double = 0.0, sma: Double = 0.0, lc: Double = 0.0,
                    piv: Pivot? = null, ltp: Double = 0.0, targets: List<Double> = emptyList(), stop: Double = 0.0) =
        OrbSignal(status, hi, lo, sma, lc, text, piv, ltp, targets, stop)

    private fun compute(i: Instrument, ltp: Double, now: LocalDateTime): OrbSignal {
        if (!sessionOpen(i)) return sig("CLOSED", "Market closed")
        val b = bars[i.symbol]?.second ?: return sig("NODATA", "Loading 5-minute data…")
        val start = sessionStart(i)
        val end = start.plusMinutes(30)
        if (now.toLocalTime().isBefore(end)) return sig("FORMING", "First 30-minute range forms until $end")
        val today = now.toLocalDate()
        val range = b.filter { it.t.toLocalDate() == today && !it.t.toLocalTime().isBefore(start) && it.t.toLocalTime().isBefore(end) }
        if (range.isEmpty()) return sig("NODATA", "No candles found for the first 30 minutes")
        val hi = range.maxOf { it.h }
        val lo = range.minOf { it.l }
        val done = b.filter { !it.t.plusMinutes(5).isAfter(now) }
        if (done.size < 20) return sig("NODATA", "Needs 20 five-minute candles for the SMA", hi, lo)
        val sma = done.takeLast(20).map { it.c }.average()
        val lc = done.last().c
        // daily pivot from the previous session's candles
        val prevDay = b.map { it.t.toLocalDate() }.filter { it.isBefore(today) }.maxOrNull()
            ?: return sig("NODATA", "No previous-session candles for the daily pivot", hi, lo, sma, lc)
        val pb = b.filter { it.t.toLocalDate() == prevDay }
        val pv = Analyzer.pivot(pb.maxOf { it.h }, pb.minOf { it.l }, pb.last().c)
        if (ltp <= 0) return sig("NODATA", "Waiting for price", hi, lo, sma, lc, pv)

        fun mk(status: String, text: String, t: List<Double> = emptyList(), stop: Double = 0.0) =
            sig(status, text, hi, lo, sma, lc, pv, ltp, t, stop)

        return when {
            ltp > hi -> {
                val missing = ArrayList<String>()
                if (!(lc > sma && ltp > sma)) missing.add("close above 5-min 20 SMA ₹${f2(sma)}")
                if (ltp <= pv.p) missing.add("price above daily pivot ₹${f2(pv.p)}")
                if (missing.isEmpty()) {
                    val stop = listOf(pv.p, pv.r1, pv.r2).filter { it < ltp }.maxOrNull() ?: pv.p
                    mk("BUY", "BUY: above 30-min high ₹${f2(hi)}, above 5-min 20 SMA and above daily pivot ₹${f2(pv.p)}. " +
                        "Targets R1 ₹${f2(pv.r1)} / R2 ₹${f2(pv.r2)} / R3 ₹${f2(pv.r3)}. Stop-loss below ₹${f2(stop)}.",
                        listOf(pv.r1, pv.r2, pv.r3), stop)
                } else mk("WAIT_BUY", "Above 30-min high ₹${f2(hi)}, waiting for " + missing.joinToString(" and "))
            }
            ltp < lo -> {
                val missing = ArrayList<String>()
                if (!(lc < sma && ltp < sma)) missing.add("close below 5-min 20 SMA ₹${f2(sma)}")
                if (ltp >= pv.p) missing.add("price below daily pivot ₹${f2(pv.p)}")
                if (missing.isEmpty()) {
                    val stop = listOf(pv.p, pv.s1, pv.s2).filter { it > ltp }.minOrNull() ?: pv.p
                    mk("SELL", "SELL: below 30-min low ₹${f2(lo)}, below 5-min 20 SMA and below daily pivot ₹${f2(pv.p)}. " +
                        "Targets S1 ₹${f2(pv.s1)} / S2 ₹${f2(pv.s2)} / S3 ₹${f2(pv.s3)}. Stop-loss above ₹${f2(stop)}.",
                        listOf(pv.s1, pv.s2, pv.s3), stop)
                } else mk("WAIT_SELL", "Below 30-min low ₹${f2(lo)}, waiting for " + missing.joinToString(" and "))
            }
            else -> mk("INSIDE", "Inside the 30-min range ₹${f2(lo)} to ₹${f2(hi)}")
        }
    }

    /** Recomputes every watched contract from cached candles + live price, and raises an alert on a new confirmed signal. */
    @Synchronized
    fun update(speak: (String) -> Unit, notify: (AlertItem) -> Unit) {
        init()
        val now = ZonedDateTime.now(IST).toLocalDateTime()
        val day = now.toLocalDate().toString()
        if (seenDay != day) { seen.clear(); seenDay = day }
        val quotes = Repo.quotes.value
        val out = HashMap<String, OrbSignal>()
        var changed = false
        for (i in watchedDerivs()) {
            val s = compute(i, quotes[i.symbol]?.ltp ?: 0.0, now)
            out[i.symbol] = s
            if ((s.status == "BUY" || s.status == "SELL") && Alerts.enabled.value && seen.add(i.symbol + "|" + s.status)) {
                changed = true
                val ltp = quotes[i.symbol]?.ltp ?: 0.0
                val msg = s.text + " (now ₹${f2(ltp)}, last 5-min close ₹${f2(s.lastClose)})."
                val way = if (s.status == "BUY") "Buy" else "Sell"
                val voice = "Signal. ${i.describe()}. $way. " +
                    (if (s.status == "BUY") "Price broke above the first 30 minute high" else "Price broke below the first 30 minute low") +
                    ", the 5 minute 20 S M A confirms, and price is " + (if (s.status == "BUY") "above" else "below") +
                    " the daily pivot. First target rupees ${f2(s.targets.first())}."
                Alerts.post(AlertItem(System.currentTimeMillis(), i.symbol, "SIGNAL_" + s.status, false, msg), voice, speak, notify, loud = false)
            }
        }
        if (changed) saveSeen()
        signals.value = out
    }
}
