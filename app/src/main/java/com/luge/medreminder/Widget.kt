package com.luge.medreminder

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

object Widgets {
    fun refresh(ctx: Context) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { DoseWidget().updateAll(ctx.applicationContext) } }
    }
}

/** Today's plan items with their med + record, ordered by time. */
fun todayItems(d: AppData, date: LocalDate = LocalDate.now()) = planFor(d, date).mapNotNull { p ->
    val m = d.medicines.find { it.id == p.medId } ?: return@mapNotNull null
    Triple(m, DoseSlot(p.slotId, p.label, p.minutes / 60, p.minutes % 60), d.recordFor(p.medId, p.slotId, date))
}

class DoseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Repo.init(context)
        provideContent {
            val d by Repo.data.collectAsState()
            WidgetContent(d)
        }
    }

    @Composable
    private fun WidgetContent(d: AppData) {
        val items = todayItems(d)
        val taken = items.count { it.third?.status == Status.TAKEN }
        val next = checkInTarget(d, LocalDate.now(), java.time.LocalTime.now().let { it.hour * 60 + it.minute })?.let { t -> items.find { it.first.id == t.medId && it.second.id == t.slotId } }
        val white = ColorProvider(Color.White)
        Column(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Color(0xFF1677FF)).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())) {
            Text("吃药打卡", style = TextStyle(color = ColorProvider(Color(0xCCFFFFFF)), fontSize = 12.sp))
            Text("今日 $taken/${items.size}", style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(4.dp))
            Text(if (next == null) (if (items.isEmpty()) "今日无服药计划" else "今日已全部完成 ✓")
                 else "下一次 ${next.second.timeText} · ${listOf(next.second.label, next.first.name).filter { it.isNotBlank() }.joinToString(" ")}",
                style = TextStyle(color = white, fontSize = 13.sp), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            if (next != null) Box(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(Color.White).padding(vertical = 8.dp)
                .clickable(actionRunCallback<TakeNextAction>()), contentAlignment = Alignment.Center) {
                Text("打卡", style = TextStyle(color = ColorProvider(Color(0xFF1677FF)), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

class TakeNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.init(context)
        val next = Repo.data.value.let { dd -> checkInTarget(dd, LocalDate.now(), java.time.LocalTime.now().let { it.hour * 60 + it.minute })?.let { t -> todayItems(dd).find { it.first.id == t.medId && it.second.id == t.slotId } } } ?: return
        Repo.record(context, next.first, next.second, LocalDate.now(), Status.TAKEN)
        Alarms.cancelNotification(context, next.first.id, next.second.id)
        DoseWidget().update(context, glanceId)
    }
}

class DoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DoseWidget()
}
