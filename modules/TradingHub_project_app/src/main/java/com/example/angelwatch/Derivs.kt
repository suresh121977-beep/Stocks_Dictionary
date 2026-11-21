@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.angelwatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Buy / sell panel for futures, options (NFO, BFO) and MCX contracts. Quantity is entered in lots. */
@Composable
fun DerivOrderPanel(ins: Instrument, ltp: Double, a: Analysis) {
    val scope = rememberCoroutineScope()
    var side by remember(ins.symbol) { mutableStateOf("BUY") }
    var product by remember(ins.symbol) { mutableStateOf("INTRADAY") }
    var lots by remember(ins.symbol) { mutableStateOf("1") }
    var limit by remember(ins.symbol) { mutableStateOf("") }
    var sl by remember(ins.symbol) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val orbAll by Orb.signals.collectAsState()
    val orbSig = orbAll[ins.symbol]
    val l = lots.toIntOrNull() ?: 0
    val qty = l * ins.lot
    val limV = limit.toDoubleOrNull()
    val slV = sl.toDoubleOrNull()
    val price = limV ?: ltp

    fun validate(): String? {
        if (l <= 0) return "Enter the number of lots"
        if (limit.isNotBlank() && limV == null) return "Limit price is invalid"
        if (sl.isNotBlank() && slV == null) return "Stop-loss is invalid"
        if (slV != null) {
            if (limit.isNotBlank()) return "A stop-loss order is only placed with a market entry (it needs an open position). Clear the limit price or the stop-loss."
            if (side == "BUY" && slV >= ltp) return "For a BUY the stop-loss must be below the current price"
            if (side == "SELL" && slV <= ltp) return "For a SELL the stop-loss must be above the current price"
        }
        return null
    }

    Section("Trade ${ins.describe()}") {
        if (orbSig != null && (orbSig.status == "BUY" || orbSig.status == "SELL")) {
            OutlinedButton(onClick = {
                side = orbSig.status
                limit = ""
                sl = String.format(java.util.Locale.US, "%.2f", orbSig.stop)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Use signal: ${orbSig.status}, stop-loss ₹" + f2(orbSig.stop) + ", targets " + orbSig.targets.joinToString(" / ") { f2(it) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side == "BUY", onClick = { side = "BUY" }, label = { Text("BUY") })
            FilterChip(selected = side == "SELL", onClick = { side = "SELL" }, label = { Text("SELL") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = product == "INTRADAY", onClick = { product = "INTRADAY" }, label = { Text("Intraday") })
            FilterChip(selected = product == "CARRYFORWARD", onClick = { product = "CARRYFORWARD" }, label = { Text("Carry forward") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lots, { lots = it.filter(Char::isDigit) }, label = { Text("Lots") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(limit, { limit = it }, label = { Text("Limit ₹ (blank = market)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1.4f))
        }
        OutlinedTextField(sl, { sl = it }, label = { Text("Stop-loss trigger ₹ (optional, market entry only)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Text("Quantity = $l lot × ${ins.lot} = $qty  •  approx. value ₹" + f2(price * qty) +
            (if (ins.isOption) " (premium × quantity)" else " (contract value, not margin)"), color = Muted, fontSize = 12.sp)
        msg?.let { Text(it, color = if (it.contains("order placed")) Green else Red, fontSize = 13.sp) }
        Button(onClick = {
            val e = validate()
            if (e != null) msg = e else confirm = true
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Working…" else "Review $side order") }
        Text("Options and futures can lose more than you expect. Orders only work from your registered static IP.", color = Muted, fontSize = 12.sp)
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Confirm $side") },
            text = {
                Text(
                    "$side $qty × ${ins.symbol}\n(${l} lot × ${ins.lot}, ${ins.exch}, " + (if (product == "INTRADAY") "Intraday" else "Carry forward") + ")\n" +
                        (if (limV != null) "Limit ₹" + f2(limV) else "Market order, approx ₹" + f2(ltp)) + "\n" +
                        (if (slV != null) "Then a stop-loss order: trigger ₹" + f2(slV) + "\n" else "") +
                        "\nThis places a real order with your Angel One account."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true; msg = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Repo.placeDeriv(ins, side, qty, limV, product, slV) }
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

/** 30-minute breakout status for one futures / options contract. */
@Composable
fun OrbCard(sym: String) {
    val all by Orb.signals.collectAsState()
    val s = all[sym]
    Section("30-min breakout signal (5-min chart)") {
        if (s == null) {
            Text("Waiting for the background service (keep it running during market hours).", color = Muted, fontSize = 13.sp)
            return@Section
        }
        val col = when (s.status) { "BUY" -> Green; "SELL" -> Red; else -> Muted }
        val head = when (s.status) {
            "BUY" -> "▲ BUY signal"
            "SELL" -> "▼ SELL signal"
            "WAIT_BUY" -> "Above 30-min high, not confirmed"
            "WAIT_SELL" -> "Below 30-min low, not confirmed"
            "INSIDE" -> "No breakout yet"
            else -> s.status.lowercase().replaceFirstChar { it.uppercase() }
        }
        Text(head, color = col, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
        Text(s.text, fontSize = 13.sp)
        if (s.high > 0) {
            KV("First 30-min high", "₹" + f2(s.high), Green)
            KV("First 30-min low", "₹" + f2(s.low), Red)
        }
        if (s.sma > 0) {
            KV("5-min 20 SMA", "₹" + f2(s.sma))
            KV("Last 5-min close", "₹" + f2(s.lastClose))
        }
        s.piv?.let { p ->
            val buy = s.status == "BUY" || s.status == "WAIT_BUY"
            val sell = s.status == "SELL" || s.status == "WAIT_SELL"
            KV("Daily pivot", "₹" + f2(p.p) + if (s.ltp > p.p) "  ▲ price above" else if (s.ltp > 0) "  ▼ price below" else "",
                if (s.ltp > p.p) Green else Red)
            Text(if (sell) "Downside targets (S1 to S3)" else "Upside targets (R1 to R3)", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 14.sp)
            val names = if (sell) listOf("S1" to p.s1, "S2" to p.s2, "S3" to p.s3) else listOf("R1" to p.r1, "R2" to p.r2, "R3" to p.r3)
            names.forEach { (n, v) ->
                val hit = s.ltp > 0 && (if (sell) s.ltp <= v else s.ltp >= v)
                KV(n, "₹" + f2(v) + if (hit) "  ✓ reached" else "", if (sell) Red else Green)
            }
            if (s.stop > 0) KV("Stop-loss", (if (sell) "above ₹" else "below ₹") + f2(s.stop), Red)
            if (!buy && !sell) Text("Pivot levels shown for reference. A signal needs a 30-min break first.", color = Muted, fontSize = 11.sp)
        }
        Text("BUY = price above the 30-min high, above the 5-min 20 SMA and above the daily pivot (targets R1 to R3). " +
            "SELL = below the 30-min low, below the 5-min 20 SMA and below the daily pivot (targets S1 to S3). " +
            "For an option, the signal is on that option's own premium chart. A rough technical signal, not advice.", color = Muted, fontSize = 11.sp)
    }
}
