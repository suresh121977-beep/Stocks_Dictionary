package com.example.stockscope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val Green = Color(0xFF2E7D32); private val Red = Color(0xFFC62828)
private val Palette = listOf(0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFFE53935, 0xFF00ACC1, 0xFF6D4C41, 0xFF7CB342, 0xFF5E35B1, 0xFFF4511E, 0xFF546E7A).map { Color(it) }
private val Sectors = listOf("Technology", "Healthcare", "Financial Services", "Consumer Cyclical", "Industrials",
    "Communication Services", "Consumer Defensive", "Energy", "Real Estate", "Utilities", "Basic Materials")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private fun cap(v: Double) = when {
    v >= 1e12 -> "$%.2fT".format(v / 1e12); v >= 1e9 -> "$%.1fB".format(v / 1e9); else -> "$%.0fM".format(v / 1e6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val repo = remember { YahooRepo() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var stock by remember { mutableStateOf<Stock?>(null) }
    var etf by remember { mutableStateOf<Etf?>(null) }
    var q by remember { mutableStateOf("") }
    var sector by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<StockRow>>(emptyList()) }
    var etfRows by remember { mutableStateOf<List<EtfRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tab, q, page, sector) {
        if (q.isNotBlank()) delay(500) // debounce typing
        loading = true; error = null
        try {
            if (tab == 0) { val r = repo.stocks(page, q, sector); rows = if (page == 0) r else rows + r }
            else etfRows = repo.etfs(q)
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    fun open(block: suspend () -> Unit) = scope.launch {
        loading = true; error = null
        try { block() } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    BackHandler(stock != null || etf != null) { stock = null; etf = null }

    Scaffold(topBar = { TopAppBar(title = { Text(stock?.symbol ?: etf?.symbol ?: "StockScope") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = Red, modifier = Modifier.padding(12.dp)) }
            when {
                stock != null -> StockDetail(stock!!) { sym -> open { stock = repo.stockDetail(sym) } }
                etf != null -> EtfDetail(etf!!)
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, { tab = 0; page = 0 }, text = { Text("Stocks") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("ETFs") })
                    }
                    OutlinedTextField(q, { q = it; page = 0 }, label = { Text("Search symbol or name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    if (tab == 0 && q.isBlank()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(sector.isEmpty(), { sector = ""; page = 0 }, label = { Text("All sectors") })
                            Sectors.forEach { s -> FilterChip(sector == s, { sector = s; page = 0 }, label = { Text(s) }) }
                        }
                    }
                    LazyColumn {
                        if (tab == 0) {
                            items(rows) { s ->
                                Row(Modifier.fillMaxWidth().clickable { open { stock = repo.stockDetail(s.symbol) } }.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${s.symbol} · ${s.name}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val sub = listOf(s.sector, if (s.marketCap > 0) cap(s.marketCap) else "").filter { it.isNotBlank() }.joinToString(" · ")
                                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (s.price > 0) Column(horizontalAlignment = Alignment.End) {
                                        Text("$" + "%.2f".format(s.price))
                                        Text("%+.2f%%".format(s.changePct), color = if (s.changePct >= 0) Green else Red,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider()
                            }
                            if (q.isBlank() && rows.size >= 100) item {
                                TextButton({ page += 1 }, Modifier.fillMaxWidth()) { Text("Load more") }
                            }
                        } else items(etfRows) { e ->
                            Column(Modifier.fillMaxWidth().clickable { open { etf = repo.etfDetail(e.symbol) } }.padding(16.dp)) {
                                Text("${e.symbol} · ${e.name}", fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StockDetail(s: Stock, onPeer: (String) -> Unit) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(s.name, style = MaterialTheme.typography.titleLarge)
            Text(listOf(s.sector, s.industry).filter { it.isNotBlank() }.joinToString(" · "))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$" + "%.2f".format(s.price), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(8.dp))
                Text("%+.2f%%".format(s.changePct), color = if (s.changePct >= 0) Green else Red)
            }
            Text("Analyst rating: ${s.rating}", fontWeight = FontWeight.Bold)
            if (s.hasTarget) {
                val p = s.premiumPct
                Text(if (p >= 0) "Trades at a ${"%.1f".format(p)}% premium to the analyst target of $${"%.2f".format(s.targetPrice)}"
                     else "Trades at a ${"%.1f".format(abs(p))}% discount to the analyst target of $${"%.2f".format(s.targetPrice)}",
                    color = if (p >= 0) Red else Green, fontWeight = FontWeight.Bold)
            }
            if (s.about.isNotBlank()) Text(s.about, style = MaterialTheme.typography.bodySmall)
        }
        if (s.revenue.isNotEmpty()) item {
            Text("Revenue ($" + "B) ${if (s.revenueUp) "▲ up" else "▼ down"} ${"%.1f".format(s.revenueGrowthPct)}% vs prior year",
                style = MaterialTheme.typography.titleMedium, color = if (s.revenueUp) Green else Red)
            BarChart(s.revenue.map { it.first.toString() to it.second }, "%.0f")
        }
        items(s.sections) { sec ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(sec.title, style = MaterialTheme.typography.titleMedium)
                    sec.rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(v, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        if (s.peers.isNotEmpty()) item {
            Text("Peers", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.peers.forEach { AssistChip(onClick = { onPeer(it) }, label = { Text(it) }) }
            }
        }
        if (s.holders.isNotEmpty()) {
            item { Text("Top institutional holders", style = MaterialTheme.typography.titleMedium) }
            items(s.holders) { Text("${it.name}: ${"%.2f".format(it.pct)}%") }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun EtfDetail(e: Etf) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(e.name, style = MaterialTheme.typography.titleLarge)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    e.stats.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(k, Modifier.weight(1f)); Text(v, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        item { Text("Asset allocation", style = MaterialTheme.typography.titleMedium); Donut(e.allocation) }
        item { Text("Sector exposure", style = MaterialTheme.typography.titleMedium); Donut(e.sectorExposure) }
        item { Text("Top holdings (% of fund)", style = MaterialTheme.typography.titleMedium); BarChart(e.topHoldings, "%.1f") }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun BarChart(data: List<Pair<String, Double>>, fmt: String) {
    if (data.isEmpty()) { Text("No data available"); return }
    val max = data.maxOf { it.second }.coerceAtLeast(0.0001)
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width / data.size
            data.forEachIndexed { i, (_, v) ->
                val h = size.height * (v / max).toFloat()
                val up = i == 0 || v >= data[i - 1].second
                drawRect(if (up) Green else Red, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            data.forEach { Text(it.first + "\n" + fmt.format(it.second), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun Donut(data: List<Pair<String, Double>>) {
    if (data.isEmpty()) { Text("No data available"); return }
    val total = data.sumOf { it.second }
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        var start = -90f
        val d = size.minDimension * 0.8f
        data.forEachIndexed { i, (_, v) ->
            val sweep = (v / total * 360).toFloat()
            drawArc(Palette[i % Palette.size], start, sweep, false,
                Offset((size.width - d) / 2, (size.height - d) / 2), Size(d, d), style = Stroke(40f))
            start += sweep
        }
    }
    data.forEachIndexed { i, (n, v) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) { drawCircle(Palette[i % Palette.size]) }
            Spacer(Modifier.width(8.dp)); Text(n + ": " + "%.1f".format(v) + "%")
        }
    }
}
