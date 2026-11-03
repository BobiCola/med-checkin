package com.luge.medreminder.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luge.medreminder.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 补打卡: choose the actual time taken (on [date]) and an optional note. Also used to edit an existing record. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakeupDialog(item: SlotItem, date: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val init = item.rec?.takenAt?.toLocalTime() ?: if (date == LocalDate.now()) LocalTime.now() else LocalTime.of(item.slot.hour, item.slot.minute)
    var mins by remember { mutableIntStateOf(init.hour * 60 + init.minute) }
    var note by remember { mutableStateOf(item.rec?.note ?: "") }
    val d by Repo.data.collectAsState()
    val w = d.windowFor(item.med.id)
    val ok = inWindow(item.slot.minutes, mins, w)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (item.rec == null) "补打卡 · ${item.slot.label} ${item.med.name}" else "编辑记录 · ${item.slot.label} ${item.med.name}") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$date  计划 ${item.slot.timeText}，选择实际服药时间：", color = TextSub)
                Text("实际时间须在打卡时段 ${windowText(item.slot.minutes, w)} 内", color = if (ok) Blue else Bad)
                HmPicker(mins) { mins = it }
                OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val ms = date.atTime(mins / 60, mins % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (Repo.record(ctx, item.med, item.slot, date, Status.TAKEN, ms, note.trim())) {
                    Alarms.cancelNotification(ctx, item.med.id, item.slot.id); onDismiss()
                }
            }) { Text(if (item.rec == null) "补卡（时段内）" else "保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
