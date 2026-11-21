package com.example.angelwatch

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Instrument lists from Angel One's public scrip master:
 *  - NSE equities (symbol -> token)
 *  - F&O of equities and indices (NFO, and BFO for SENSEX/BANKEX) and MCX futures/options, unexpired only.
 */
object Instruments {
    private const val URL = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
    private const val MAX_AGE_MS = 3 * 86_400_000L
    private val EXPIRY_FMT: DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dMMMyyyy").toFormatter(Locale.ENGLISH)

    val ready = MutableStateFlow(false)
    @Volatile var all: List<Instrument> = emptyList()
    @Volatile var deriv: List<Instrument> = emptyList()
    @Volatile private var bySym: Map<String, Instrument> = emptyMap()
    @Volatile private var derivBySym: Map<String, Instrument> = emptyMap()
    @Volatile private var byTok: Map<String, Instrument> = emptyMap()
    /** NSE index rows (AMXIDX) keyed by name with spaces/punctuation removed, e.g. "NIFTYSMLCAP100" -> token. */
    @Volatile private var idxTok: Map<String, String> = emptyMap()

    /** Token of an NSE index; [names] are alternative spellings in Angel's master (spaces ignored). */
    fun indexToken(vararg names: String): String? {
        for (n in names) idxTok[n.uppercase().filter { it.isLetterOrDigit() }]?.let { return it }
        return null
    }

    fun get(sym: String): Instrument? = bySym[sym] ?: derivBySym[sym]
    fun byToken(tok: String): Instrument? = byTok[tok]

    /**
     * seg: ALL | EQ | FNO | MCX.
     * Derivatives match when every space-separated word appears in the contract symbol,
     * e.g. "nifty 26000 ce", "reliance fut", "gold dec", "crudeoil".
     */
    fun search(q: String, seg: String = "ALL"): List<Instrument> {
        val u = q.trim().uppercase()
        if (u.isEmpty()) return emptyList()
        val out = ArrayList<Instrument>()
        if (seg == "ALL" || seg == "EQ") {
            val a = all.filter { it.symbol.startsWith(u) }
            val b = all.filter { !it.symbol.startsWith(u) && (it.symbol.contains(u) || it.name.uppercase().contains(u)) }
            out.addAll((a + b).take(40))
        }
        if (seg != "EQ") {
            val words = u.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isNotEmpty()) {
                val hits = ArrayList<Instrument>()
                for (d in deriv) {   // already sorted by expiry, then strike
                    if (seg == "FNO" && d.exch == "MCX") continue
                    if (seg == "MCX" && d.exch != "MCX") continue
                    if (words.all { d.symbol.contains(it) }) {
                        hits.add(d)
                        if (hits.size >= 4000) break
                    }
                }
                hits.sortWith(compareBy<Instrument>(
                    { if (it.name.uppercase() == words[0]) 0 else 1 },
                    { if (it.isOption) 1 else 0 },
                    { it.expiry },
                    { it.strike }
                ))
                out.addAll(hits.take(if (seg == "ALL") 25 else 60))
            }
        }
        return out
    }

    @Synchronized
    fun load(ctx: Context, force: Boolean = false) {
        if (ready.value && !force) return
        val f = File(ctx.filesDir, "nse_eq.tsv")
        val d = File(ctx.filesDir, "fno_mcx.tsv")
        val ix = File(ctx.filesDir, "nse_idx.tsv")
        val stale = !f.exists() || !d.exists() || !ix.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS
        if (force || stale) {
            try { download(f, d, ix) } catch (e: Exception) { if (!f.exists()) throw e }
        }
        val list = ArrayList<Instrument>()
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 3) list.add(Instrument(p[0], p[1], p[2]))
        }
        val im = HashMap<String, String>()
        if (ix.exists()) ix.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 2) {
                im[p[0].uppercase().filter { it.isLetterOrDigit() }] = p[1]
                if (p.size >= 3) im[p[2].uppercase().filter { it.isLetterOrDigit() }] = p[1]
            }
        }
        idxTok = im
        all = list.sortedBy { it.symbol }
        bySym = list.associateBy { it.symbol }
        byTok = list.associateBy { it.token }

        val today = LocalDate.now()
        val dl = ArrayList<Instrument>()
        if (d.exists()) d.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 9) {
                val e = try { LocalDate.parse(p[5]) } catch (x: Exception) { null }
                if (e != null && !e.isBefore(today)) {
                    dl.add(Instrument(p[1], p[2], p[3], exch = p[0], type = p[4], expiry = e,
                        strike = p[6].toDoubleOrNull() ?: 0.0, lot = p[7].toIntOrNull() ?: 1, tick = p[8].toDoubleOrNull() ?: 0.05))
                }
            }
        }
        dl.sortWith(compareBy<Instrument>({ it.expiry }, { it.strike }))
        deriv = dl
        derivBySym = dl.associateBy { it.symbol }
        ready.value = true
    }

    private fun JsonReader.str(): String =
        if (peek() == JsonToken.NULL) { nextNull(); "" } else nextString()

    private fun parseExpiry(s: String): LocalDate? =
        try { if (s.isBlank()) null else LocalDate.parse(s.trim(), EXPIRY_FMT) } catch (e: Exception) { null }

    private fun download(eqFile: File, dFile: File, ixFile: File) {
        val client = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Instrument list download failed (HTTP ${r.code})")
            val tmpEq = File(eqFile.path + ".tmp")
            val tmpD = File(dFile.path + ".tmp")
            val tmpIx = File(ixFile.path + ".tmp")
            val today = LocalDate.now()
            JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { jr ->
                tmpEq.bufferedWriter().use { eq ->
                  tmpIx.bufferedWriter().use { iw ->
                    tmpD.bufferedWriter().use { dw ->
                        jr.beginArray()
                        while (jr.hasNext()) {
                            var tok = ""; var sym = ""; var name = ""; var seg = ""
                            var exp = ""; var strike = ""; var lot = ""; var itype = ""; var tick = ""
                            jr.beginObject()
                            while (jr.hasNext()) {
                                when (jr.nextName()) {
                                    "token" -> tok = jr.str()
                                    "symbol" -> sym = jr.str()
                                    "name" -> name = jr.str()
                                    "exch_seg" -> seg = jr.str()
                                    "expiry" -> exp = jr.str()
                                    "strike" -> strike = jr.str()
                                    "lotsize" -> lot = jr.str()
                                    "instrumenttype" -> itype = jr.str()
                                    "tick_size" -> tick = jr.str()
                                    else -> jr.skipValue()
                                }
                            }
                            jr.endObject()
                            if (seg == "NSE" && itype == "AMXIDX") {
                                iw.write(sym + "\t" + tok + "\t" + name + "\n")
                            } else if (seg == "NSE" && sym.endsWith("-EQ")) {
                                eq.write(sym.removeSuffix("-EQ") + "\t" + tok + "\t" + name + "\n")
                            } else if ((seg == "NFO" || seg == "BFO" || seg == "MCX") &&
                                (itype.startsWith("FUT") || itype.startsWith("OPT"))) {
                                val e = parseExpiry(exp)
                                if (e != null && !e.isBefore(today)) {
                                    val k = if (itype.startsWith("OPT")) ((strike.toDoubleOrNull() ?: 0.0) / 100.0) else 0.0
                                    val t = (tick.toDoubleOrNull() ?: 5.0) / 100.0
                                    dw.write(listOf(seg, sym, tok, name, itype, e.toString(), k.toString(),
                                        (lot.toIntOrNull() ?: 1).toString(), t.toString()).joinToString("\t") + "\n")
                                }
                            }
                        }
                        jr.endArray()
                    }
                  }
                }
            }
            tmpIx.renameTo(ixFile)
            tmpEq.renameTo(eqFile)
            tmpD.renameTo(dFile)
        }
    }
}
