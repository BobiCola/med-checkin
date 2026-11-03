package com.luge.medreminder.ui

import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import kotlin.math.roundToInt
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.luge.medreminder.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun SettingsScreen(d: AppData, modifier: Modifier) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Medicine?>(null) }
    var confirmDel by remember { mutableStateOf<Medicine?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<String?>(null) } // which option dialog
    var prefsTick by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Repo.backupJson(ctx).toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "备份已保存", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "备份失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            .onSuccess { txt -> if (runCatching { org.json.JSONObject(txt).has("medicines") }.getOrDefault(false)) pendingRestore = txt
                else Toast.makeText(ctx, "不是有效的备份文件", Toast.LENGTH_LONG).show() }
    }

    fun newMed() { editing = Medicine(slots = listOf(DoseSlot(label = "早", hour = 8), DoseSlot(label = "中", hour = 12), DoseSlot(label = "晚", hour = 20))) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("设置", Modifier.padding(4.dp, 12.dp, 4.dp, 12.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        // Alipay-like quick entries
        WhiteCard(radius = 16.dp) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                QuickEntry(Icons.Filled.Add, "新增药品", Blue) { newMed() }
                QuickEntry(Icons.Filled.Share, "导出CSV", Good) { exportCsv(ctx) }
                QuickEntry(Icons.Filled.List, "服药报告", Warn) { exportReportPdf(ctx) }
                QuickEntry(Icons.Filled.Lock, "备份", Color(0xFF9254DE)) { backupLauncher.launch("吃药打卡备份-$today.json") }
            }
        }

        SectionTitle("我的药品 · ${d.medicines.size}", Modifier.padding(top = 12.dp))
        if (d.medicines.isEmpty()) WhiteCard { Text("还没有药品，点击上方「新增药品」开始", Modifier.padding(16.dp), color = TextSub) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.medicines.forEachIndexed { idx, m ->
                Box(Modifier.entrance("set-${m.id}", idx)) {
                    MedicineCard(m, d.inventory[m.id], m.id in expanded, m.isEnded(today),
                        onToggle = { expanded = if (m.id in expanded) expanded - m.id else expanded + m.id },
                        onEdit = { editing = m }, onDelete = { confirmDel = m })
                }
            }
        }

        SectionTitle("打卡规则", Modifier.padding(top = 16.dp))
        WhiteCard {
            SettingRow(Icons.Filled.DateRange, Blue, "提前可打卡", "${d.winBefore} 分钟") { pick = "winBefore" }
            RowDivider()
            SettingRow(Icons.Filled.DateRange, Good, "延后可打卡", "${d.winAfter} 分钟") { pick = "winAfter" }
            RowDivider()
            Text("例：08:00 的剂次可在 ${windowText(480, Window(d.winBefore, d.winAfter))} 内打卡，超出时段视为漏服。可在药品编辑中单独设置。",
                fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(16.dp, 10.dp))
        }

        key(prefsTick) {
            SectionTitle("提醒", Modifier.padding(top = 16.dp))
            WhiteCard {
                SettingRow(Icons.Filled.Notifications, Blue, "提醒方式", Alarms.SOUND_OPTIONS.first { it.first == Prefs.sound(ctx) }.second) { pick = "sound" }
                RowDivider()
                SettingRow(Icons.Filled.Refresh, Good, "未打卡重复提醒", Prefs.repeatMin(ctx).let { if (it == 0) "关闭" else "每 $it 分钟 × ${Prefs.repeatCount(ctx)} 次" }) { pick = "repeat" }
                RowDivider()
                SettingRow(Icons.Filled.Warning, Warn, "迟到判定", "超过 ${minOf(d.lateThresholdMin, d.winAfter)} 分钟") { pick = "late" }
                RowDivider()
                SettingRow(Icons.Filled.Settings, Gray, "系统通知设置", "") {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    RowDivider()
                    SettingRow(Icons.Filled.DateRange, Color(0xFF13C2C2), "精确闹钟权限", "提醒更准时") {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
        }

        SectionTitle("数据", Modifier.padding(top = 16.dp))
        WhiteCard {
            SettingRow(Icons.Filled.Share, Good, "导出记录 CSV", "") { exportCsv(ctx) }
            RowDivider()
            SettingRow(Icons.Filled.List, Warn, "导出服药报告 PDF", "") { exportReportPdf(ctx) }
            RowDivider()
            SettingRow(Icons.Filled.Face, Color(0xFFEB2F96), "分享报告长图", "") { exportReportImage(ctx) }
            RowDivider()
            SettingRow(Icons.Filled.Lock, Color(0xFF9254DE), "备份到文件", "JSON") { backupLauncher.launch("吃药打卡备份-$today.json") }
            RowDivider()
            SettingRow(Icons.Filled.Refresh, Bad, "从备份恢复", "") { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }

        SectionTitle("关于", Modifier.padding(top = 16.dp))
        WhiteCard {
            SettingRow(Icons.Filled.Info, Blue, "吃药打卡", "v2.0") {}
            RowDivider()
            SettingRow(Icons.Filled.Home, Good, "数据存储", "仅保存在本机") {}
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { m ->
        MedicineWizard(m, d.inventory[m.id] ?: Inventory(m.id), isNew = d.medicines.none { it.id == m.id }, onDismiss = { editing = null }) { nm, inv ->
            Repo.updateMedicines(ctx) { dd ->
                dd.copy(medicines = if (dd.medicines.any { it.id == nm.id }) dd.medicines.map { if (it.id == nm.id) nm else it } else dd.medicines + nm,
                    inventory = dd.inventory + (nm.id to inv))
            }
            Alarms.rescheduleAll(ctx)
            editing = null
        }
    }
    confirmDel?.let { m ->
        AlertDialog(onDismissRequest = { confirmDel = null }, title = { Text("删除「${m.name}」？") }, text = { Text("该药品的打卡记录与库存也会一并删除，不可恢复。") },
            confirmButton = { TextButton({
                Repo.updateMedicines(ctx) { dd -> dd.copy(medicines = dd.medicines - m, records = dd.records.filterNot { it.medId == m.id }, inventory = dd.inventory - m.id) }
                Alarms.rescheduleAll(ctx); confirmDel = null
            }) { Text("删除", color = Bad) } },
            dismissButton = { TextButton({ confirmDel = null }) { Text("取消") } })
    }
    pendingRestore?.let { txt ->
        AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("从备份恢复？") },
            text = { Text("当前所有药品、打卡记录和设置将被备份文件中的内容覆盖。建议先备份当前数据。") },
            confirmButton = { TextButton({
                runCatching { Repo.restoreJson(ctx, txt) }
                    .onSuccess { Toast.makeText(ctx, "恢复成功", Toast.LENGTH_SHORT).show(); prefsTick++ }
                    .onFailure { Toast.makeText(ctx, "恢复失败：${it.message}", Toast.LENGTH_LONG).show() }
                pendingRestore = null
            }) { Text("覆盖恢复", color = Bad) } },
            dismissButton = { TextButton({ pendingRestore = null }) { Text("取消") } })
    }
    when (pick) {
        "sound" -> OptionDialog("提醒方式", Alarms.SOUND_OPTIONS, Prefs.sound(ctx), { pick = null }) { Prefs.set(ctx, "sound", it); prefsTick++ }
        "repeat" -> OptionDialog("未打卡重复提醒", listOf("0|1" to "关闭", "5|3" to "每 5 分钟 × 3 次", "10|3" to "每 10 分钟 × 3 次", "15|2" to "每 15 分钟 × 2 次", "30|2" to "每 30 分钟 × 2 次", "60|1" to "1 小时后 1 次"),
            "${Prefs.repeatMin(ctx)}|${Prefs.repeatCount(ctx)}", { pick = null }) { v ->
            val (a, b) = v.split("|"); Prefs.set(ctx, "repeatMin", a.toInt()); Prefs.set(ctx, "repeatCount", b.toInt()); prefsTick++ }
        "winBefore" -> StepperDialog("提前可打卡", "计划时间前多少分钟起可打卡", d.winBefore, { pick = null }) { Repo.setWindow(ctx, it, d.winAfter) }
        "winAfter" -> StepperDialog("延后可打卡", "计划时间后多少分钟内仍可打卡（迟到判定不超过此值）", d.winAfter, { pick = null }) { Repo.setWindow(ctx, d.winBefore, it) }
        "late" -> OptionDialog("迟到判定", listOf(10, 15, 30, 45, 60, 90, 120).filter { it <= maxOf(d.winAfter, 10) }.map { "$it" to "超过 $it 分钟算迟到" }, "${d.lateThresholdMin}", { pick = null }) { Repo.setLate(ctx, it.toInt()) }
    }
}

@Composable
fun RowScope.QuickEntry(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = if (LocalDark.current) .22f else .12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp)); Text(label, fontSize = 12.sp, color = TextMain)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = Divider)

@Composable
fun SettingRow(icon: ImageVector, color: Color, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(icon, color, 32.dp); Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 15.sp, color = TextMain)
        if (value.isNotBlank()) Text(value, fontSize = 13.sp, color = TextSub)
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = TextSub.copy(alpha = .6f))
    }
}

@Composable
fun MinStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = TextMain)
            IconButton({ onChange((value - 5).coerceAtLeast(0)) }, enabled = value > 0) { Text("−", fontSize = 20.sp) }
            Text("$value 分钟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Blue, modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton({ onChange((value + 5).coerceAtMost(WIN_MAX)) }, enabled = value < WIN_MAX) { Text("+", fontSize = 20.sp) }
        }
        Slider(value.toFloat(), { onChange((it / 5).roundToInt() * 5) }, valueRange = 0f..WIN_MAX.toFloat(), steps = WIN_MAX / 5 - 1)
    }
}

@Composable
fun StepperDialog(title: String, hint: String, init: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var v by remember { mutableIntStateOf(init) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column { Text(hint, fontSize = 13.sp, color = TextSub); Spacer(Modifier.height(8.dp)); MinStepper("时长", v) { v = it } } },
        confirmButton = { TextButton({ onSave(v); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
fun OptionDialog(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = { TextButton(onDismiss) { Text("关闭") } },
        text = {
            Column {
                options.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(k); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(k == selected, { onPick(k); onDismiss() }); Text(label)
                    }
                }
            }
        })
}

@Composable
fun TimeChip(text: String, color: Color = Blue, onClick: (() -> Unit)? = null) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = if (LocalDark.current) .22f else .10f))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MedicineCard(m: Medicine, inv: Inventory?, expanded: Boolean, ended: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val rot by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "arrow")
    WhiteCard(Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)), radius = 16.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Filled.Favorite, if (ended) Gray else Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name.ifBlank { "未命名药品" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                    if (ended) { Spacer(Modifier.width(6.dp)); TimeChip("已结束", Gray) }
                    if (inv?.isLow() == true) { Spacer(Modifier.width(6.dp)); TimeChip("库存低", Warn) }
                }
                Text("${m.freqText()} · 每次 ${m.slots.size} 个时间点" + if (m.dosage.isNotBlank()) " · ${m.dosage}" else "", fontSize = 12.sp, color = TextSub, maxLines = 1)
            }
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = TextSub, modifier = Modifier.rotate(rot))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = if (expanded) 4.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.slots.sortedBy { it.minutes }.forEach { s -> TimeChip((if (s.label.isBlank()) "" else s.label + " ") + s.timeText) }
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(220, easing = Standard)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)),
            exit = fadeOut(tween(120, easing = EmphasizedAccel)) + shrinkVertically(tween(220, easing = Standard))) {
            Column(Modifier.padding(16.dp, 8.dp, 16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("疗程", m.courseText())
                InfoLine("频率", m.freqText())
                InfoLine("库存", if (inv?.enabled == true) "剩余 ${fmtQty(inv.remaining)} ${inv.unit} · 每次 ${fmtQty(inv.perDose)} ${inv.unit} · 低于 ${fmtQty(inv.lowThreshold)} 提醒"
                    + (inv.daysLeft(m.slots.size)?.let { " · 约可用 $it 天" } ?: "") else "未开启")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDelete) { Icon(Icons.Filled.Delete, null, tint = Bad); Text(" 删除", color = Bad) }
                    Spacer(Modifier.width(8.dp))
                    Button(onEdit, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Text(" 编辑") }
                }
            }
        }
    }
}

@Composable
fun InfoLine(k: String, v: String) = Row {
    Text(k, Modifier.width(48.dp), fontSize = 13.sp, color = TextSub); Text(v, fontSize = 13.sp, color = TextMain)
}

/** 新增/编辑药品 step wizard: 基本信息 → 服药时间 → 疗程频率 → 库存. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicineWizard(init: Medicine, initInv: Inventory, isNew: Boolean, onDismiss: () -> Unit, onSave: (Medicine, Inventory) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var m by remember { mutableStateOf(init.sortedSlots()) }
    var inv by remember { mutableStateOf(initInv) }
    var pickFor by remember { mutableStateOf<DoseSlot?>(null) }
    var pickDate by remember { mutableStateOf<String?>(null) } // "start" / "end"
    var courseDays by remember { mutableStateOf("") }
    val steps = listOf("基本信息", "服药时间", "疗程频率", "库存")
    val canNext = when (step) { 0 -> m.name.isNotBlank(); 1 -> m.slots.isNotEmpty(); else -> true }
    FullScreenLayer(onDismiss) {
        Scaffold(Modifier.fillMaxSize(), containerColor = PageBg, contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭") }
                    Text(if (isNew) "新增药品" else "编辑药品", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                // step indicator
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    steps.forEachIndexed { i, t ->
                        val c by animateColorAsState(if (i <= step) Blue else Track, tween(300, easing = Standard), label = "st")
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                                if (i < step) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                else Text("${i + 1}", color = if (i <= step) Color.White else TextSub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(t, fontSize = 11.sp, color = if (i == step) Blue else TextSub, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (i < steps.lastIndex) Box(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 0.dp).offset(y = (-8).dp).height(2.dp).background(if (i < step) Blue else Track))
                    }
                }
            } },
            bottomBar = { Surface(color = CardBg, shadowElevation = 8.dp) { Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step > 0) OutlinedButton({ step-- }, Modifier.weight(1f).height(48.dp)) { Text("上一步") }
                    Button({ if (step < steps.lastIndex) step++ else onSave(m.copy(name = m.name.trim(), dosage = m.dosage.trim(), slots = m.slots.sortedBy { it.minutes }), inv.copy(medId = m.id)) },
                        Modifier.weight(1f).height(48.dp), enabled = canNext, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Text(if (step < steps.lastIndex) "下一步" else "保存")
                    }
                }
            } } }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                val off = dpPx(24)
                AnimatedContent(step, Modifier.weight(1f), label = "wiz", transitionSpec = { fadeThrough(if (targetState > initialState) 1 else -1, off) }) { s ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (s) {
                            0 -> WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(m.name, { m = m.copy(name = it) }, label = { Text("药品名称 *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(m.dosage, { m = m.copy(dosage = it) }, label = { Text("剂量 / 备注（如 每次1片，饭后）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            } }
                            1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("每天 ${m.slots.size} 次", fontWeight = FontWeight.SemiBold)
                                m.slots.forEach { sl ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(sl.label, { v -> m = m.copy(slots = m.slots.map { if (it.id == sl.id) it.copy(label = v) else it }) },
                                            label = { Text("时段") }, singleLine = true, modifier = Modifier.weight(1f))
                                        Spacer(Modifier.width(8.dp))
                                        FilledTonalButton({ pickFor = sl }) { Text(sl.timeText, fontSize = 16.sp) }
                                        IconButton({ m = m.copy(slots = m.slots - sl) }) { Icon(Icons.Filled.Close, "删除", tint = TextSub) }
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("早" to 8, "中" to 12, "晚" to 20, "睡前" to 22).forEach { (l, h) ->
                                        TimeChip("+ $l ${"%02d:00".format(h)}") { m = m.copy(slots = m.slots + DoseSlot(label = l, hour = h)).sortedSlots() }
                                    }
                                    TimeChip("+ 自定义", Good) { m = m.copy(slots = m.slots + DoseSlot(label = "", hour = 12)).sortedSlots() }
                                }
                            } }
                            run {
                                val g = Repo.data.value
                                val custom = m.winBefore != null || m.winAfter != null
                                WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("打卡时段", fontWeight = FontWeight.SemiBold)
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                        SegmentedButton(!custom, { m = m.copy(winBefore = null, winAfter = null) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("使用全局", softWrap = false, maxLines = 1) }
                                        SegmentedButton(custom, { if (!custom) m = m.copy(winBefore = g.winBefore, winAfter = g.winAfter) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("自定义", softWrap = false, maxLines = 1) }
                                    }
                                    if (!custom) Text("全局：提前 ${g.winBefore} 分钟 / 延后 ${g.winAfter} 分钟", fontSize = 12.sp, color = TextSub)
                                    if (custom) {
                                        MinStepper("提前可打卡", m.winBefore ?: g.winBefore) { m = m.copy(winBefore = it, winAfter = m.winAfter ?: g.winAfter) }
                                        MinStepper("延后可打卡", m.winAfter ?: g.winAfter) { m = m.copy(winAfter = it, winBefore = m.winBefore ?: g.winBefore) }
                                    }
                                    val w = Window(m.winBefore ?: g.winBefore, m.winAfter ?: g.winAfter)
                                    m.slots.sortedBy { it.minutes }.forEach { sl -> Text("${sl.label.ifBlank { sl.timeText }}：可打卡 ${windowText(sl.minutes, w)}", fontSize = 12.sp, color = TextSub) }
                                } }
                            } }
                            2 -> {
                                WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("疗程", fontWeight = FontWeight.SemiBold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("开始日期", Modifier.weight(1f), color = TextSub); TimeChip(m.startDate) { pickDate = "start" }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("结束", Modifier.weight(1f), color = TextSub)
                                        TimeChip("长期", if (m.endDate == null) Blue else Gray) { m = m.copy(endDate = null); courseDays = "" }
                                        Spacer(Modifier.width(6.dp))
                                        TimeChip(m.endDate ?: "选择结束日期", if (m.endDate != null) Blue else Gray) { pickDate = "end" }
                                    }
                                    OutlinedTextField(courseDays, { v -> courseDays = v.filter(Char::isDigit).take(4)
                                        courseDays.toIntOrNull()?.takeIf { it > 0 }?.let { n -> m = m.copy(endDate = LocalDate.parse(m.startDate).plusDays(n - 1L).toString()) } },
                                        label = { Text("或：持续 N 天") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                                } }
                                WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("频率", fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(Freq.DAILY to "每天", Freq.EVERY_N to "每隔 N 天", Freq.WEEKDAYS to "指定星期").forEach { (f, t) ->
                                            FilterChip(m.freq == f, { m = m.copy(freq = f) }, { Text(t, softWrap = false, maxLines = 1) })
                                        }
                                    }
                                    AnimatedContent(m.freq, label = "freq", transitionSpec = { fadeThrough(0, 0) }) { f ->
                                        when (f) {
                                            Freq.EVERY_N -> Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("每 ", color = TextSub)
                                                IconButton({ m = m.copy(everyN = (m.everyN - 1).coerceAtLeast(2)) }) { Text("－", fontSize = 20.sp) }
                                                AnimatedValue(m.everyN) { Text("$it", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                                                IconButton({ m = m.copy(everyN = (m.everyN + 1).coerceAtMost(30)) }) { Text("＋", fontSize = 20.sp) }
                                                Text(" 天一次（从开始日期起算）", color = TextSub)
                                            }
                                            Freq.WEEKDAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                (0..6).forEach { i ->
                                                    val on = m.weekdays and (1 shl i) != 0
                                                    FilterChip(on, { m = m.copy(weekdays = (m.weekdays xor (1 shl i)).let { if (it == 0) m.weekdays else it }) }, { Text("周" + "一二三四五六日"[i]) })
                                                }
                                            }
                                            else -> Text("每天按时服药", color = TextSub)
                                        }
                                    }
                                } }
                            }
                            else -> WhiteCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("库存管理", fontWeight = FontWeight.SemiBold); Text("打卡自动扣减，撤销自动恢复", fontSize = 12.sp, color = TextSub) }
                                    Switch(inv.enabled, { inv = inv.copy(enabled = it) })
                                }
                                AnimatedVisibility(inv.enabled, enter = fadeIn(tween(220, easing = Standard)) + expandVertically(spring(0.9f, Spring.StiffnessLow)),
                                    exit = fadeOut(tween(120)) + shrinkVertically(tween(200, easing = Standard))) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NumField("当前剩余", inv.remaining) { inv = inv.copy(remaining = it) }
                                        NumField("每次用量", inv.perDose) { inv = inv.copy(perDose = it) }
                                        OutlinedTextField(inv.unit, { inv = inv.copy(unit = it.take(4)) }, label = { Text("单位（片/粒/ml）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        NumField("低于多少时提醒", inv.lowThreshold) { inv = inv.copy(lowThreshold = it) }
                                    }
                                }
                            } }
                        }
                    }
                }
            }
        }
    }
    pickFor?.let { s ->
        var v by remember(s.id) { mutableIntStateOf(s.minutes) }
        AlertDialog(onDismissRequest = { pickFor = null }, title = { Text("选择时间（24小时制）") }, text = { HmPicker(v) { v = it } },
            confirmButton = { TextButton({ m = m.copy(slots = m.slots.map { if (it.id == s.id) it.withMinutes(v) else it }).sortedSlots(); pickFor = null }) { Text("确定") } },
            dismissButton = { TextButton({ pickFor = null }) { Text("取消") } })
    }
    pickDate?.let { which ->
        val cur = LocalDate.parse(if (which == "start") m.startDate else m.endDate ?: m.startDate)
        val st = rememberDatePickerState(initialSelectedDateMillis = cur.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = null },
            confirmButton = { TextButton({
                st.selectedDateMillis?.let { ms -> val dt = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    m = if (which == "start") m.copy(startDate = dt, endDate = m.endDate?.let { e -> if (e < dt) dt else e }) else m.copy(endDate = if (dt < m.startDate) m.startDate else dt)
                    courseDays = "" }
                pickDate = null }) { Text("确定") } },
            dismissButton = { TextButton({ pickDate = null }) { Text("取消") } }) { DatePicker(st) }
    }
}

@Composable
fun NumField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(fmtQty(value)) }
    OutlinedTextField(text, { v -> text = v.filter { it.isDigit() || it == '.' }.take(7); text.toDoubleOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
