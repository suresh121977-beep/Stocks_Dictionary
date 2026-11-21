package com.example.angelwatch

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Quote(val symbol: String, val token: String, val ltp: Double, val change: Double, val pct: Double, val close: Double)
data class Holding(val symbol: String, val token: String, val qty: Int, val avg: Double, val ltp: Double, val pnl: Double, val pnlPct: Double)
data class OrderRow(val symbol: String, val side: String, val qty: String, val price: String, val status: String, val type: String, val time: String)
/**
 * A tradable instrument. exch = NSE (equity), NFO / BFO (equity + index F&O) or MCX (commodities).
 * For derivatives: type is Angel's instrumenttype (FUTIDX, FUTSTK, OPTIDX, OPTSTK, FUTCOM, OPTFUT ...),
 * lot is the lot size, tick the minimum price step in rupees.
 */
data class Instrument(
    val symbol: String, val token: String, val name: String,
    val exch: String = "NSE", val type: String = "", val expiry: LocalDate? = null,
    val strike: Double = 0.0, val lot: Int = 1, val tick: Double = 0.05
) {
    val isDeriv: Boolean get() = exch != "NSE"
    val isOption: Boolean get() = type.startsWith("OPT")

    /** Readable contract name, e.g. "NIFTY 25 NOV 2025 26000 CE". */
    fun describe(): String {
        if (!isDeriv) return name
        val e = expiry?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)) ?: ""
        if (!isOption) return "$name $e FUT"
        val k = if (strike == Math.floor(strike)) strike.toLong().toString() else strike.toString()
        return "$name $e $k ${if (symbol.endsWith("PE")) "PE" else "CE"}"
    }

    /** One line under the symbol in lists. */
    fun subtitle(): String = if (!isDeriv) "NSE" else "$exch • ${describe()} • lot $lot"
}
/** One 5-minute candle (time is exchange local time). */
data class Bar(val t: LocalDateTime, val h: Double, val l: Double, val c: Double)
data class Candle(val date: LocalDate, val o: Double, val h: Double, val l: Double, val c: Double)
data class WatchList(val name: String, val symbols: List<String>)
/** Daily / weekly / monthly pivot (P) levels for one stock, valid for [day]. */
data class PivotSet(val day: LocalDate, val d: Double, val w: Double?, val m: Double?)
data class Creds(val apiKey: String, val client: String, val pin: String, val totp: String)
