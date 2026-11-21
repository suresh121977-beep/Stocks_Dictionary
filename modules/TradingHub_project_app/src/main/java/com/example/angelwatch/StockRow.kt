package com.example.angelwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The compact list row used everywhere: name + exchange on the left, price + change on the right. Tap for details. */
@Composable
fun StockRow(
    title: String, sub: String, price: String, line2: String, onClick: () -> Unit,
    priceColor: Color = Color.Unspecified, line2Color: Color = Muted
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(price, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = priceColor)
            if (line2.isNotEmpty()) Text(line2, color = line2Color, fontSize = 13.sp)
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
