@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.angelwatch

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

val Green = Color(0xFF0A9D74)
val Red = Color(0xFFE0474C)
val Blue = Color(0xFF3B4FD8)
val Muted = Color(0xFF5F6B7A)

fun chgColor(v: Double) = if (v >= 0) Green else Red
fun signedRs(v: Double) = (if (v >= 0) "+" else "-") + "₹" + f2(abs(v))
fun pctStr(p: Double) = String.format(Locale.US, "%+.2f%%", p)

fun refreshSoon(scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) { runCatching { Repo.tick() } }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (Store.hasCreds()) 0 else 5) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Instruments.load(ctx) } } }

    val d = detail
    if (d != null) {
        BackHandler { detail = null }
        if (d.startsWith("IDX:")) IndexDetailScreen(d.removePrefix("IDX:")) { detail = null }
        else DetailScreen(d) { detail = null }
    } else {
        val items = listOf(
            "Watchlist" to Icons.Filled.Star, "Indices" to Icons.Filled.ShowChart, "Portfolio" to Icons.Filled.Folder,
            "Alerts" to Icons.Filled.Notifications, "Orders" to Icons.Filled.Receipt, "Account" to Icons.Filled.AccountCircle
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
                BackgroundStrip()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> WatchlistScreen { detail = it }
                        1 -> IndicesScreen { detail = it }
                        2 -> PortfolioScreen { detail = it }
                        3 -> AlertsScreen { detail = it }
                        4 -> OrdersScreen()
                        else -> AccountScreen()
                    }
                }
            }
        }
    }
}

/** Always-visible status + Stop/Start for the background service (handy after the market closes). */
@Composable
fun BackgroundStrip() {
    val ctx = LocalContext.current
    val running by Repo.running.collectAsState()
    val status by Repo.status.collectAsState()
    val col = if (running) Green else Red
    Surface(color = col.copy(alpha = 0.10f)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(10.dp))
            Text(
                (if (running) "Running" else "Stopped") + (if (marketOpen()) "" else " • Market closed") + " • " + status,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (running) TextButton(onClick = { MonitorService.stop(ctx) }) { Text("Stop", color = Red, fontWeight = FontWeight.Bold) }
            else TextButton(onClick = { MonitorService.start(ctx) }, enabled = Store.hasCreds()) { Text("Start", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun IndexBar() {
    val ix by Repo.indices.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (ix.isEmpty()) Text("Waiting for market data…", color = Muted, fontSize = 13.sp)
        ix.forEach { q ->
            Column(Modifier.weight(1f)) {
                Text(q.symbol, fontWeight = FontWeight.Bold)
                Text(f2(q.ltp), color = chgColor(q.change), fontWeight = FontWeight.SemiBold)
                Text(signedRs(q.change) + " (" + pctStr(q.pct) + ")", color = chgColor(q.change), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, okLabel: String, multiline: Boolean = false, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = !multiline, minLines = if (multiline) 4 else 1) },
        confirmButton = { TextButton(onClick = { if (t.isNotBlank()) onOk(t) }) { Text(okLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun WatchlistScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val lists by Store.lists.collectAsState()
    val quotes by Repo.quotes.collectAsState()
    val ready by Instruments.ready.collectAsState()
    var sel by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var seg by rememberSaveable { mutableStateOf("ALL") }
    var sort by rememberSaveable { mutableStateOf("Manual") }
    var chg by rememberSaveable { mutableStateOf("All") }
    var pv by rememberSaveable { mutableStateOf("") }
    val pivots by Repo.pivots.collectAsState()
    val orb by Orb.signals.collectAsState()
    var sg by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val idx = sel.coerceIn(0, lists.size - 1)
    val cur = lists[idx]

    when (dialog) {
        "new" -> TextDialog("New watchlist", "", "Create", onDismiss = { dialog = "" }) {
            Store.addList(it); sel = Store.lists.value.size - 1; dialog = ""
        }
        "rename" -> TextDialog("Rename watchlist", cur.name, "Save", onDismiss = { dialog = "" }) {
            Store.rename(cur.name, it); dialog = ""
        }
        "import" -> TextDialog("Paste symbols (comma, space or new line)", "", "Import", multiline = true, onDismiss = { dialog = "" }) {
            val (a, s) = Store.importText(cur.name, it)
            Toast.makeText(ctx, "Added $a, skipped $s", Toast.LENGTH_LONG).show()
            dialog = ""; refreshSoon(scope)
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text("Delete \"${cur.name}\"?") },
            confirmButton = { TextButton(onClick = { Store.deleteList(cur.name); sel = 0; dialog = "" }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(if (ready) "Search stocks, F&O, MCX" else "Loading list…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        if (query.isNotBlank()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "All", "EQ" to "Equity", "FNO" to "F&O", "MCX" to "MCX").forEach { (k, label) ->
                    FilterChip(selected = seg == k, onClick = { seg = k }, label = { Text(label) })
                }
            }
            val results = remember(query, ready, seg) { Instruments.search(query, seg) }
            if (ready && results.isEmpty()) {
                Text("No match. For F&O try e.g. \"nifty 26000 ce\", \"reliance fut\", \"banknifty\"; for MCX \"gold\", \"crudeoil\".",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(results, key = { it.symbol }) { ins ->
                    val added = ins.symbol in cur.symbols
                    Row(Modifier.fillMaxWidth().clickable(enabled = !added) {
                        Store.addSymbol(cur.name, ins.symbol); query = ""; refreshSoon(scope)
                    }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(ins.symbol, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (ins.isDeriv) ins.subtitle() else ins.name, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (added) "Added" else "+ Add", color = if (added) Muted else Blue)
                    }
                    HorizontalDivider()
                }
            }
        } else {
            IndexBar()
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScrollableTabRow(selectedTabIndex = idx, modifier = Modifier.weight(1f), edgePadding = 8.dp) {
                    lists.forEachIndexed { i, l -> Tab(selected = i == idx, onClick = { sel = i }, text = { Text(l.name) }) }
                }
                IconButton(onClick = { dialog = "new" }) { Icon(Icons.Filled.Add, "New watchlist") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Text("Sort", color = Muted); Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Tune, null, tint = Muted)
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Manual", "Name", "Change ↓", "Change ↑", "Price ↓").forEach {
                            DropdownMenuItem(text = { Text("Sort: $it" + if (it == sort) "  ✓" else "") }, onClick = { sort = it; sortMenu = false })
                        }
                    }
                }
                Text("Sort: $sort", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "List options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename list") }, onClick = { menu = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("Import symbols") }, onClick = { menu = false; dialog = "import" })
                        DropdownMenuItem(text = { Text("Delete list", color = Red) }, onClick = { menu = false; dialog = "delete" })
                    }
                }
            }
            HorizontalDivider()

            val today = java.time.LocalDate.now()
            val base: List<Pair<String, Quote?>> = cur.symbols.map { it to quotes[it] }
            var rows = base.filter { (s, q) -> matchesFilter(q?.ltp ?: 0.0, q?.pct, chg, pvSet(pv), pivots[s]) && (sg == "" || orb[s]?.status == sg) }
            PivotLoader(pv.isNotEmpty(), cur.symbols)
            FilterBar(chg, { chg = it }, pv, { pv = it }, rows.size, base.size, cur.symbols.count { pivots[it]?.day == today }, sg, { sg = it })
            HorizontalDivider()
            rows = when (sort) {
                "Name" -> rows.sortedBy { it.first }
                "Change ↓" -> rows.sortedByDescending { it.second?.pct ?: -999.0 }
                "Change ↑" -> rows.sortedBy { it.second?.pct ?: 999.0 }
                "Price ↓" -> rows.sortedByDescending { it.second?.ltp ?: 0.0 }
                else -> rows
            }
            if (base.isEmpty()) {
                Text("This list is empty. Use the search box above to add stocks, or import symbols from the ⋮ menu.",
                    color = Muted, modifier = Modifier.padding(24.dp))
            } else if (rows.isEmpty()) {
                Text("No stocks match the current filters.", color = Muted, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.first }) { (sym, q) ->
                    var rowMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { open(sym) }, onLongClick = { rowMenu = true })
                            .padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(sym, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Instruments.get(sym)?.subtitle() ?: "NSE", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                when (orb[sym]?.status) {
                                    "BUY" -> Text("▲ BUY • next target ₹" + f2(orb[sym]?.let { o -> o.targets.firstOrNull { it > o.ltp } ?: o.targets.last() } ?: 0.0) + " • stop ₹" + f2(orb[sym]?.stop ?: 0.0),
                                        color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    "SELL" -> Text("▼ SELL • next target ₹" + f2(orb[sym]?.let { o -> o.targets.firstOrNull { it < o.ltp } ?: o.targets.last() } ?: 0.0) + " • stop ₹" + f2(orb[sym]?.stop ?: 0.0),
                                        color = Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    "WAIT_BUY" -> Text("Broke 30-min high • not confirmed yet", color = Muted, fontSize = 12.sp)
                                    "WAIT_SELL" -> Text("Broke 30-min low • not confirmed yet", color = Muted, fontSize = 12.sp)
                                    else -> {}
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (q == null) Text("—", color = Muted, fontSize = 18.sp) else {
                                    Text("₹" + f2(q.ltp), color = chgColor(q.change), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Text(signedRs(q.change) + " (" + pctStr(q.pct) + ")", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                        DropdownMenu(rowMenu, { rowMenu = false }) {
                            DropdownMenuItem(text = { Text("Move up") }, onClick = { Store.move(cur.name, sym, -1); rowMenu = false })
                            DropdownMenuItem(text = { Text("Move down") }, onClick = { Store.move(cur.name, sym, 1); rowMenu = false })
                            DropdownMenuItem(text = { Text("Remove", color = Red) }, onClick = { Store.removeSymbol(cur.name, sym); rowMenu = false })
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
fun PortfolioScreen(open: (String) -> Unit) {
    val h by Repo.holdings.collectAsState()
    val quotes by Repo.quotes.collectAsState()
    val pivots by Repo.pivots.collectAsState()
    var chg by rememberSaveable { mutableStateOf("All") }
    var pv by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    fun load() {
        scope.launch {
            loading = true
            err = withContext(Dispatchers.IO) { runCatching { Repo.refreshHoldings() }.exceptionOrNull()?.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val invested = h.sumOf { it.avg * it.qty }
    val current = h.sumOf { it.ltp * it.qty }
    val pnl = current - invested
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Current value", color = Muted, fontSize = 13.sp)
                Text("₹" + f2(current), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Invested ₹" + f2(invested) + "  •  P&L " + signedRs(pnl) +
                    (if (invested > 0) " (" + pctStr(pnl / invested * 100) + ")" else ""), color = chgColor(pnl), fontSize = 13.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        err?.let { Text(it, color = Red, modifier = Modifier.padding(horizontal = 16.dp)) }
        HorizontalDivider()
        val today = java.time.LocalDate.now()
        val shown = h.filter { x ->
            val q = quotes[x.symbol]
            matchesFilter(q?.ltp?.takeIf { it > 0 } ?: x.ltp, q?.pct, chg, pvSet(pv), pivots[x.symbol])
        }
        PivotLoader(pv.isNotEmpty(), h.map { it.symbol })
        FilterBar(chg, { chg = it }, pv, { pv = it }, shown.size, h.size, h.count { pivots[it.symbol]?.day == today })
        HorizontalDivider()
        if (h.isNotEmpty() && shown.isEmpty()) {
            Text("No holdings match the current filters.", color = Muted, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.symbol + it.token }) { x ->
                Row(Modifier.fillMaxWidth().clickable { open(x.symbol) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(x.symbol, fontSize = 18.sp)
                        Text("${x.qty} × avg ₹" + f2(x.avg), color = Muted, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("₹" + f2(x.ltp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(signedRs(x.pnl) + " (" + pctStr(x.pnlPct) + ")", color = chgColor(x.pnl), fontSize = 13.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
fun OrdersScreen() {
    val o by Repo.orders.collectAsState()
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    fun load() {
        scope.launch { err = withContext(Dispatchers.IO) { runCatching { Repo.refreshOrders() }.exceptionOrNull()?.message } }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Today's orders", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        err?.let { Text(it, color = Red, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (o.isEmpty() && err == null) Text("No orders today.", color = Muted, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(o) { x ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(x.symbol, fontSize = 17.sp)
                        Text(x.status.uppercase(), color = if (x.status.contains("reject", true) || x.status.contains("cancel", true)) Red else Green, fontSize = 13.sp)
                    }
                    Text("${x.side} ${x.qty} • ${x.type} • ₹${x.price}  ${x.time}", color = Muted, fontSize = 13.sp)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AccountScreen() {
    val ctx = LocalContext.current
    val running by Repo.running.collectAsState()
    val status by Repo.status.collectAsState()
    val scope = rememberCoroutineScope()
    val c = remember { Store.creds() }
    var key by remember { mutableStateOf(c.apiKey) }
    var client by remember { mutableStateOf(c.client) }
    var pin by remember { mutableStateOf(c.pin) }
    var totp by remember { mutableStateOf(c.totp) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Angel One account", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        IpCard()
        Text("Stored encrypted on this phone only. Get the API key from smartapi.angelone.in (create an app), and the TOTP secret from the QR setup page (the text code, not the 6-digit number).", color = Muted, fontSize = 13.sp)
        OutlinedTextField(key, { key = it }, label = { Text("SmartAPI key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(client, { client = it }, label = { Text("Client code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(totp, { totp = it }, label = { Text("TOTP secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            Store.saveCreds(Creds(key, client, pin, totp)); Api.jwt = null
            MonitorService.start(ctx)
        }, modifier = Modifier.fillMaxWidth()) { Text("Save & start") }
        HorizontalDivider()
        Text("Background service: " + if (running) "Running" else "Stopped", fontWeight = FontWeight.SemiBold,
            color = if (running) Green else Red)
        Text(status, color = Muted, fontSize = 13.sp)
        if (running) OutlinedButton(onClick = { MonitorService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        else Button(onClick = { MonitorService.start(ctx) }, enabled = Store.hasCreds(), modifier = Modifier.fillMaxWidth()) { Text("Start") }
        OutlinedButton(onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Instruments.load(ctx, true) } }
                Toast.makeText(ctx, if (r.isSuccess) "Stock list updated" else "Failed: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Refresh stock, F&O and MCX list") }
    }
}
