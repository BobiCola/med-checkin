package com.luge.medreminder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.format.DateTimeFormatter

fun shareFile(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, title))
}

fun exportCsv(ctx: Context) {
    val d = Repo.data.value
    val names = d.medicines.associate { it.id to it.name }
    val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("\uFEFF日期,药名,时段,计划时间,实际时间,状态,延迟(分钟),备注\n")
    d.records.sortedWith(compareBy({ it.date }, { it.scheduledMin })).forEach { r ->
        val actual = java.time.Instant.ofEpochMilli(r.takenAt).atZone(java.time.ZoneId.systemDefault()).format(f)
        val st = if (r.status == Status.SKIPPED) "跳过" else if (r.onTime(d.lateThresholdMin)) "准时" else "迟到"
        sb.append(listOf(r.date, q(names[r.medId] ?: "(已删除)"), q(r.label), minText(r.scheduledMin),
            if (r.status == Status.TAKEN) actual else "", st, if (r.status == Status.TAKEN) r.delayMin().toString() else "", q(r.note)).joinToString(",")).append('\n')
    }
    shareFile(ctx, File(ctx.cacheDir, "吃药记录.csv").apply { writeText(sb.toString()) }, "text/csv", "导出打卡记录")
}

fun Long.toLocalTime(): java.time.LocalTime = java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
fun Long.toLocalDate(): java.time.LocalDate = java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
