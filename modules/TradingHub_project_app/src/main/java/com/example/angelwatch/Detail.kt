package com.example.angelwatch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(sym: String, onBack: () -> Unit) {
    val quotes by Repo.quotes.collectAsState()
    val holdings by Repo.holdings.collectAsState()
    val held = holdings.find { it.symbol == sym }
    val token = Instruments.get(sym)?.token ?: held?.token
    var candles by remember { mutableStateOf<List<Candle>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sym) {
        val r = withContext(Dispatchers.IO) {
            runCatching { Repo.candlesFor(sym, requireNotNull(token) { "Symbol not found in the instrument list" }) }
        }
        r.onSuccess { candles = it }.onFailure { error = it.message ?: "Failed" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(sym, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        val c = candles
        when {
            error != null -> Text("Could not load: $error", Modifier.padding(16.dp), color = Red)
            c == null || token == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> DetailBody(sym, token, c, quotes[sym], held)
        }
    }
}

@Composable
fun DetailBody(sym: String, token: String, c: List<Candle>, q: Quote?, held: Holding?) {
    val ltp = q?.ltp?.takeIf { it > 0 } ?: held?.ltp?.takeIf { it > 0 } ?: c.last().c
    val a = remember(c, ltp) { Analyzer.analyze(c, ltp) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instruments.get(sym)?.takeIf { it.isDeriv }?.let { ins ->
            item {
                Section("Contract") {
                    KV("Instrument", ins.describe())
                    KV("Exchange", ins.exch)
                    KV("Lot size", ins.lot.toString())
                    KV("Tick size", "₹" + f2(ins.tick))
                    ins.expiry?.let { KV("Expiry", it.toString()) }
                }
            }
            item { OrbCard(ins.symbol) }
        }
        item { ScoreCard(ltp, q, a) }
        item {
            Section("Key levels") {
                KV("All-time high", "₹" + f2(a.ath) + (a.athDate?.let { "  ($it)" } ?: ""))
                KV("All-time low", "₹" + f2(a.atl) + (a.atlDate?.let { "  ($it)" } ?: ""))
                KV("52-week high", "₹" + f2(a.high52))
                KV("52-week low", "₹" + f2(a.low52))
                KV("Previous high", "₹" + f2(a.prevHigh))
                KV("Previous low", "₹" + f2(a.prevLow))
                KV("Previous close", "₹" + f2(a.prevClose))
            }
        }
        item {
            Section("Moving averages") {
                listOf("SMA 20" to a.sma20, "SMA 50" to a.sma50, "SMA 200" to a.sma200).forEach { (n, v) ->
                    if (v == null) KV(n, "not enough data", Muted)
                    else KV(n, "₹" + f2(v) + if (ltp > v) "  ▲ above" else "  ▼ below", if (ltp > v) Green else Red)
                }
            }
        }
        item { PivotCard("Daily pivots", a.daily) }
        item { PivotCard("Weekly pivots", a.weekly) }
        item { PivotCard("Monthly pivots", a.monthly) }
        item {
            Section("Fibonacci (all-time low → high)") {
                a.fib.sortedByDescending { it.second }.forEach { (n, v) ->
                    val tag = when (v) {
                        a.fibSupport -> "  ← support (stop-loss)"
                        a.fibResistance -> "  ← resistance (target)"
                        else -> ""
                    }
                    KV(n, "₹" + f2(v) + tag, if (v > ltp) Red else Green)
                }
            }
        }
        item {
            Section("Square-root levels") {
                Text("Resistance", color = Red, fontWeight = FontWeight.SemiBold)
                a.sqrtRes.reversed().forEach { KV("R", "₹" + f2(it), Red) }
                KV("Price now", "₹" + f2(ltp))
                Text("Support", color = Green, fontWeight = FontWeight.SemiBold)
                a.sqrtSup.forEach { KV("S", "₹" + f2(it), Green) }
            }
        }
        item {
            val inst = Instruments.get(sym)
            if (inst != null && inst.isDeriv) DerivOrderPanel(inst, ltp, a) else OrderPanel(sym, token, ltp, a, held)
        }
        item {
            Text("Score and levels are rough technical indicators, not investment advice.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FA))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun KV(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PivotCard(title: String, p: Pivot?) {
    Section(title) {
        if (p == null) { Text("Not enough history", color = Muted); return@Section }
        listOf(Triple("R3", p.r3, "S3" to p.s3), Triple("R2", p.r2, "S2" to p.s2), Triple("R1", p.r1, "S1" to p.s1)).forEach { (rl, rv, s) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { KV(rl, f2(rv), Red) }
                Box(Modifier.weight(1f)) { KV(s.first, f2(s.second), Green) }
            }
        }
        KV("Pivot", f2(p.p))
    }
}

@Composable
fun ScoreCard(ltp: Double, q: Quote?, a: Analysis) {
    val good = a.score >= 50
    val col = if (good) Green else Red
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = col.copy(alpha = 0.10f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("₹" + f2(ltp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    q?.let { Text(signedRs(it.change) + " (" + pctStr(it.pct) + ")", color = chgColor(it.change)) }
                }
                Box(Modifier.size(70.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                    Text("${a.score}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(if (good) "BUY bias (score ${a.score}/100)" else "WAIT / AVOID (score ${a.score}/100)", color = col, fontWeight = FontWeight.Bold)
            a.reasons.forEach { (ok, t) -> Text((if (ok) "✔ " else "✘ ") + t, color = if (ok) Green else Red, fontSize = 13.sp) }
        }
    }
}

@Composable
fun OrderPanel(sym: String, token: String, ltp: Double, a: Analysis, held: Holding?) {
    val scope = rememberCoroutineScope()
    var qty by remember(sym) { mutableStateOf("1") }
    var limit by remember(sym) { mutableStateOf("") }
    var sl by remember(sym) { mutableStateOf(a.fibSupport?.let { String.format("%.2f", it) } ?: "") }
    var tp by remember(sym) { mutableStateOf(a.fibResistance?.let { String.format("%.2f", it) } ?: "") }
    var gtt by remember(sym) { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var exitOnly by remember { mutableStateOf(false) }

    val q = qty.toIntOrNull() ?: 0
    val slV = sl.toDoubleOrNull()
    val tpV = tp.toDoubleOrNull()
    val limV = limit.toDoubleOrNull()

    fun validate(exit: Boolean): String? {
        if (!exit && q <= 0) return "Enter a quantity"
        if (!exit && limit.isNotBlank() && limV == null) return "Limit price is invalid"
        if (slV != null && slV >= ltp) return "Stop-loss must be below the current price"
        if (tpV != null && tpV <= ltp) return "Target must be above the current price"
        if (exit && (held == null || held.qty <= 0)) return "You don't hold this stock"
        if (exit && slV == null && tpV == null) return "Enter a stop-loss and/or target"
        return null
    }

    Section("Place order (Delivery)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text("Qty") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(limit, { limit = it }, label = { Text("Limit ₹ (blank = market)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1.4f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sl, { sl = it }, label = { Text("Stop-loss (Fib support)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(tp, { tp = it }, label = { Text("Target (Fib resistance)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(gtt, { gtt = it })
            Text("Also set stop-loss & target GTT after buying", fontSize = 13.sp)
        }
        msg?.let { Text(it, color = if (it.startsWith("Buy order placed") || it.contains("GTT set")) Green else Red, fontSize = 13.sp) }
        Button(onClick = {
            val e = validate(false)
            if (e != null) msg = e else { exitOnly = false; confirm = true }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Working…" else "Review buy order") }
        if (held != null && held.qty > 0) {
            OutlinedButton(onClick = {
                val e = validate(true)
                if (e != null) msg = e else { exitOnly = true; confirm = true }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Set SL & Target GTT for ${held.qty} held") }
        }
    }

    if (confirm) {
        val n = if (exitOnly) held?.qty ?: 0 else q
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (exitOnly) "Confirm exit orders" else "Confirm BUY") },
            text = {
                Text(
                    (if (exitOnly) "Set GTT SELL orders for $n × $sym (Delivery)\n"
                    else "BUY $n × $sym (Delivery)\n" + (if (limV != null) "Limit ₹" + f2(limV) else "Market order, approx ₹" + f2(ltp * n)) + "\n") +
                        (if (slV != null && (exitOnly || gtt)) "Stop-loss GTT trigger ₹" + f2(slV) + "\n" else "") +
                        (if (tpV != null && (exitOnly || gtt)) "Target GTT trigger ₹" + f2(tpV) + "\n" else "") +
                        "\nThis places a real order with your Angel One account."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true; msg = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                if (exitOnly) Repo.placeExits(sym, token, n, slV, tpV)
                                else Repo.placeBuy(sym, token, n, limV, slV, tpV, gtt)
                            }
                        }
                        msg = r.getOrElse { "Failed: ${it.message}" }
                        busy = false
                    }
                }) { Text("Place order", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
