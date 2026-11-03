package com.luge.medreminder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luge.medreminder.minText

/** Plain 24-hour picker (no AM/PM dial): hour grid 0–23 + minute grid + ±1 min. Value = minutes since midnight. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HmPicker(value: Int, onChange: (Int) -> Unit) {
    val h = value / 60; val m = value % 60
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onChange((value + 1439) % 1440) }) { Text("−", fontSize = 22.sp) }
            Text(minText(value), Modifier.weight(1f), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Blue, textAlign = TextAlign.Center)
            IconButton({ onChange((value + 1) % 1440) }) { Text("+", fontSize = 22.sp) }
        }
        Text("时", fontSize = 12.sp, color = TextSub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 6) {
            (0..23).forEach { x -> HmCell("%02d".format(x), x == h, Modifier.weight(1f)) { onChange(x * 60 + m) } }
        }
        Text("分", fontSize = 12.sp, color = TextSub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 6) {
            (0..55 step 5).forEach { x -> HmCell("%02d".format(x), x == m, Modifier.weight(1f)) { onChange(h * 60 + x) } }
        }
    }
}

@Composable
private fun HmCell(t: String, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = if (sel) ButtonDefaults.buttonColors(containerColor = Blue) else ButtonDefaults.filledTonalButtonColors()
    Button(onClick, modifier.height(36.dp), colors = colors, contentPadding = PaddingValues(0.dp), shape = MaterialTheme.shapes.small) {
        Text(t, fontSize = 13.sp, softWrap = false, maxLines = 1)
    }
}
