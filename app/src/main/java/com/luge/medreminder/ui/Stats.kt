package com.luge.medreminder.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luge.medreminder.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StatsScreen(d: AppData, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(20.dp, 12.dp, 8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("统计", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ exportReportPdf(ctx) }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" 报告") }
        }
        val labels = listOf("日视图", "月历", "药物总览")
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SegBg).padding(3.dp)) {
            val w = maxWidth / labels.size
            val x by animateDpAsState(w * tab, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "ind")
            Box(Modifier.offset(x = x).width(w).height(36.dp).shadow(1.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(CardBg))
            Row(Modifier.fillMaxWidth().height(36.dp)) {
                labels.forEachIndexed { i, t ->
                    val c by animateColorAsState(if (tab == i) Blue else TextSub, tween(250, easing = Standard), label = "segc")
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { tab = i }, contentAlignment = Alignment.Center) {
                        Text(t, color = c, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
        val holder = rememberSaveableStateHolder()
        val off = dpPx(24)
        AnimatedContent(tab, label = "stats", transitionSpec = { fadeThrough(if (targetState > initialState) 1 else -1, off) }) { t ->
            holder.SaveableStateProvider(t) {
                when (t) {
                    0 -> DayView(d, date) { date = it }
                    1 -> MonthView(d) { date = it; tab = 0 }
                    else -> TotalView(d)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayView(d: AppData, date: LocalDate, onDate: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var showPicker by remember { mutableStateOf(false) }
    var makeup by remember { mutableStateOf<SlotItem?>(null) }
    val days = (59 downTo 0).map { today.minusDays(it.toLong()) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(date).takeIf { it >= 0 } ?: days.lastIndex).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        WhiteCard {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.weight(1f))
                IconButton({ showPicker = true }) { Icon(Icons.Filled.DateRange, "选择日期", tint = Blue) }
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp)) {
                items(days) { day ->
                    val sel = day == date
                    val v = dayCompletion(d, day, null, today)
                    val bg by animateColorAsState(if (sel) Blue else PageBg, tween(280, easing = Standard), label = "day")
                    Column(Modifier.width(48.dp).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onDate(day) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (day == today) "今天" else day.format(DateTimeFormatter.ofPattern("E", Locale.CHINA)), color = if (sel) Color.White else TextSub, fontSize = 11.sp)
                        Text("${day.dayOfMonth}", color = if (sel) Color.White else TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        val dot = when { v == null -> Color.Transparent; v >= 1f -> Good; v > 0f -> Warn; day.isBefore(today) -> Bad; else -> Gray.copy(alpha = .4f) }
                        Box(Modifier.padding(top = 3.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (sel && dot != Color.Transparent) Color.White else dot))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(date, label = "daylist", transitionSpec = { fadeThrough(0, 0) }) { dt ->
            val list = slotsFor(d, dt)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("当天没有服药计划", color = TextSub, modifier = Modifier.padding(8.dp))
                else WhiteCard {
                    list.forEachIndexed { i, it ->
                        val (_, color) = statusOf(it, dt, d.lateThresholdMin)
                        Row(Modifier.fillMaxWidth().clickable(enabled = !dt.isAfter(today) && it.rec?.status != Status.SKIPPED) { makeup = it }
                            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconCircle(statusIcon(it, dt, d.lateThresholdMin), color)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(listOf(it.slot.label, it.med.name).filter { s -> s.isNotBlank() }.joinToString(" · "), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextMain, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val actual = it.rec?.takenAt?.toLocalTime()?.let { t -> "%02d:%02d".format(t.hour, t.minute) }
                                Text("计划 ${it.slot.timeText} · 实际 ${if (it.rec?.status == Status.TAKEN) actual else "—"}", fontSize = 12.sp, color = TextSub, maxLines = 1, softWrap = false)
                                if (!it.rec?.note.isNullOrBlank()) Text("备注：${it.rec!!.note}", fontSize = 12.sp, color = TextSub, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp)); Box(Modifier.widthIn(min = 88.dp), contentAlignment = Alignment.CenterEnd) { StatusChip(it, dt, d.lateThresholdMin) }
                        }
                        if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp), color = Divider)
                    }
                }
                if (list.isNotEmpty() && !dt.isAfter(today)) Text("点按记录可补打卡 / 修改实际时间与备注", fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(8.dp))
            }
        }
    }
    makeup?.let { MakeupDialog(it, date) { makeup = null } }
    if (showPicker) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showPicker = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; showPicker = false }) { Text("确定") } },
            dismissButton = { TextButton({ showPicker = false }) { Text("取消") } }) { DatePicker(st) }
    }
}

/** Monthly calendar heatmap (all medicines). */
@Composable
fun MonthView(d: AppData, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var ym by remember { mutableStateOf(YearMonth.from(today)) }
    var dir by remember { mutableIntStateOf(1) }
    val dark = LocalDark.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhiteCard(radius = 16.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ dir = -1; ym = ym.minusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "上个月", tint = Blue) }
                AnimatedValue("${ym.year}年${ym.monthValue}月") { Text(it, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain) }
                Spacer(Modifier.weight(1f))
                IconButton({ dir = 1; ym = ym.plusMonths(1) }, enabled = ym < YearMonth.from(today)) { Icon(Icons.Filled.KeyboardArrowRight, "下个月", tint = if (ym < YearMonth.from(today)) Blue else TextSub) }
            }
            Row(Modifier.padding(horizontal = 12.dp)) { "一二三四五六日".forEach { Text("$it", Modifier.weight(1f), fontSize = 12.sp, color = TextSub, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            val off = dpPx(24)
            AnimatedContent(ym, label = "month", transitionSpec = { fadeThrough(dir, off) }) { m ->
                val first = m.atDay(1).dayOfWeek.value - 1
                val cells = first + m.lengthOfMonth()
                Column(Modifier.padding(12.dp, 6.dp, 12.dp, 12.dp)) {
                    for (row in 0 until (cells + 6) / 7) Row {
                        for (col in 0..6) {
                            val day = row * 7 + col - first + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp)) {
                                if (day in 1..m.lengthOfMonth()) {
                                    val dt = m.atDay(day)
                                    val v = if (dt.isAfter(today)) null else dayCompletion(d, dt, null, today)
                                    val bg = when { v == null -> Track; v >= 1f -> Blue; v > 0f -> Blue.copy(alpha = .35f); else -> Bad.copy(alpha = if (dark) .35f else .18f) }
                                    val alpha = remember(m) { Animatable(0f) }
                                    LaunchedEffect(m) { kotlinx.coroutines.delay((row * 7 + col) * 12L); alpha.animateTo(1f, tween(300, easing = Standard)) }
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(bg.copy(alpha = bg.alpha * alpha.value))
                                        .clickable(enabled = !dt.isAfter(today)) { onPick(dt) }, contentAlignment = Alignment.Center) {
                                        Text("$day", fontSize = 13.sp, color = if (v != null && v >= 1f) Color.White else TextMain,
                                            fontWeight = if (dt == today) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Blue to "全部完成", Blue.copy(alpha = .35f) to "部分", Bad.copy(alpha = .3f) to "未完成", Track to "无计划").forEach { (c, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); Text(" $t", fontSize = 11.sp, color = TextSub) }
                }
            }
        }
        val daysIn = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { !it.isAfter(today) }
        val vals = daysIn.mapNotNull { dayCompletion(d, it, null, today) }
        WhiteCard(radius = 16.dp) {
            Row(Modifier.padding(vertical = 14.dp)) {
                Metric("计划天数", "${vals.size}"); Metric("全勤天数", "${vals.count { it >= 1f }}", Blue)
                Metric("月完成率", if (vals.isEmpty()) "--" else "${(vals.average() * 100).toInt()}%", Good)
            }
        }
        Text("点按日期查看当天明细", fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun TotalView(d: AppData) {
    var range by remember { mutableIntStateOf(7) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { r ->
                val sel = range == r
                val bg by animateColorAsState(if (sel) Blue else CardBg, tween(250, easing = Standard), label = "rg")
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable { range = r }.padding(14.dp, 6.dp)) {
                    Text("近 $r 天", color = if (sel) Color.White else TextMain, fontSize = 13.sp)
                }
            }
        }
        if (d.medicines.isEmpty()) Text("暂无药物", color = TextSub)
        d.medicines.forEachIndexed { idx, m ->
            val s = computeStats(d, m, range)
            Box(Modifier.entrance("stat-${m.id}", idx)) {
                WhiteCard(radius = 16.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconCircle(Icons.Filled.Favorite, Blue)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name.ifBlank { "未命名药物" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                Text(listOf(m.dosage, m.freqText()).filter { it.isNotBlank() }.joinToString(" · "), color = TextSub, fontSize = 12.sp)
                            }
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                ProgressRing(s.onTimeRate, Modifier.fillMaxSize(), stroke = 10f, track = Track, color = Good)
                                Text("${(s.onTimeRate * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Good)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(PageBg).padding(vertical = 12.dp)) {
                            Row { Metric("记录天数", "${s.daysTracked}"); Metric("已服", "${s.taken}", Blue); Metric("漏服", "${s.missed}", Bad) }
                            Spacer(Modifier.height(12.dp))
                            Row { Metric("跳过", "${s.skipped}", Gray); Metric("准时率", "${(s.onTimeRate * 100).toInt()}%", Good); Metric("连续全勤", "${s.streak}天", Warn) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("每日完成率", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        key(range) { BarChart(s.daily) }
                        Spacer(Modifier.height(12.dp))
                        Text("准时分析", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconCircle(Icons.Filled.Refresh, Warn, 32.dp); Spacer(Modifier.width(10.dp))
                            Text("平均延迟 ", color = TextSub, fontSize = 13.sp)
                            AnimatedValue(s.avgDelayMin.toInt()) { Text("$it", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Warn) }
                            Text(" 分钟", color = TextSub, fontSize = 13.sp)
                        }
                        if (s.slotForgetRate.isEmpty()) Text("暂无足够数据", fontSize = 12.sp, color = TextSub)
                        s.slotForgetRate.forEach { (slot, rate) -> RateBar("$slot 漏服率", rate) }
                    }
                }
            }
        }
    }
}

@Composable
fun RateBar(label: String, rate: Float) {
    val p by animateFloatAsState(rate, tween(800, easing = Emphasized), label = "rate")
    val track = Track
    val color = if (rate > .3f) Bad else if (rate > .1f) Warn else Good
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontSize = 12.sp, color = TextSub, maxLines = 1)
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(color, size = Size(size.width * p, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Text(" ${(rate * 100).toInt()}%", Modifier.width(44.dp), fontSize = 12.sp, color = color)
    }
}

@Composable
fun RowScope.Metric(label: String, value: String, color: Color = TextMain) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedValue(value) { v -> Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color) }
        Text(label, fontSize = 12.sp, color = TextSub)
    }
}

/** Bars with grow-in animation; date labels rotated -90° in reserved bottom area. */
@Composable
fun BarChart(daily: List<Pair<LocalDate, Float>>) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(800, easing = Emphasized)) }
    val labelArea = 44.dp
    val sub = TextSub; val track = Track; val guide = Divider
    Canvas(Modifier.fillMaxWidth().height(150.dp + labelArea).padding(top = 10.dp)) {
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 10.sp.toPx(); color = sub.toArgb(); textAlign = android.graphics.Paint.Align.RIGHT
        }
        val labelH = labelArea.toPx()
        val chartH = size.height - labelH
        val n = daily.size; val slot = size.width / n; val bw = slot * if (n > 7) 0.6f else 0.45f
        listOf(0f, .5f, 1f).forEach { g -> drawLine(guide, Offset(0f, chartH * (1 - g)), Offset(size.width, chartH * (1 - g)), 1.dp.toPx()) }
        daily.forEachIndexed { i, (dt, v) ->
            val x = i * slot + (slot - bw) / 2
            drawRoundRect(track, Offset(x, 0f), Size(bw, chartH), CornerRadius(bw / 3))
            val h = chartH * v * grow.value
            if (h > 0) drawRoundRect(if (v >= 1f) Blue else BlueLight.copy(alpha = .6f), Offset(x, chartH - h), Size(bw, h), CornerRadius(bw / 3))
            val cx = i * slot + slot / 2
            val nc = drawContext.canvas.nativeCanvas
            nc.save(); nc.translate(cx + paint.textSize / 3, chartH + 4.dp.toPx()); nc.rotate(-90f)
            nc.drawText("${dt.monthValue}/${dt.dayOfMonth}", 0f, 0f, paint); nc.restore()
        }
    }
}
