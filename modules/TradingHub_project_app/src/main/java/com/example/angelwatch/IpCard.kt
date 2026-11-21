package com.example.angelwatch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shows the phone's current public IP (re-checked every 30 s, so a VPN or network switch shows up). */
@Composable
fun IpCard() {
    val ctx = LocalContext.current
    var ip by remember { mutableStateOf(Net.lastIp) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        while (true) {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { Net.publicIp() } }
            r.onSuccess { ip = it; err = null }.onFailure { err = it.message }
            busy = false
            delay(30_000)
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Blue.copy(alpha = 0.08f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your current IP address", color = Muted, fontSize = 13.sp)
            Text(ip ?: if (busy) "Checking…" else "Unknown", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            err?.let { Text(it, color = Red, fontSize = 12.sp) }
            Text("Angel One accepts orders only from the IP you registered in your SmartAPI app. Prices, alerts and screeners work from any IP.",
                color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tick++ }, enabled = !busy) { Text("Refresh") }
                OutlinedButton(onClick = {
                    val v = ip
                    if (v != null) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("IP", v))
                        Toast.makeText(ctx, "Copied $v", Toast.LENGTH_SHORT).show()
                    }
                }, enabled = ip != null) { Text("Copy") }
            }
        }
    }
}
