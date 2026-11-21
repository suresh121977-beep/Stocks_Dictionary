@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.angelwatch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Pivot filter keys: first letter A(bove)/B(elow), second letter D(aily)/W(eekly)/M(onthly). */
val PIVOT_KEYS = listOf(
    "AD" to "Above daily pivot", "AW" to "Above weekly pivot", "AM" to "Above monthly pivot",
    "BD" to "Below daily pivot", "BW" to "Below weekly pivot", "BM" to "Below monthly pivot"
)

fun pvSet(s: String): Set<String> = s.split(",").filter { it.isNotEmpty() }.toSet()

/** Toggles a pivot chip; picking "Above weekly" drops "Below weekly" because they can't both be true. */
fun togglePivot(cur: String, key: String): String {
    val set = pvSet(cur).toMutableSet()
    if (key in set) {
        set.remove(key)
    } else {
        set.add(key)
        set.remove((if (key[0] == 'A') "B" else "A") + key[1])
    }
    return set.sorted().joinToString(",")
}

/**
 * chg = "All" | "Gainers" | "Losers" (today's price change, from the live quote).
 * pv  = set of PIVOT_KEYS that must ALL hold against the live price.
 */
fun matchesFilter(ltp: Double, pct: Double?, chg: String, pv: Set<String>, p: PivotSet?): Boolean {
    if (chg == "Gainers" && (pct == null || pct <= 0.0)) return false
    if (chg == "Losers" && (pct == null || pct >= 0.0)) return false
    if (pv.isEmpty()) return true
    if (p == null || p.day != LocalDate.now() || ltp <= 0.0) return false
    for (k in pv) {
        val lvl: Double = when (k[1]) {
            'D' -> p.d
            'W' -> p.w
            else -> p.m
        } ?: return false
        if (k[0] == 'A') {
            if (ltp <= lvl) return false
        } else {
            if (ltp >= lvl) return false
        }
    }
    return true
}

/** Downloads history in the background so pivots exist for every stock while a pivot chip is on. */
@Composable
fun PivotLoader(active: Boolean, symbols: List<String>) {
    val ready by Instruments.ready.collectAsState()
    val key = symbols.joinToString(",")
    LaunchedEffect(active, key, ready) {
        if (!active || !ready) return@LaunchedEffect
        while (isActive) {
            val n = withContext(Dispatchers.IO) { Repo.ensurePivots(symbols, 3) }
            if (n == 0) break
        }
    }
}

/** One slim row: a Filter button that opens a dropdown (price change, signals, pivots) + a short summary. */
@Composable
fun FilterBar(
    chg: String, onChg: (String) -> Unit,
    pv: String, onPv: (String) -> Unit,
    shown: Int, total: Int, pivotsReady: Int,
    sg: String = "", onSg: ((String) -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val active = pvSet(pv)
    val count = (if (chg != "All") 1 else 0) + active.size + (if (sg != "") 1 else 0)

    @Composable
    fun Head(t: String) = Text(t, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

    @Composable
    fun Pick(label: String, checked: Boolean, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (checked) Icon(Icons.Filled.Check, null, Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
        onClick = onClick
    )

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) {
                Icon(Icons.Filled.FilterList, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (count > 0) "Filter ($count)" else "Filter")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Head("Price change")
                Pick("All", chg == "All") { onChg("All") }
                Pick("▲ Price up", chg == "Gainers") { onChg("Gainers") }
                Pick("▼ Price down", chg == "Losers") { onChg("Losers") }
                if (onSg != null) {
                    HorizontalDivider()
                    Head("30-min breakout signal (F&O, MCX)")
                    Pick("▲ Buy signal", sg == "BUY") { onSg(if (sg == "BUY") "" else "BUY") }
                    Pick("▼ Sell signal", sg == "SELL") { onSg(if (sg == "SELL") "" else "SELL") }
                }
                HorizontalDivider()
                Head("Pivot (live price vs level)")
                PIVOT_KEYS.forEach { (k, label) -> Pick(label, k in active) { onPv(togglePivot(pv, k)) } }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Clear all") }, onClick = { onChg("All"); onPv(""); onSg?.invoke("") })
                DropdownMenuItem(text = { Text("Done", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }, onClick = { open = false })
            }
        }
        Spacer(Modifier.width(10.dp))
        val loading = active.isNotEmpty() && pivotsReady < total
        Text(
            if (count > 0) "Showing $shown of $total" + (if (loading) " • pivots $pivotsReady/$total…" else "") else "",
            color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (count > 0) TextButton(onClick = { onChg("All"); onPv(""); onSg?.invoke("") }) { Text("Clear") }
    }
}
