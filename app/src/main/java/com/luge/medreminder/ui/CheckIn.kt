package com.luge.medreminder.ui

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luge.medreminder.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class SlotItem(val med: Medicine, val slot: DoseSlot, val rec: Record?)

fun slotsFor(d: AppData, date: LocalDate) = todayItems(d, date).map { (m, s, r) -> SlotItem(m, s, r) }

/** Returns (text, color) of a slot's status. */
@Composable
fun statusOf(item: SlotItem, date: LocalDate, threshold: Int): Pair<String, Color> {
    val r = item.rec
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    return when {
        r == null && (date.isBefore(LocalDate.now()) || (date == LocalDate.now() && now > item.slot.minutes + Repo.data.value.windowFor(item.med.id).after)) -> "漏服" to Bad
        r == null -> "待服" to Blue
        r.status == Status.SKIPPED -> "跳过" to Gray
        r.onTime(threshold) -> "准时" to Good
        else -> "迟到${r.delayMin()}分" to Warn
    }
}

@Composable
fun StatusChip(item: SlotItem, date: LocalDate, threshold: Int) {
    val (text, color) = statusOf(item, date, threshold)
    Box(Modifier.wrapContentWidth(unbounded = true).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, softWrap = false, maxLines = 1)
    }
}

@Composable
fun statusIcon(item: SlotItem, date: LocalDate, threshold: Int) = when (statusOf(item, date, threshold).first.take(2)) {
    "准时" -> Icons.Filled.CheckCircle; "迟到" -> Icons.Filled.Warning; "漏服" -> Icons.Filled.Close; "跳过" -> Icons.Filled.ArrowForward
    else -> Icons.Filled.Notifications
}

@Composable
fun ProgressRing(progress: Float, modifier: Modifier, stroke: Float = 14f, track: Color = Color.White.copy(alpha = .25f), color: Color = Color.White) {
    val p by animateFloatAsState(progress, spring(dampingRatio = 0.9f, stiffness = 60f), label = "ring")
    Canvas(modifier) {
        val st = Stroke(stroke * density / 2.5f, cap = StrokeCap.Round)
        val inset = st.width / 2
        val sz = androidx.compose.ui.geometry.Size(size.width - st.width, size.height - st.width)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = st)
        drawArc(color, -90f, 360f * p, false, Offset(inset, inset), sz, style = st)
    }
}

@Composable
fun CheckInScreen(d: AppData, modifier: Modifier) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val today = LocalDate.now()
    val items = slotsFor(d, today)
    val taken = items.count { it.rec?.status == Status.TAKEN }
    var now by remember { mutableStateOf(LocalTime.now()) }
    val nowMin = now.hour * 60 + now.minute
    val target = checkInTarget(d, today, nowMin)
    val next = target?.let { t -> items.find { it.med.id == t.medId && it.slot.id == t.slotId } }
    val upcoming = if (next == null) nextWindowOpen(d, today, nowMin)?.let { (p, start) -> items.find { it.med.id == p.medId && it.slot.id == p.slotId }?.let { it to start } } else null
    var lastTap by remember { mutableStateOf(0L) }
    var confirmUndo by remember { mutableStateOf<SlotItem?>(null) }
    var makeup by remember { mutableStateOf<SlotItem?>(null) }
    var celebrate by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
    fun take(it: SlotItem) {
        if (d.doseState(it.med.id, it.slot.id, it.slot.minutes, today, today, LocalTime.now().let { t -> t.hour * 60 + t.minute }) != DoseState.OPEN) {
            android.widget.Toast.makeText(ctx, "不在打卡时段内（${windowText(it.slot.minutes, d.windowFor(it.med.id))}）", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        // debounce: a double tap must not also record the following dose
        val t = System.currentTimeMillis(); if (t - lastTap < 1500) return; lastTap = t
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!Repo.record(ctx, it.med, it.slot, today, Status.TAKEN)) {
            android.widget.Toast.makeText(ctx, "不在打卡时段内", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        Alarms.cancelNotification(ctx, it.med.id, it.slot.id)
        celebrate++
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---------- gradient header ----------
            Box(Modifier.fillMaxWidth().background(BlueGradient, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))) {
                Column(Modifier.statusBarsPadding().padding(20.dp, 12.dp, 20.dp, 56.dp)) {
                    Text("吃药打卡", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(today.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(today.format(DateTimeFormatter.ofPattern("yyyy年 EEEE", Locale.CHINA)), color = Color.White.copy(alpha = .8f), fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            Text(when {
                                items.isEmpty() -> "先去设置里添加药物吧"
                                taken == items.size -> "今天的药都吃完啦，真棒 🎉"
                                else -> "今天还有 ${items.count { it.rec == null }} 次待服"
                            }, color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                        }
                        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                            ProgressRing(if (items.isEmpty()) 0f else taken.toFloat() / items.size, Modifier.fillMaxSize())
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AnimatedValue("$taken/${items.size}") { v -> Text(v, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                                Text("今日进度", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            // ---------- big punch card ----------
            WhiteCard(Modifier.padding(horizontal = 16.dp).offset(y = (-40).dp), radius = 16.dp) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (next != null) {
                        Text(listOf(next.slot.label, next.med.name).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        Text("计划 ${next.slot.timeText}" + if (next.med.dosage.isNotBlank()) " · ${next.med.dosage}" else "", fontSize = 13.sp, color = TextSub)
                        d.inventory[next.med.id]?.takeIf { it.enabled }?.let { inv ->
                            Text("库存 ${fmtQty(inv.remaining)} ${inv.unit}" + if (inv.isLow()) " · 库存不足" else "", fontSize = 12.sp, color = if (inv.isLow()) Warn else TextSub)
                        }
                        Spacer(Modifier.height(20.dp))
                        PunchButton(now) {
                            // re-resolve at tap time from the latest state so displayed == recorded
                            val cur = checkInTarget(Repo.data.value, today, LocalTime.now().let { it.hour * 60 + it.minute })
                            if (cur != null && cur.medId == next.med.id && cur.slotId == next.slot.id) take(next)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("跳过本次", color = TextSub, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            Repo.record(ctx, next.med, next.slot, today, Status.SKIPPED); Alarms.cancelNotification(ctx, next.med.id, next.slot.id)
                        }.padding(8.dp, 4.dp))
                    } else if (upcoming != null) {
                        val (u, start) = upcoming
                        Text(listOf(u.slot.label, u.med.name).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        Text("计划 ${u.slot.timeText} · 可打卡 ${windowText(u.slot.minutes, d.windowFor(u.med.id))}", fontSize = 13.sp, color = TextSub)
                        Spacer(Modifier.height(16.dp))
                        val left = start * 60 - now.toSecondOfDay()
                        Text("距离可打卡还有", fontSize = 13.sp, color = TextSub)
                        Text(countdown(left), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Blue)
                        Text("${minText(start)} 后可打卡", fontSize = 13.sp, color = TextSub)
                    } else if (items.any { it.rec == null }) {
                        IconCircle(Icons.Filled.Close, Bad, 72.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("今日剩余剂次已过打卡时段", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("可在下方列表对漏服剂次「补卡」（时间需在打卡时段内）", fontSize = 13.sp, color = TextSub)
                    } else {
                        IconCircle(Icons.Filled.CheckCircle, if (items.isEmpty()) Gray else Good, 72.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (items.isEmpty()) "还没有服药计划" else "今日已全部完成", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (items.isEmpty()) "到「设置」添加药物和服药时间" else "继续保持好习惯", fontSize = 13.sp, color = TextSub)
                    }
                }
            }
            // ---------- per-dose list ----------
            if (items.isNotEmpty()) Column(Modifier.offset(y = (-28).dp).padding(horizontal = 16.dp)) {
                SectionTitle("今日服药 · ${items.size} 次")
                WhiteCard {
                    items.forEachIndexed { i, it ->
                        key(it.med.id + it.slot.id) {
                            Box(Modifier.entrance("dose-${it.med.id}-${it.slot.id}", i)) {
                                DoseRow(it, d, now, onTake = { take(it) }, onUndo = { confirmUndo = it }, onMakeup = { makeup = it })
                            }
                            if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        SuccessOverlay(celebrate)
    }
    makeup?.let { MakeupDialog(it, today) { makeup = null } }
    confirmUndo?.let { u ->
        AlertDialog(onDismissRequest = { confirmUndo = null },
            title = { Text("撤销打卡？") },
            text = { Text("将撤销「${u.med.name} ${u.slot.label} ${u.slot.timeText}」的记录。") },
            confirmButton = { TextButton({ Repo.undo(ctx, u.med.id, u.slot.id, today); confirmUndo = null }) { Text("撤销", color = Bad) } },
            dismissButton = { TextButton({ confirmUndo = null }) { Text("取消") } })
    }
}

@Composable
fun PunchButton(now: LocalTime, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = Standard)), label = "p")
    val breathe by inf.animateFloat(1f, 1.03f, infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "b")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "press")
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            listOf(pulse, (pulse + .5f) % 1f).forEach { p ->
                drawCircle(Blue.copy(alpha = (1 - p) * (1 - p) * 0.12f), radius = size.minDimension / 2 * (0.74f + 0.26f * p))
            }
        }
        Box(Modifier.size(140.dp).scale(breathe * press).shadow(12.dp, CircleShape, spotColor = Blue).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF69B1FF), Blue, BlueDark)))
            .clickable(src, indication = ripple(color = Color.White.copy(alpha = .5f))) { onClick() },
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("打卡", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("%02d:%02d:%02d".format(now.hour, now.minute, now.second), color = Color.White.copy(alpha = .9f), fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun DoseRow(it: SlotItem, d: AppData, now: LocalTime, onTake: () -> Unit, onUndo: () -> Unit, onMakeup: () -> Unit = {}) {
    val today = LocalDate.now()
    val threshold = d.lateFor(it.med.id)
    val (_, color) = statusOf(it, today, threshold)
    val r = it.rec
    val w = d.windowFor(it.med.id)
    val nowMin = now.hour * 60 + now.minute
    val state = d.doseState(it.med.id, it.slot.id, it.slot.minutes, today, today, nowMin)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(statusIcon(it, today, threshold), color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(listOf(it.slot.label, it.med.name).filter { s -> s.isNotBlank() }.joinToString(" · "), Modifier.fillMaxWidth(),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val actual = if (r?.status == Status.TAKEN) r.takenAt.toLocalTime().let { t -> " · 实际 %02d:%02d".format(t.hour, t.minute) } else ""
            Text("计划 ${it.slot.timeText}$actual", fontSize = 12.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (line, lc) = when (state) {
                DoseState.BEFORE -> "${minText(it.slot.minutes - w.before)} 起 · ${shortLeft((it.slot.minutes - w.before) * 60 - now.toSecondOfDay())}" to TextSub
                DoseState.OPEN -> "可打卡 ${windowText(it.slot.minutes, w)}" to Blue
                DoseState.MISSED -> "已过时段 ${windowText(it.slot.minutes, w)}" to Bad
                DoseState.DONE -> (if (!r?.note.isNullOrBlank()) "备注：${r!!.note}" else "") to TextSub
            }
            if (line.isNotEmpty()) Text(line, fontSize = 12.sp, color = lc, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(min = 88.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(it, today, threshold)
            if (r == null) Button(onTake, enabled = state == DoseState.OPEN, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(34.dp).widthIn(min = 72.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text(when (state) { DoseState.BEFORE -> "未到"; DoseState.MISSED -> "已过"; else -> "打卡" }, fontSize = 13.sp, softWrap = false, maxLines = 1)
            }
        }
        Box {
            IconButton({ menu = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "更多", tint = TextSub) }
            DropdownMenu(menu, { menu = false }) {
                if (r == null) DropdownMenuItem({ Text("补卡（时段内）") }, { menu = false; onMakeup() })
                if (r?.status == Status.TAKEN) DropdownMenuItem({ Text("编辑时间/备注") }, { menu = false; onMakeup() })
                if (r != null) DropdownMenuItem({ Text("撤销") }, { menu = false; onUndo() })
            }
        }
    }
}

fun shortLeft(sec: Int): String { val m = (sec.coerceAtLeast(0) + 59) / 60; return if (m >= 60) "${m / 60}时${m % 60}分" else "${m}分" }

fun countdown(sec: Int): String { val s = sec.coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }

@Composable
fun SuccessOverlay(trigger: Int) {
    if (trigger == 0) return
    val backdrop = remember(trigger) { Animatable(0f) }
    val scale = remember(trigger) { Animatable(0.6f) }
    val check = remember(trigger) { Animatable(0f) }
    val burst = remember(trigger) { Animatable(0f) }
    var done by remember(trigger) { mutableStateOf(false) }
    val parts = remember(trigger) { List(14) { Triple(it * 360f / 14 + Random.nextFloat() * 12f, 0.65f + Random.nextFloat() * 0.35f, listOf(Blue, Good, Warn, Color(0xFF9254DE))[it % 4]) } }
    LaunchedEffect(trigger) {
        launch { backdrop.animateTo(1f, tween(220, easing = Standard)) }
        launch { burst.animateTo(1f, tween(900, easing = Emphasized)) }
        launch { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
        delay(140)
        check.animateTo(1f, tween(380, easing = Emphasized))
        delay(320)
        backdrop.animateTo(0f, tween(260, easing = EmphasizedAccel))
        done = true
    }
    if (done) return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.value }.background(Color.Black.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            val c = center; val b = burst.value
            drawCircle(Good.copy(alpha = (1 - b) * .18f), radius = size.minDimension / 2 * (0.4f + 0.6f * b))
            parts.forEach { (ang, sp, col) ->
                val rad = Math.toRadians(ang.toDouble()); val dist = size.minDimension / 2 * (0.3f + 0.7f * b) * sp
                drawCircle(col.copy(alpha = (1 - b) * .7f), 3.5.dp.toPx(), Offset(c.x + cos(rad).toFloat() * dist, c.y + sin(rad).toFloat() * dist))
            }
            val r = 52.dp.toPx() * scale.value
            drawCircle(Good, r, c)
            if (check.value > 0) {
                val p1 = Offset(c.x - r * .42f, c.y + r * .02f); val p2 = Offset(c.x - r * .1f, c.y + r * .34f); val p3 = Offset(c.x + r * .45f, c.y - r * .3f)
                val t = check.value; val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    if (t < .4f) lineTo(p1.x + (p2.x - p1.x) * t / .4f, p1.y + (p2.y - p1.y) * t / .4f)
                    else { lineTo(p2.x, p2.y); val u = (t - .4f) / .6f; lineTo(p2.x + (p3.x - p2.x) * u, p2.y + (p3.y - p2.y) * u) }
                }
                drawPath(path, Color.White, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text("打卡成功", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(y = 92.dp).graphicsLayer { alpha = check.value; translationY = (1 - check.value) * 12.dp.toPx() })
    }
}

fun fmtQty(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
