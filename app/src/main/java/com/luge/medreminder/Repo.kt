package com.luge.medreminder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/** Settings kept in SharedPreferences. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun late(ctx: Context) = p(ctx).getInt("late", 30)
    fun repeatMin(ctx: Context) = p(ctx).getInt("repeatMin", 15)      // 0 = off
    fun repeatCount(ctx: Context) = p(ctx).getInt("repeatCount", 2)
    fun sound(ctx: Context) = p(ctx).getString("sound", "sound_vibrate")!! // sound_vibrate / sound / vibrate / silent
    fun set(ctx: Context, k: String, v: Any) = p(ctx).edit().apply {
        when (v) { is Int -> putInt(k, v); is String -> putString(k, v); is Boolean -> putBoolean(k, v) }
    }.apply()
    fun all(ctx: Context): Map<String, Any> = mapOf("repeatMin" to repeatMin(ctx), "repeatCount" to repeatCount(ctx), "sound" to sound(ctx))
}

object Repo {
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    private var db: AppDb? = null
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastDay: LocalDate? = null

    @Synchronized
    fun init(ctx: Context) {
        if (db == null) {
            val app = ctx.applicationContext
            val database = AppDb.build(app); db = database
            var d = database.dao().loadAll(Prefs.late(app))
            // One-time migration from v1 JSON storage.
            val legacy = File(app.filesDir, "data.json")
            if (legacy.exists()) {
                runCatching {
                    val old = Codec.parse(JSONObject(legacy.readText()))
                    if (d.medicines.isEmpty() && d.records.isEmpty()) d = old
                    Prefs.set(app, "late", old.lateThresholdMin)
                    legacy.renameTo(File(app.filesDir, "data.json.migrated"))
                }
            }
            _data.value = d
            persist(app, d)
        }
        rollDay(ctx)
    }

    /** Lazily freeze plan snapshots for days since last open. */
    fun rollDay(ctx: Context) {
        val today = LocalDate.now()
        if (lastDay == today) return
        lastDay = today
        update(ctx) { ensureSnapshots(it, today) }
    }

    @Synchronized
    fun update(ctx: Context, fn: (AppData) -> AppData) {
        if (db == null) init(ctx)
        val n = fn(_data.value)
        _data.value = n
        persist(ctx.applicationContext, n)
    }

    /** Edit medicines: freeze history first, then refresh today's snapshot with the new plan. */
    fun updateMedicines(ctx: Context, fn: (AppData) -> AppData) = update(ctx) { d0 ->
        val d1 = fn(ensureSnapshots(d0)); ensureSnapshots(d1.copy(medicines = d1.medicines.map { it.sortedSlots() }))
    }

    private fun persist(app: Context, d: AppData) {
        io.execute {
            runCatching { db!!.runInTransaction { db!!.dao().replaceAll(d) } }
            Widgets.refresh(app)
        }
    }

    /** Records a dose. TAKEN is only accepted when [takenAt] falls inside the dose's check-in window; returns false otherwise. */
    fun record(ctx: Context, med: Medicine, slot: DoseSlot, date: LocalDate, status: Status, takenAt: Long = System.currentTimeMillis(), note: String = ""): Boolean {
        if (db == null) init(ctx)
        if (status == Status.TAKEN && !_data.value.canCheckIn(med.id, slot.minutes, date, takenAt)) return false
        update(ctx) { it.withRecord(Record(med.id, slot.id, date.toString(), slot.minutes, slot.label, status, takenAt, note)) }
        if (status == Status.TAKEN) Alarms.checkLowStock(ctx, med.id)
        return true
    }

    fun setWindow(ctx: Context, before: Int, after: Int) = update(ctx) { it.copy(winBefore = before, winAfter = after) }

    fun undo(ctx: Context, medId: String, slotId: String, date: LocalDate) = update(ctx) { it.withoutRecord(medId, slotId, date) }

    fun backupJson(ctx: Context) = Codec.toJson(_data.value, Prefs.all(ctx)).toString(2)

    fun restoreJson(ctx: Context, text: String) {
        val o = JSONObject(text)
        val d = Codec.parse(o)
        o.optJSONObject("prefs")?.let { p -> p.keys().forEach { k -> Prefs.set(ctx, k, p.get(k)) } }
        Prefs.set(ctx, "late", d.lateThresholdMin)
        update(ctx) { ensureSnapshots(d) }
        Alarms.rescheduleAll(ctx)
    }

    fun setLate(ctx: Context, v: Int) { Prefs.set(ctx, "late", v); update(ctx) { it.copy(lateThresholdMin = v) } }
}
