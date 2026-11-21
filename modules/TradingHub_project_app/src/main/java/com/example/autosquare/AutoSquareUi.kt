@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.autosquare

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angelwatch.Green
import com.example.angelwatch.Muted
import com.example.angelwatch.Red
import com.example.angelwatch.StockRow
import com.example.angelwatch.chgColor
import com.example.angelwatch.f2
import com.example.angelwatch.pctStr
import com.example.angelwatch.signedRs
import com.example.angelwatch.marketOpen
import kotlinx.coroutines.delay

@Composable
fun AutoSquareApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        "Setup" to Icons.Filled.Settings, "Portfolio" to Icons.Filled.Folder,
        "Buy" to Icons.Filled.TrendingUp, "Short" to Icons.Filled.TrendingDown, "Trend" to Icons.Filled.ShowChart
    )
    Scaffold(bottomBar = {
        NavigationBar {
            items.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            RunStrip()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> SetupTab()
                    1 -> PortfolioTab()
                    2 -> ScreenerTab(0)
                    3 -> ScreenerTab(2)
                    else -> ScreenerTab(1)
                }
            }
        }
    }
}

/** Always-visible status + Stop/Start for the AutoSquare background service. */
@Composable
fun RunStrip() {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(MonitorService.running) }
    LaunchedEffect(Unit) {
        while (true) { running = MonitorService.running; delay(1000) }
    }
    val col = if (running) Green else Red
    Surface(color = col.copy(alpha = 0.10f)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(if (running) "Background running" else "Background stopped", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (marketOpen()) "Market open" else "Market closed", color = Muted, fontSize = 11.sp)
            }
            if (running) {
                TextButton(onClick = { ctx.startService(Intent(ctx, MonitorService::class.java).setAction("STOP")) }) {
                    Text("Stop", color = Red, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(
                    onClick = { ctx.startForegroundService(Intent(ctx, MonitorService::class.java)) },
                    enabled = ASState.store(ctx).get("apiKey").isNotEmpty()
                ) { Text("Start", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, number: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default
    )
}

// ---------------- SETUP ----------------
@Composable
fun SetupTab() {
    val ctx = LocalContext.current
    val s = remember { ASState.store(ctx) }
    var api by remember { mutableStateOf(s.get("apiKey")) }
    var client by remember { mutableStateOf(s.get("client")) }
    var pin by remember { mutableStateOf(s.get("pin")) }
    var totp by remember { mutableStateOf(s.get("totp")) }
    var sl by remember { mutableStateOf(s.get("sl", "2")) }
    var tp by remember { mutableStateOf(s.get("tp", "4")) }
    var trail by remember { mutableStateOf(s.get("trail", "1")) }
    var sec by remember { mutableStateOf(s.get("sec", "5")) }
    var log by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            synchronized(MonitorService.log) { log = MonitorService.log.toString() }
            delay(2000)
        }
    }
    fun save() = s.put("apiKey" to api.trim(), "client" to client.trim(), "pin" to pin.trim(), "totp" to totp.trim(),
        "sl" to sl, "tp" to tp, "trail" to trail, "sec" to sec)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AutoSquare setup", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        com.example.angelwatch.IpCard()
        Text("Your Angel One login is shared with AngelWatch, so if you already entered it there it appears here. Everything is stored encrypted on this phone only.",
            color = Muted, fontSize = 13.sp)
        Field("SmartAPI key", api, { api = it }, secret = true)
        Field("Client code", client, { client = it })
        Field("PIN", pin, { pin = it }, secret = true)
        Field("TOTP secret (base32 key)", totp, { totp = it }, secret = true)
        HorizontalDivider()
        Field("Open positions: stop-loss %", sl, { sl = it }, number = true)
        Field("Open positions: target %", tp, { tp = it }, number = true)
        Field("Trailing stop % (trades placed from app)", trail, { trail = it }, number = true)
        Field("Check every (seconds)", sec, { sec = it }, number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { save(); ctx.startForegroundService(Intent(ctx, MonitorService::class.java)) }, modifier = Modifier.weight(1f)) { Text("Save & start") }
            OutlinedButton(onClick = { ctx.startService(Intent(ctx, MonitorService::class.java).setAction("STOP")) }, modifier = Modifier.weight(1f)) { Text("Stop") }
            OutlinedButton(onClick = { save(); ASState.toast(ctx, "Saved") }, modifier = Modifier.weight(1f)) { Text("Save") }
        }
        HorizontalDivider()
        Text("Service log", fontWeight = FontWeight.SemiBold)
        Text(if (log.isEmpty()) "No activity yet." else log, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
    }
}

// ---------------- PORTFOLIO ----------------
@Composable
fun PortfolioTab() {
    val ctx = LocalContext.current
    val s = remember { ASState.store(ctx) }
    var live by remember { mutableStateOf(s.get("live") == "1") }
    var hsl by remember { mutableStateOf(s.get("hsl", "0")) }
    var opts by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<Hd?>(null) }
    @Suppress("UNUSED_VARIABLE") val version = ASState.pVersion   // read so the list redraws when a stop-loss changes
    val hs = ASState.holdings
    val loading = ASState.pLoading

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(ASState.csvText().toByteArray()) }
                ASState.toast(ctx, "CSV saved")
            } catch (e: Exception) {
                ASState.toast(ctx, "Save failed: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) { ASState.loadPortfolio(ctx) }
    LaunchedEffect(live) {
        while (live) {
            delay(10_000)
            if (!ASState.pLoading) ASState.loadPortfolio(ctx)
        }
    }

    val invested = hs.sumOf { it.avg * it.qty }
    val current = hs.sumOf { it.ltp * it.qty }
    val total = current - invested
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Current value", color = Muted, fontSize = 13.sp)
                    Text("₹" + f2(current), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Invested ₹" + f2(invested) + "  •  P&L " + signedRs(total) +
                        (if (invested > 0) " (" + pctStr(total / invested * 100) + ")" else ""), color = chgColor(total), fontSize = 13.sp)
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else IconButton(onClick = { ASState.loadPortfolio(ctx) }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (hs.isEmpty()) ASState.toast(ctx, "Load portfolio first") else csvPicker.launch("portfolio.csv") }) { Text("Save CSV") }
                TextButton(onClick = { opts = !opts }) { Text(if (opts) "Options ▴" else "Options ▾") }
                Text(ASState.pStatus, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (opts) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(live, {
                            live = it
                            s.put("live" to if (it) "1" else "0")
                            if (it) ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
                        })
                        Spacer(Modifier.width(10.dp))
                        Text("LIVE: refresh every 10s and update files in Downloads/AutoSquare (portfolio_live.xls / .csv, positions_live.csv). The service must be running.",
                            fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = { ASState.updateLiveNow(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Update live files now") }
                    Field("Stop-loss % for ALL holdings (0 = off)", hsl, { hsl = it }, number = true)
                    Button(onClick = { s.put("hsl" to hsl); ASState.toast(ctx, "Saved. Keep the service running.") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Apply stop-loss to all holdings")
                    }
                }
            }
        }
        item { HorizontalDivider() }
        items(hs, key = { it.tok + it.sym }) { x ->
            val pnl = (x.ltp - x.avg) * x.qty
            val pct = if (x.avg > 0) (x.ltp - x.avg) / x.avg * 100 else 0.0
            StockRow(x.sym, "${x.qty} × avg ₹" + f2(x.avg), "₹" + f2(x.ltp), signedRs(pnl) + " (" + pctStr(pct) + ")",
                onClick = { dlg = x }, line2Color = chgColor(pnl))
        }
        val tr = Trades.list(s)
        if (tr.isNotEmpty()) {
            item { Text("Active auto-managed trades", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            items(tr, key = { it.getString("id") }) {
                Text("${it.getString("sym")} x${it.getInt("qty")}  entry ${n2(it.getDouble("entry"))}  SL ${n2(it.getDouble("sl"))}  stage ${it.getInt("stage")}",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
    }
    dlg?.let { x -> HoldingDialog(x) { dlg = null } }
}

@Composable
private fun HoldingDialog(x: Hd, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { ASState.store(ctx) }
    var sl by remember { mutableStateOf(s.get("hsl_${x.tok}")) }
    val pnl = (x.ltp - x.avg) * x.qty
    val pct = if (x.avg > 0) (x.ltp - x.avg) / x.avg * 100 else 0.0
    AlertDialog(
        onDismissRequest = onClose, title = { Text(x.sym) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LTP ₹" + f2(x.ltp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${x.qty} shares • avg ₹" + f2(x.avg), color = Muted, fontSize = 13.sp)
                Text("P&L " + signedRs(pnl) + " (" + pctStr(pct) + ")", color = chgColor(pnl), fontSize = 13.sp)
                val lv = x.lv
                if (lv == null) Text("No level data") else {
                    val near = Calc.levels(lv).filter { x.ltp > 0 && Math.abs(it.second - x.ltp) / x.ltp <= 0.01 }
                    near.forEach {
                        val sup = x.ltp >= it.second
                        Text("● near ${it.first} ${n2(it.second)} – ${if (sup) "support" else "resistance"}", color = if (sup) Green else Red, fontSize = 13.sp)
                    }
                    Spacer(Modifier.size(4.dp))
                    (Calc.levels(lv) + Calc.sq(x.ltp)).sortedByDescending { it.second }.forEach {
                        val up = it.second > x.ltp
                        Text("${if (up) "▲" else "▼"} ${it.first}  ${n2(it.second)}  (${n2((it.second - x.ltp) / x.ltp * 100)}%)",
                            color = if (up) Red else Green, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.size(6.dp))
                Field("Stop-loss % for this stock (blank = global " + s.get("hsl", "0") + ")", sl, { sl = it }, number = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                s.put("hsl_${x.tok}" to sl.trim(), "anyH" to "1")
                ASState.pVersion = ASState.pVersion + 1
                onClose()
            }) { Text("Save SL") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

// ---------------- SCREENERS ----------------
@Composable
fun ScreenerTab(kind: Int) {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf<Sr?>(null) }
    val out = ASState.sOut[kind]
    val busyHere = ASState.sBusy && ASState.sKind == kind
    val desc = when (kind) {
        0 -> "Intraday BUY: Nifty 500 above prev day high, above pivot, stop below pivot/R1 (risk ≤ 1 ATR). Tap a stock for the signal and to place an order."
        2 -> "Intraday SELL: Nifty 500 below prev day low, below pivot, stop above pivot/S1 (risk ≤ 1 ATR). Tap a stock for the signal and to place an order."
        else -> "Positional BUY: above SMA 20/50/100/200 and above weekly pivot (stop = weekly pivot, targets weekly R1/R2/R3). Tap a stock for the signal and to place an order."
    }
    val status = when {
        busyHere -> ASState.sProgress
        ASState.sBusy -> "Another screener is running (${ASState.sProgress}). Wait for it to finish."
        out != null -> out.msg
        else -> "First run downloads the list and ~500 candles (3–5 min). Later runs today are fast."
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { ASState.runScreener(ctx, kind) }, enabled = !ASState.sBusy) { Text("Run screener") }
                    if (busyHere) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(status, fontSize = 12.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (out == null && !ASState.sBusy) Text(desc, fontSize = 11.sp, color = Muted)
            }
            HorizontalDivider()
        }
        if (out != null) {
            items(out.rows, key = { it.tok }) { r ->
                val ch = if (r.close > 0) r.ltp - r.close else null
                val valid = remember(r, kind) { Plan.make(r.lv, r.ltp, kind).err == null }
                StockRow(
                    r.sym, if (valid) "NSE" else "NSE • no valid setup", "₹" + f2(r.ltp),
                    if (ch != null) signedRs(ch) + " (" + pctStr(ch / r.close * 100) + ")" else "",
                    onClick = { dlg = r }, priceColor = if (ch != null) chgColor(ch) else Color.Unspecified
                )
            }
        }
    }
    dlg?.let { r -> SignalDialog(r, kind) { dlg = null } }
}

/** Tap a stock: the full signal (stop-loss, targets, levels) and the order button. */
@Composable
private fun SignalDialog(r: Sr, kind: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lv = r.lv
    val pl = remember(r, kind) { Plan.make(lv, r.ltp, kind) }
    val fb = Calc.fibs(lv.getDouble("h52"), lv.getDouble("l52"))
    val up = fb.filter { it.second > r.ltp }.minByOrNull { it.second }
    val dn = fb.filter { it.second <= r.ltp }.maxByOrNull { it.second }
    val head = when (kind) {
        0 -> "+${n2((r.ltp / lv.getDouble("pdh") - 1) * 100)}% over previous day high ${n2(lv.getDouble("pdh"))}"
        2 -> "${n2((r.ltp / lv.getDouble("pdl") - 1) * 100)}% under previous day low ${n2(lv.getDouble("pdl"))}"
        else -> "Above weekly pivot ${n2(lv.getDouble("wp"))}"
    }
    val lab = when (kind) { 0 -> "Book R2 / R3 / R4: "; 2 -> "Cover S2 / S3 / S4: "; else -> "Next weekly R1 / R2 / R3: " }
    val col = if (pl.err != null) Muted else if (kind == 2) Red else Green
    val sq = if (kind == 2) Calc.sqDown(r.ltp) else Calc.sq(r.ltp)
    var qty by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(r.sym + "  ₹" + f2(r.ltp)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(head, color = col, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (pl.err != null) {
                    Text("✖ Setup not valid: ${pl.err}", color = Muted, fontSize = 13.sp)
                } else {
                    Text("${pl.side} ${pl.product}: limit order ≈ ${n2(r.ltp)} (0.2% marketable), waits up to 15s to fill, cancels if not filled.", fontSize = 13.sp)
                    Text("Stop-loss ${n2(pl.sl)}  (risk/share ${n2(Math.abs(r.ltp - pl.sl))})", color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(lab + pl.t.joinToString(" / ") { n2(it) }, color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(pl.note, color = Muted, fontSize = 12.sp)
                    if (pl.near.isNotEmpty()) Text(pl.near, color = Muted, fontSize = 12.sp)
                    Text("Trailing stop: ATR-based, ${n2(pl.trailD)} behind the best price. Book 1/3 at each target; the stop moves to entry after the 1st.",
                        color = Muted, fontSize = 12.sp)
                }
                HorizontalDivider()
                Text("Fib ▲ ${up?.let { it.first + " " + n2(it.second) } ?: "-"}   ▼ ${dn?.let { it.first + " " + n2(it.second) } ?: "-"}", color = Muted, fontSize = 12.sp)
                Text("√ ${if (kind == 2) "support" else "resistance"}: " + sq.joinToString("  ") { "${it.first} ${n2(it.second)}" }, color = Muted, fontSize = 12.sp)
                if (pl.err == null) {
                    OutlinedTextField(qty, { qty = it }, label = { Text("Quantity") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (pl.err == null) {
                TextButton(onClick = {
                    val q = qty.toIntOrNull() ?: 0
                    if (q <= 0) ASState.toast(ctx, "Enter quantity") else { ASState.placeOrder(ctx, r, pl, q); onClose() }
                }) { Text("PLACE ${pl.side} ${pl.product}") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (pl.err == null) "Cancel" else "Close") } }
    )
}
