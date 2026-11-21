package com.example.angelwatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val PortfolioColor = Color(0xFFE65100)
val WatchColor = Color(0xFF3B4FD8)

@Composable
fun AlertsScreen(open: (String) -> Unit) {
    val items by Alerts.items.collectAsState()
    val on by Alerts.enabled.collectAsState()
    var voice by remember { mutableStateOf(Store.voice) }
    val fmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Alerts", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { Alerts.clear() }) { Text("Clear all") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (on) Green else Red)
            Spacer(Modifier.width(8.dp))
            Text(if (on) "Alerts are ON" else "Alerts are STOPPED", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (on) OutlinedButton(onClick = { Alerts.setEnabled(false) }) { Text("Stop alerts", color = Red) }
            else Button(onClick = { Alerts.setEnabled(true) }) { Text("Start alerts") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(PortfolioColor); Text("Portfolio (voice)", fontSize = 13.sp)
            Dot(WatchColor); Text("Watchlist (silent)", fontSize = 13.sp)
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(voice, { voice = it; Store.voice = it })
            Spacer(Modifier.width(10.dp))
            Text("Speak portfolio alerts", fontSize = 14.sp)
        }
        Text(if (on) "Alerts fire during market hours when a portfolio or watchlist stock falls below a support level or comes within 1% of a resistance level. Futures and options also alert (with voice) when they break the first 30-minute high or low and the 5-minute 20 SMA confirms."
            else "Alerts are stopped: no new alerts, voice or notifications until you press Start alerts. Prices keep updating while the background service runs.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        HorizontalDivider()
        if (items.isEmpty()) Text(if (on) "No alerts yet. Keep the background service running." else "No alerts. Alerts are stopped.", color = Muted, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.time.toString() + it.symbol + it.kind }) { a ->
                val sigAlert = a.kind.startsWith("SIGNAL")
                val col = if (sigAlert) (if (a.kind == "SIGNAL_BUY") Green else Red) else if (a.portfolio) PortfolioColor else WatchColor
                Card(Modifier.fillMaxWidth().clickable { open(if (a.kind == "INDEX") "IDX:" + (INDEX_DEFS.firstOrNull { it.label == a.symbol }?.key ?: "NIFTY50") else a.symbol) }, colors = CardDefaults.cardColors(containerColor = col.copy(alpha = 0.10f))) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(col))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (sigAlert) "30-MIN BREAKOUT • " + (if (a.kind == "SIGNAL_BUY") "▲ BUY SIGNAL" else "▼ SELL SIGNAL")
                                else if (a.kind == "INDEX") "INDEX • ⚠ CAUTION"
                                else (if (a.portfolio) "PORTFOLIO" else "WATCHLIST") + " • " + (if (a.kind == "BREACH") "⚠ SUPPORT BROKEN" else "↗ NEAR RESISTANCE"),
                                color = col, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(a.symbol, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.msg, fontSize = 13.sp)
                            Text(fmt.format(Date(a.time)), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(c: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
}
