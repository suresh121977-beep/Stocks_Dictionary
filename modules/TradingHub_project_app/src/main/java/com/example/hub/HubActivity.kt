@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angelwatch.Blue
import com.example.angelwatch.Green
import com.example.angelwatch.Muted
import com.example.angelwatch.Red
import com.example.angelwatch.Repo
import com.example.angelwatch.marketOpen
import kotlinx.coroutines.delay
import com.example.angelwatch.MainActivity as AngelWatchActivity
import com.example.angelwatch.MonitorService as AngelWatchService
import com.example.angelwatch.Store as AngelWatchStore
import com.example.autosquare.ASState
import com.example.autosquare.MainActivity as AutoSquareActivity
import com.example.autosquare.MonitorService as AutoSquareService

/** Launcher: one grid of menus, each opening its own app screen. */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AngelWatchStore.init(this)
        val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) { HubScreen() }
        }
    }
}

private val HubBg = Color(0xFFFBF8FF)
private val TileBg = Color(0xFFF6F3FB)

@Composable
fun HubScreen() {
    val ctx = LocalContext.current
    val awRunning by Repo.running.collectAsState()
    var asRunning by remember { mutableStateOf(AutoSquareService.running) }
    LaunchedEffect(Unit) {
        while (true) { asRunning = AutoSquareService.running; delay(1000) }
    }

    Column(
        Modifier.fillMaxSize().background(HubBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text("Trading Hub", fontSize = 32.sp, fontWeight = FontWeight.Light, color = Color(0xFF1B1B1F), modifier = Modifier.padding(start = 6.dp, bottom = 20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HubTile("AngelWatch", Icons.Filled.Visibility, Color(0xFF3B4FD8), Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, AngelWatchActivity::class.java))
            }
            HubTile("AutoSquare", Icons.Filled.FlashOn, Color(0xFFE65100), Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, AutoSquareActivity::class.java))
            }
            HubTile("Screener", Icons.Filled.Search, Color(0xFF0B6E7A), Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, ScreenerActivity::class.java))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("Background services", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = TileBg), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                ServiceRow("AngelWatch monitor", awRunning, onStop = { AngelWatchService.stop(ctx) }, onStart = {
                    if (AngelWatchStore.hasCreds()) AngelWatchService.start(ctx)
                    else Toast.makeText(ctx, "Enter your Angel One login in AngelWatch > Account first", Toast.LENGTH_LONG).show()
                })
                ServiceRow("AutoSquare monitor", asRunning, onStop = {
                    ctx.startService(Intent(ctx, AutoSquareService::class.java).setAction("STOP"))
                }, onStart = {
                    if (ASState.store(ctx).get("apiKey").isNotEmpty()) ctx.startForegroundService(Intent(ctx, AutoSquareService::class.java))
                    else Toast.makeText(ctx, "Enter your Angel One login in AutoSquare > Setup first", Toast.LENGTH_LONG).show()
                })
            }
        }
        Text(
            "Services keep polling prices while running, even after the market closes. Use Stop when you are done for the day.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp)
        )
    }
}

@Composable
private fun HubTile(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = modifier.height(124.dp), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = TileBg), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun ServiceRow(name: String, running: Boolean, onStop: () -> Unit, onStart: () -> Unit) {
    val col = if (running) Green else Red
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).clip(CircleShape).background(col))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text((if (running) "Running" else "Stopped") + " • " + (if (marketOpen()) "market open" else "market closed"), color = Muted, fontSize = 12.sp)
        }
        if (running) OutlinedButton(onClick = onStop) { Text("Stop", color = Red) }
        else TextButton(onClick = onStart) { Text("Start") }
    }
}
