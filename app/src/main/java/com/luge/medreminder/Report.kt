package com.luge.medreminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Report = summary + month heatmap + record table; rendered to PDF (paged) or a long PNG. */
private sealed interface Block { val h: Float }
private data class TextB(val text: String, val size: Float, val bold: Boolean = false, val color: Int = 0xFF1F2329.toInt(), override val h: Float = size * 1.6f) : Block
private data class HeatB(val month: YearMonth, override val h: Float = 250f) : Block
private data class RowB(val cells: List<String>, val header: Boolean = false, val color: Int = 0xFF1F2329.toInt(), override val h: Float = 20f) : Block
private data class GapB(override val h: Float) : Block

private fun blocks(d: AppData, today: LocalDate): List<Block> {
    val l = mutableListOf<Block>()
    l += TextB("吃药打卡 · 服药报告", 22f, true, 0xFF1677FF.toInt())
    l += TextB("生成日期 $today ｜ 统计范围：开始服药至今", 10f, color = 0xFF8F959E.toInt())
    l += GapB(8f)
    d.medicines.forEach { m ->
        val s = computeStats(d, m, 30, today)
        l += TextB("${m.name}  ${m.dosage}", 14f, true)
        l += TextB("${m.freqText()} ｜ ${m.courseText()} ｜ 每天 ${m.slots.joinToString(" ") { it.timeText }}", 10f, color = 0xFF8F959E.toInt())
        l += TextB("记录 ${s.daysTracked} 天 · 已服 ${s.taken} · 漏服 ${s.missed} · 跳过 ${s.skipped} · 准时率 ${(s.onTimeRate * 100).toInt()}% · 平均延迟 ${s.avgDelayMin.toInt()} 分钟 · 连续全勤 ${s.streak} 天", 11f)
        if (s.slotForgetRate.isNotEmpty()) l += TextB("各时段漏服率：" + s.slotForgetRate.joinToString("  ") { "${it.first} ${(it.second * 100).toInt()}%" }, 11f)
        l += GapB(6f)
    }
    l += TextB("本月完成情况（${today.year}年${today.monthValue}月）", 14f, true)
    l += HeatB(YearMonth.from(today))
    l += TextB("近 30 天打卡明细", 14f, true)
    l += RowB(listOf("日期", "药品", "时段", "计划", "实际", "状态"), header = true)
    val names = d.medicines.associate { it.id to it.name }
    (0 until 30).map { today.minusDays(it.toLong()) }.forEach { day ->
        planFor(d, day, today).forEach { p ->
            val r = d.recordFor(p.medId, p.slotId, day)
            val (st, c) = when {
                r == null && (day.isBefore(today)) -> "漏服" to 0xFFFF3141.toInt()
                r == null -> "待服" to 0xFF8F959E.toInt()
                r.status == Status.SKIPPED -> "跳过" to 0xFF999999.toInt()
                r.onTime(d.lateThresholdMin) -> "准时" to 0xFF00B578.toInt()
                else -> "迟到${r.delayMin()}分" to 0xFFFF8F1F.toInt()
            }
            val actual = if (r?.status == Status.TAKEN) r.takenAt.toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) } else "—"
            l += RowB(listOf(day.toString(), names[p.medId] ?: "-", p.label, minText(p.minutes), actual, st), color = c)
        }
    }
    return l
}

private fun drawBlock(c: Canvas, b: Block, x: Float, y: Float, w: Float, d: AppData, today: LocalDate) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    when (b) {
        is TextB -> { p.textSize = b.size; p.isFakeBoldText = b.bold; p.color = b.color; c.drawText(b.text, x, y + b.size * 1.15f, p) }
        is GapB -> {}
        is RowB -> {
            val cols = floatArrayOf(0f, .2f, .42f, .58f, .72f, .86f)
            if (b.header) { p.color = 0xFFF0F5FF.toInt(); c.drawRect(x, y, x + w, y + b.h, p) }
            p.textSize = 10f; p.isFakeBoldText = b.header
            b.cells.forEachIndexed { i, t -> p.color = if (i == 5 && !b.header) b.color else 0xFF1F2329.toInt(); c.drawText(t.take(12), x + 4 + cols[i] * w, y + 14f, p) }
            p.color = 0xFFEBEDF0.toInt(); c.drawLine(x, y + b.h, x + w, y + b.h, p)
        }
        is HeatB -> {
            val cell = minOf(w / 7f, 32f); val gap = 4f
            p.textSize = 9f; p.color = 0xFF8F959E.toInt()
            "一二三四五六日".forEachIndexed { i, ch -> c.drawText("$ch", x + i * cell + cell / 2 - 4, y + 10, p) }
            val first = b.month.atDay(1); val offset = first.dayOfWeek.value - 1
            for (day in 1..b.month.lengthOfMonth()) {
                val dt = b.month.atDay(day); val idx = offset + day - 1
                val cx = x + (idx % 7) * cell; val cy = y + 16 + (idx / 7) * cell
                val v = if (dt.isAfter(today)) null else dayCompletion(d, dt, null, today)
                p.color = when { v == null -> 0xFFF0F1F3.toInt(); v >= 1f -> 0xFF1677FF.toInt(); v > 0f -> 0xFF91CAFF.toInt(); else -> 0xFFFFCCC7.toInt() }
                c.drawRoundRect(RectF(cx + gap / 2, cy + gap / 2, cx + cell - gap / 2, cy + cell - gap / 2), 6f, 6f, p)
                p.color = if (v != null && v >= 1f) 0xFFFFFFFF.toInt() else 0xFF1F2329.toInt()
                c.drawText("$day", cx + cell / 2 - (if (day > 9) 6 else 3), cy + cell / 2 + 4, p)
            }
            p.textSize = 9f; val ly = y + 16 + 6 * cell + 14
            listOf(0xFF1677FF.toInt() to "全部完成", 0xFF91CAFF.toInt() to "部分完成", 0xFFFFCCC7.toInt() to "未完成", 0xFFF0F1F3.toInt() to "无计划").forEachIndexed { i, (col, t) ->
                p.color = col; c.drawRoundRect(RectF(x + i * 70f, ly - 9, x + i * 70f + 10, ly + 1), 2f, 2f, p)
                p.color = 0xFF8F959E.toInt(); c.drawText(t, x + i * 70f + 14, ly, p)
            }
        }
    }
}

fun exportReportPdf(ctx: Context) = runCatching {
    val d = Repo.data.value; val today = LocalDate.now()
    val doc = PdfDocument(); val W = 595; val H = 842; val margin = 36f
    var pageNo = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); var y = margin
    blocks(d, today).forEach { b ->
        if (y + b.h > H - margin) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); y = margin }
        drawBlock(page.canvas, b, margin, y, W - margin * 2, d, today); y += b.h
    }
    doc.finishPage(page)
    val f = File(ctx.cacheDir, "服药报告-$today.pdf"); f.outputStream().use { doc.writeTo(it) }; doc.close()
    shareFile(ctx, f, "application/pdf", "分享服药报告")
}.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }

fun exportReportImage(ctx: Context) = runCatching {
    val d = Repo.data.value; val today = LocalDate.now()
    val bl = blocks(d, today); val scale = 2f; val W = 420f; val margin = 20f
    val H = bl.sumOf { it.h.toDouble() }.toFloat() + margin * 2
    val bmp = Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt().coerceAtMost(30000), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); c.drawColor(0xFFFFFFFF.toInt()); c.scale(scale, scale)
    var y = margin
    bl.forEach { b -> drawBlock(c, b, margin, y, W - margin * 2, d, today); y += b.h }
    val f = File(ctx.cacheDir, "服药报告-$today.png"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
    shareFile(ctx, f, "image/png", "分享报告长图")
}.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
