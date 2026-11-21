package com.example.angelwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** [voice] = gives spoken "cautious move" alerts near pivot / support / resistance. */
data class IndexDef(val key: String, val label: String, val names: List<String>, val fallbackToken: String?, val voice: Boolean) {
    val token: String? get() = Instruments.indexToken(*names.toTypedArray()) ?: fallbackToken
}

val INDEX_DEFS = listOf(
    IndexDef("NIFTY50", "Nifty 50", listOf("Nifty 50", "NIFTY"), "99926000", true),
    IndexDef("NIFTY500", "Nifty 500", listOf("Nifty 500"), null, true),
    IndexDef("SMALLCAP100", "Nifty Smallcap 100", listOf("NIFTY SMLCAP 100", "NIFTY SMALLCAP 100"), null, true),
    IndexDef("MIDCAP150", "Nifty Midcap 150", listOf("NIFTY MIDCAP 150", "NIFTY MIDCAP150"), null, false)
)

fun indexDef(key: String) = INDEX_DEFS.firstOrNull { it.key == key }

/**
 * Four fixed indices: live quote, previous-day high/low and daily / weekly / monthly pivots.
 * Voice alerts only for Nifty 50, Nifty 500 and Smallcap 100 (Midcap 150 is display only).
 */
object IndexWatch {
    private const val NEAR_PCT = 0.25   // indices move slowly, so the "near" band is tighter than for stocks

    val quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    private val analysis = ConcurrentHashMap<String, Pair<Long, Analysis>>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val seen = HashSet<String>()
    private var seenDay = ""
    private var inited = false

    @Synchronized
    private fun init() {
        if (inited) return
        inited = true
        runCatching {
            val o = JSONObject(Store.getRaw("idxSeen") ?: "{}")
            seenDay = o.optString("d")
            val k = o.optJSONArray("k") ?: JSONArray()
            for (i in 0 until k.length()) seen.add(k.getString(i))
        }
    }

    private fun saveSeen() {
        Store.putRaw("idxSeen", JSONObject().put("d", seenDay).put("k", JSONArray(seen.toList())).toString())
    }

    fun tick() {
        val items = INDEX_DEFS.mapNotNull { d -> d.token?.let { "NSE" to it } }
        if (items.isEmpty()) return
        val byTok = INDEX_DEFS.mapNotNull { d -> d.token?.let { it to d.key } }.toMap()
        val out = HashMap<String, Quote>()
        for (o in Api.quotes(items)) {
            val t = o.optString("symbolToken")
            val k = byTok[t] ?: continue
            out[k] = Quote(k, t, o.optDouble("ltp", 0.0), o.optDouble("netChange", 0.0),
                o.optDouble("percentChange", 0.0), o.optDouble("close", 0.0))
        }
        if (out.isNotEmpty()) quotes.value = out
    }

    /** Loads daily history for one index per call (rate limited). Cached 30 minutes. */
    fun prepare() {
        val now = System.currentTimeMillis()
        for (d in INDEX_DEFS) {
            val token = d.token ?: continue
            val a = analysis[d.key]
            if (a != null && now - a.first < 1_800_000) continue
            val f = failed[d.key]
            if (f != null && now - f < 300_000) continue
            try {
                val c = Api.candles("NSE", token)
                if (c.isEmpty()) throw java.io.IOException("No history")
                val ltp = quotes.value[d.key]?.ltp?.takeIf { it > 0 } ?: c.last().c
                analysis[d.key] = now to Analyzer.analyze(c, ltp)
            } catch (e: Exception) {
                failed[d.key] = now
            }
            return
        }
    }

    /** Fresh analysis for the detail screen (uses the cache when recent). */
    fun load(d: IndexDef): Analysis {
        val token = requireNotNull(d.token) { "${d.label} not found in the instrument list. Account > Refresh list." }
        val now = System.currentTimeMillis()
        analysis[d.key]?.let { if (now - it.first < 1_800_000) return it.second }
        val c = Api.candles("NSE", token)
        if (c.isEmpty()) throw java.io.IOException("No price history for ${d.label}")
        val ltp = quotes.value[d.key]?.ltp?.takeIf { it > 0 } ?: c.last().c
        val a = Analyzer.analyze(c, ltp)
        analysis[d.key] = now to a
        return a
    }

    private fun levels(a: Analysis): List<Lvl> {
        val out = ArrayList<Lvl>()
        fun piv(tag: String, p: Pivot?) {
            if (p == null) return
            out.add(Lvl("$tag pivot", "$tag pivot", p.p))
            listOf(1 to p.s1, 2 to p.s2, 3 to p.s3).forEach { (n, v) -> out.add(Lvl("$tag S$n", "$tag support $n", v)) }
            listOf(1 to p.r1, 2 to p.r2, 3 to p.r3).forEach { (n, v) -> out.add(Lvl("$tag R$n", "$tag resistance $n", v)) }
        }
        piv("Daily", a.daily); piv("Weekly", a.weekly); piv("Monthly", a.monthly)
        return out.filter { it.v > 0 }
    }

    private fun side(ltp: Double, p: Pivot?, tag: String): String? =
        if (p == null) null else "$tag pivot ${if (ltp >= p.p) "above" else "below"}"

    @Synchronized
    fun evaluate(speak: (String) -> Unit, notify: (AlertItem) -> Unit) {
        if (!Alerts.enabled.value || !marketOpen()) return
        init()
        val day = LocalDate.now().toString()
        if (seenDay != day) { seen.clear(); seenDay = day }
        for (d in INDEX_DEFS) {
            if (!d.voice) continue
            val q = quotes.value[d.key] ?: continue
            val a = analysis[d.key]?.second ?: continue
            if (q.ltp <= 0) continue
            val ltp = q.ltp
            val ls = levels(a)

            // Position against the three pivots, spoken as part of every caution
            val pos = listOfNotNull(side(ltp, a.daily, "daily"), side(ltp, a.weekly, "weekly"), side(ltp, a.monthly, "monthly"))
                .joinToString(", ") { it.replace(" pivot ", " pivot: ") }
            val posVoice = listOf("daily" to a.daily, "weekly" to a.weekly, "monthly" to a.monthly)
                .mapNotNull { (t, p) -> p?.let { "${if (ltp >= it.p) "above" else "below"} $t pivot" } }
                .joinToString(", ")

            // Nearest level within the near band, either side of price
            val near = ls.filter { abs(it.v - ltp) / ltp * 100 <= NEAR_PCT }.minByOrNull { abs(it.v - ltp) }
            if (near != null) {
                val key = "${d.key}|N|${near.name}"
                if (seen.add(key)) {
                    val isSupport = near.v <= ltp
                    val kind = if (isSupport) "support" else "resistance"
                    val msg = "Caution: ${d.label} is near ${near.name} ($kind) ₹${f2(near.v)} (now ₹${f2(ltp)}). $pos. Trade carefully."
                    val voice = "Caution. ${d.label} is near ${near.spoken}, $kind, at ${f2(near.v)}. Current ${f2(ltp)}. Price is $posVoice. Move carefully."
                    Alerts.post(AlertItem(System.currentTimeMillis(), d.label, "INDEX", true, msg), voice, speak, notify, loud = true)
                }
            }

            // Crossed a level since yesterday's close
            val prev = q.close
            if (prev > 0) {
                val cross = ls.filter { (prev >= it.v && ltp < it.v) || (prev <= it.v && ltp > it.v) }
                    .minByOrNull { abs(it.v - ltp) }
                if (cross != null) {
                    val down = ltp < cross.v
                    val key = "${d.key}|X|${cross.name}|${if (down) "D" else "U"}"
                    if (seen.add(key)) {
                        val msg = "Caution: ${d.label} moved ${if (down) "below" else "above"} ${cross.name} ₹${f2(cross.v)} (now ₹${f2(ltp)}). $pos."
                        val voice = "Caution. ${d.label} moved ${if (down) "below" else "above"} ${cross.spoken} at ${f2(cross.v)}. Current ${f2(ltp)}. Price is $posVoice. Move carefully."
                        Alerts.post(AlertItem(System.currentTimeMillis(), d.label, "INDEX", true, msg), voice, speak, notify, loud = true)
                    }
                }
            }
        }
        saveSeen()
    }
}

@Composable
fun IndicesScreen(open: (String) -> Unit) {
    val q by IndexWatch.quotes.collectAsState()
    val ready by Instruments.ready.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text("Indices", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
        Text("Tap an index for previous day high/low and daily, weekly, monthly pivots. Voice caution alerts: Nifty 50, Nifty 500, Smallcap 100.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        HorizontalDivider(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(INDEX_DEFS, key = { it.key }) { d ->
                val x = q[d.key]
                Row(Modifier.fillMaxWidth().clickable { open("IDX:" + d.key) }.padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(d.label, fontSize = 18.sp)
                        Text(if (d.voice) "Voice alerts ON" else "Display only", color = if (d.voice) Green else Muted, fontSize = 12.sp)
                        if (ready && d.token == null) Text("Not in instrument list – Account > Refresh list", color = Red, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (x == null) Text("—", color = Muted, fontSize = 18.sp) else {
                            Text(f2(x.ltp), color = chgColor(x.change), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(signedRs(x.change).replace("₹", "") + " (" + pctStr(x.pct) + ")", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
fun IndexDetailScreen(key: String, onBack: () -> Unit) {
    val d = indexDef(key)
    val quotes by IndexWatch.quotes.collectAsState()
    var a by remember { mutableStateOf<Analysis?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (d == null) { error = "Unknown index"; return@LaunchedEffect }
        withContext(Dispatchers.IO) { runCatching { IndexWatch.load(d) } }
            .onSuccess { a = it }.onFailure { error = it.message ?: "Failed" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(d?.label ?: key, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        val an = a
        when {
            error != null -> Text("Could not load: $error", Modifier.padding(16.dp), color = Red)
            an == null || d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val q = quotes[key]
                val ltp = q?.ltp?.takeIf { it > 0 } ?: an.ltp
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Section("Price") {
                            Text(f2(ltp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            q?.let { Text(signedRs(it.change).replace("₹", "") + " (" + pctStr(it.pct) + ")", color = chgColor(it.change)) }
                            Text(if (d.voice) "Voice caution alerts: ON" else "Display only (no alerts)",
                                color = if (d.voice) Green else Muted, fontSize = 13.sp)
                        }
                    }
                    item {
                        Section("Previous day") {
                            KV("High", f2(an.prevHigh), Green)
                            KV("Low", f2(an.prevLow), Red)
                            KV("Close", f2(an.prevClose))
                        }
                    }
                    item {
                        Section("Price vs pivots") {
                            listOf("Daily" to an.daily, "Weekly" to an.weekly, "Monthly" to an.monthly).forEach { (n, p) ->
                                if (p == null) KV(n, "not enough data", Muted)
                                else KV("$n pivot ${f2(p.p)}", if (ltp >= p.p) "▲ above" else "▼ below", if (ltp >= p.p) Green else Red)
                            }
                        }
                    }
                    item { PivotCard("Daily pivots", an.daily) }
                    item { PivotCard("Weekly pivots", an.weekly) }
                    item { PivotCard("Monthly pivots", an.monthly) }
                    item { Text("Levels are rough technical indicators, not investment advice.", color = Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}
