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
import androidx.glance.color.ColorProvider as DayNight
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

object Widgets {
    fun refresh(ctx: Context) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { val c = ctx.applicationContext; SmallDoseWidget().updateAll(c); DoseWidget().updateAll(c); LargeDoseWidget().updateAll(c) } }
    }
}

/** Today's plan items with their med + record, ordered by time. */
fun todayItems(d: AppData, date: LocalDate = LocalDate.now()) = planFor(d, date).mapNotNull { p ->
    val m = d.medicines.find { it.id == p.medId } ?: return@mapNotNull null
    Triple(m, DoseSlot(p.slotId, p.label, p.minutes / 60, p.minutes % 60), d.recordFor(p.medId, p.slotId, date))
}


private val Blue = Color(0xFF1677FF)
private val BlueDark = Color(0xFF4C9AFF)
private object WC {
    val bg = DayNight(day = Blue, night = Color(0xFF1B2230))
    val fg = DayNight(day = Color.White, night = Color(0xFFE8EEF8))
    val sub = DayNight(day = Color(0xCCFFFFFF), night = Color(0xFF9DB0CC))
    val btnBg = DayNight(day = Color.White, night = BlueDark)
    val btnFg = DayNight(day = Blue, night = Color.White)
    val done = DayNight(day = Color(0xFFB7F5C8), night = Color(0xFF52C41A))
    val skip = DayNight(day = Color(0xFFFFD8A8), night = Color(0xFFFAAD14))
    val track = DayNight(day = Color(0x55FFFFFF), night = Color(0xFF2E3A4F))
}

internal data class WState(val items: List<Triple<Medicine, DoseSlot, Record?>>, val taken: Int, val next: Triple<Medicine, DoseSlot, Record?>?)

private fun nowMin() = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
private fun nextOf(d: AppData, items: List<Triple<Medicine, DoseSlot, Record?>>) =
    checkInTarget(d, LocalDate.now(), nowMin())?.let { t -> items.find { it.first.id == t.medId && it.second.id == t.slotId } }
private fun stateOf(d: AppData): WState { val i = todayItems(d); return WState(i, i.count { it.third?.status == Status.TAKEN }, nextOf(d, i)) }
private fun nameOf(t: Triple<Medicine, DoseSlot, Record?>) = listOf(t.second.label, t.first.name).filter { it.isNotBlank() }.joinToString(" ")
private fun emptyText(s: WState) = if (s.items.isEmpty()) "今日无服药计划" else "今日已全部完成 ✓"

/** Base: shared data loading, container and check-in button. */
abstract class BaseDoseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Repo.init(context)
        provideContent {
            val d by Repo.data.collectAsState()
            Box(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(WC.bg).clickable(actionStartActivity<MainActivity>())) {
                Body(stateOf(d), LocalSize.current)
            }
        }
    }
    @Composable internal abstract fun Body(s: WState, size: DpSize)
}

@Composable
private fun CheckButton(s: WState, compact: Boolean = false, modifier: GlanceModifier = GlanceModifier) {
    if (s.next == null) return
    Box(modifier.cornerRadius(16.dp).background(WC.btnBg).padding(horizontal = if (compact) 12.dp else 0.dp, vertical = if (compact) 6.dp else 8.dp)
        .clickable(actionRunCallback<TakeNextAction>()), contentAlignment = Alignment.Center) {
        Text("打卡", style = TextStyle(color = WC.btnFg, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Bold))
    }
}

/** 小 2×1: next dose time + button. */
class SmallDoseWidget : BaseDoseWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 40.dp), DpSize(180.dp, 40.dp), DpSize(110.dp, 90.dp)))
    @Composable override fun Body(s: WState, size: DpSize) {
        val n = s.next
        val tall = size.height >= 90.dp
        if (tall) Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
            Text(if (n == null) emptyText(s) else n.second.timeText, style = TextStyle(color = WC.fg, fontSize = if (n == null) 13.sp else 22.sp, fontWeight = FontWeight.Bold), maxLines = 2)
            if (n != null) Text(nameOf(n), style = TextStyle(color = WC.sub, fontSize = 12.sp), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            CheckButton(s, modifier = GlanceModifier.fillMaxWidth())
        } else Row(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                if (n == null) Text(emptyText(s), style = TextStyle(color = WC.fg, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                else {
                    Text(n.second.timeText, style = TextStyle(color = WC.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    if (size.width >= 180.dp) Text(nameOf(n), style = TextStyle(color = WC.sub, fontSize = 11.sp), maxLines = 1)
                }
            }
            CheckButton(s, compact = true)
        }
    }
}

/** 中 2×2: progress + next dose + button. */
class DoseWidget : BaseDoseWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(180.dp, 110.dp), DpSize(110.dp, 180.dp)))
    @Composable override fun Body(s: WState, size: DpSize) {
        Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
            Text("吃药打卡", style = TextStyle(color = WC.sub, fontSize = 12.sp))
            Text("${s.taken}/${s.items.size}", style = TextStyle(color = WC.fg, fontSize = if (size.width >= 180.dp) 26.sp else 22.sp, fontWeight = FontWeight.Bold))
            LinearProgressIndicator(progress = if (s.items.isEmpty()) 0f else s.taken.toFloat() / s.items.size,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp), color = WC.fg, backgroundColor = WC.track)
            Spacer(GlanceModifier.height(6.dp))
            val n = s.next
            Text(if (n == null) emptyText(s) else "下一次 ${n.second.timeText} · ${nameOf(n)}", style = TextStyle(color = WC.fg, fontSize = 13.sp),
                maxLines = if (size.height >= 180.dp) 3 else 2)
            Spacer(GlanceModifier.defaultWeight())
            CheckButton(s, modifier = GlanceModifier.fillMaxWidth())
        }
    }
}

/** 大 4×2: today's list with status + button. */
class LargeDoseWidget : BaseDoseWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp), DpSize(250.dp, 260.dp)))
    @Composable override fun Body(s: WState, size: DpSize) {
        Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text("今日 ${s.taken}/${s.items.size}", style = TextStyle(color = WC.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Text(s.next?.let { "下一次 ${it.second.timeText}" } ?: emptyText(s), style = TextStyle(color = WC.sub, fontSize = 12.sp), maxLines = 1)
                }
                CheckButton(s, compact = true)
            }
            Spacer(GlanceModifier.height(6.dp))
            val rows = ((size.height.value - 70f) / 22f).toInt().coerceAtLeast(1)
            val shown = s.items.take(rows)
            shown.forEach { t ->
                val st = t.third?.status
                Row(GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.second.timeText, style = TextStyle(color = WC.fg, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(nameOf(t), style = TextStyle(color = WC.fg, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Text(when (st) { Status.TAKEN -> "已服 ✓"; Status.SKIPPED -> "跳过"; null -> if (t === s.next) "待打卡" else "未服" },
                        style = TextStyle(color = when (st) { Status.TAKEN -> WC.done; Status.SKIPPED -> WC.skip; null -> WC.sub }, fontSize = 12.sp))
                }
            }
            if (s.items.size > shown.size) Text("还有 ${s.items.size - shown.size} 项…", style = TextStyle(color = WC.sub, fontSize = 11.sp))
        }
    }
}

class TakeNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.init(context)
        val dd = Repo.data.value
        val next = nextOf(dd, todayItems(dd)) ?: return
        Repo.record(context, next.first, next.second, LocalDate.now(), Status.TAKEN)
        Alarms.cancelNotification(context, next.first.id, next.second.id)
        Widgets.refresh(context)
    }
}

class SmallDoseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SmallDoseWidget() }
class DoseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DoseWidget() }
class LargeDoseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LargeDoseWidget() }
