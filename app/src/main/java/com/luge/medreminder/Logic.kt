package com.luge.medreminder

import java.time.*
import java.time.temporal.ChronoUnit

/** Pure, Android-free business logic (unit tested). */

fun Medicine.isActiveOn(date: LocalDate): Boolean {
    val start = LocalDate.parse(startDate)
    if (date.isBefore(start)) return false
    endDate?.let { if (date.isAfter(LocalDate.parse(it))) return false }
    return when (freq) {
        Freq.DAILY -> true
        Freq.EVERY_N -> ChronoUnit.DAYS.between(start, date) % everyN.coerceAtLeast(1) == 0L
        Freq.WEEKDAYS -> weekdays and (1 shl (date.dayOfWeek.value - 1)) != 0
    }
}

fun Medicine.isEnded(today: LocalDate) = endDate?.let { today.isAfter(LocalDate.parse(it)) } ?: false

fun currentPlan(meds: List<Medicine>, date: LocalDate): List<PlanItem> =
    meds.filter { it.isActiveOn(date) }.flatMap { m -> m.slots.map { PlanItem(m.id, it.id, it.label, it.minutes) } }

/** Past days use the frozen snapshot (if any); today/future use the current schedule. */
fun planFor(d: AppData, date: LocalDate, today: LocalDate = LocalDate.now()): List<PlanItem> {
    val snap = d.snapshots[date.toString()]
    val items = if (date.isBefore(today) && snap != null) snap else currentPlan(d.medicines, date)
    return items.sortedBy { it.minutes }
}

/**
 * Lazily create snapshots for every day since the last snapshot up to today (inclusive),
 * using the current schedule (which is what was in effect since the app was last opened).
 * Today's snapshot is always refreshed; past snapshots are never modified.
 */
fun ensureSnapshots(d: AppData, today: LocalDate = LocalDate.now()): AppData {
    val last = d.snapshots.keys.maxOrNull()?.let { LocalDate.parse(it) }
    val from = when {
        last == null -> today
        last.isBefore(today.minusDays(400)) -> today.minusDays(400)
        else -> last.plusDays(1).let { if (it.isAfter(today)) today else it }
    }
    val m = d.snapshots.toMutableMap()
    var day = from
    while (!day.isAfter(today)) { m[day.toString()] = currentPlan(d.medicines, day); day = day.plusDays(1) }
    return d.copy(snapshots = m)
}

fun Record.delayMin(zone: ZoneId = ZoneId.systemDefault()): Long {
    val sched = LocalDate.parse(date).atTime(scheduledMin / 60, scheduledMin % 60)
    val actual = Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDateTime()
    return Duration.between(sched, actual).toMinutes()
}
fun Record.onTime(threshold: Int, zone: ZoneId = ZoneId.systemDefault()) = status == Status.TAKEN && delayMin(zone) <= threshold

fun AppData.recordFor(medId: String, slotId: String, date: LocalDate) =
    records.find { it.medId == medId && it.slotId == slotId && it.date == date.toString() }

/** Adds/replaces a record and keeps inventory consistent (decrement once on TAKEN). */
fun AppData.withRecord(r: Record): AppData {
    val prev = recordFor(r.medId, r.slotId, LocalDate.parse(r.date))
    var inv = inventory
    val i = inv[r.medId]
    if (i != null && i.enabled) {
        val wasTaken = prev?.status == Status.TAKEN; val isTaken = r.status == Status.TAKEN
        val delta = when { isTaken && !wasTaken -> -i.perDose; !isTaken && wasTaken -> i.perDose; else -> 0.0 }
        if (delta != 0.0) inv = inv + (r.medId to i.copy(remaining = (i.remaining + delta).coerceAtLeast(0.0)))
    }
    return copy(records = records.filterNot { it.medId == r.medId && it.slotId == r.slotId && it.date == r.date } + r, inventory = inv)
}

fun AppData.withoutRecord(medId: String, slotId: String, date: LocalDate): AppData {
    val prev = recordFor(medId, slotId, date) ?: return this
    var inv = inventory
    val i = inv[medId]
    if (i != null && i.enabled && prev.status == Status.TAKEN) inv = inv + (medId to i.copy(remaining = i.remaining + i.perDose))
    return copy(records = records - prev, inventory = inv)
}

fun Inventory.isLow() = enabled && remaining <= lowThreshold
fun Inventory.daysLeft(dosesPerDay: Int) = if (!enabled || dosesPerDay == 0 || perDose <= 0) null else (remaining / (perDose * dosesPerDay)).toInt()

data class MedStats(
    val daysTracked: Int, val taken: Int, val skipped: Int, val missed: Int, val onTimeRate: Float, val streak: Int,
    val daily: List<Pair<LocalDate, Float>>, val avgDelayMin: Float, val slotForgetRate: List<Pair<String, Float>>,
)

/** Completion (0..1) for one day; null if nothing planned. medId=null = all medicines. */
fun dayCompletion(d: AppData, date: LocalDate, medId: String? = null, today: LocalDate = LocalDate.now()): Float? {
    val plan = planFor(d, date, today).filter { medId == null || it.medId == medId }
    if (plan.isEmpty()) return null
    return plan.count { p -> d.recordFor(p.medId, p.slotId, date)?.status == Status.TAKEN }.toFloat() / plan.size
}

fun computeStats(d: AppData, m: Medicine, chartDays: Int, today: LocalDate = LocalDate.now(), nowMin: Int = LocalTime.now().let { it.hour * 60 + it.minute }): MedStats {
    val recs = d.records.filter { it.medId == m.id }
    val start = minOf(LocalDate.parse(m.startDate), recs.minOfOrNull { LocalDate.parse(it.date) } ?: today)
    val end = m.endDate?.let { minOf(LocalDate.parse(it), today) } ?: today
    var missed = 0
    val slotTotal = mutableMapOf<String, Int>(); val slotMissed = mutableMapOf<String, Int>()
    var day = start
    while (!day.isAfter(end)) {
        planFor(d, day, today).filter { it.medId == m.id }.forEach { p ->
            val due = day.isBefore(today) || p.minutes + d.windowFor(p.medId).after < nowMin
            if (!due) return@forEach
            val key = p.label.ifBlank { minText(p.minutes) }
            slotTotal[key] = (slotTotal[key] ?: 0) + 1
            if (d.recordFor(m.id, p.slotId, day) == null) { missed++; slotMissed[key] = (slotMissed[key] ?: 0) + 1 }
        }
        day = day.plusDays(1)
    }
    val taken = recs.filter { it.status == Status.TAKEN }
    fun full(dt: LocalDate) = (dayCompletion(d, dt, m.id, today) ?: -1f) >= 1f
    fun planned(dt: LocalDate) = dayCompletion(d, dt, m.id, today) != null
    var streak = 0
    var c = if (full(today) || !planned(today)) today else today.minusDays(1)
    var guard = 0
    while (!c.isBefore(start) && guard++ < 3660) {
        if (planned(c)) { if (full(c)) streak++ else break }
        c = c.minusDays(1)
    }
    return MedStats(
        daysTracked = (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(0),
        taken = taken.size, skipped = recs.count { it.status == Status.SKIPPED }, missed = missed,
        onTimeRate = if (taken.isEmpty()) 0f else taken.count { it.onTime(d.lateThresholdMin) }.toFloat() / taken.size,
        streak = streak,
        daily = (chartDays - 1 downTo 0).map { today.minusDays(it.toLong()).let { dt -> dt to (dayCompletion(d, dt, m.id, today) ?: 0f) } },
        avgDelayMin = if (taken.isEmpty()) 0f else taken.map { it.delayMin().coerceAtLeast(0) }.average().toFloat(),
        slotForgetRate = slotTotal.keys.sorted().map { it to (slotMissed[it] ?: 0).toFloat() / slotTotal[it]!! },
    )
}

/** Next fire time for a slot honoring course/frequency, or null if the course ended. */
fun nextFireTime(m: Medicine, s: DoseSlot, now: LocalDateTime): LocalDateTime? {
    var day = now.toLocalDate()
    repeat(400) {
        val t = day.atTime(s.hour, s.minute)
        if (t.isAfter(now) && m.isActiveOn(day)) return t
        if (m.endDate != null && day.isAfter(LocalDate.parse(m.endDate))) return null
        day = day.plusDays(1)
    }
    return null
}

fun weekdaysText(mask: Int) = if (mask == 0x7F) "每天" else (0..6).filter { mask and (1 shl it) != 0 }.joinToString("、") { "周" + "一二三四五六日"[it] }

fun Medicine.freqText() = when (freq) {
    Freq.DAILY -> "每天"
    Freq.EVERY_N -> "每 $everyN 天"
    Freq.WEEKDAYS -> weekdaysText(weekdays)
}
fun Medicine.courseText() = if (endDate == null) "长期" else "$startDate 至 $endDate"

// ---------------- check-in window ----------------
const val WIN_MAX = 240

data class Window(val before: Int, val after: Int)

fun AppData.windowFor(medId: String): Window {
    val m = medicines.find { it.id == medId }
    return Window(m?.winBefore ?: winBefore, m?.winAfter ?: winAfter)
}

/** Inclusive on both edges: [slot - before, slot + after]. */
fun inWindow(slotMin: Int, atMin: Int, w: Window) = atMin >= slotMin - w.before && atMin <= slotMin + w.after

fun windowText(slotMin: Int, w: Window) = "${minText(((slotMin - w.before) % 1440 + 1440) % 1440)}–${minText((slotMin + w.after) % 1440)}"

/** Late threshold never exceeds the window's "after" (anything inside the window but past it = 迟到). */
fun AppData.lateFor(medId: String) = minOf(lateThresholdMin, windowFor(medId).after)

enum class DoseState { DONE, BEFORE, OPEN, MISSED }

fun AppData.doseState(medId: String, slotId: String, slotMin: Int, date: LocalDate, today: LocalDate, nowMin: Int): DoseState {
    if (recordFor(medId, slotId, date) != null) return DoseState.DONE
    val w = windowFor(medId)
    // express "now" relative to the dose's day so windows crossing midnight work
    val rel = nowMin + (java.time.temporal.ChronoUnit.DAYS.between(date, today) * 1440).toInt()
    return when {
        rel < slotMin - w.before -> DoseState.BEFORE
        rel > slotMin + w.after -> DoseState.MISSED
        else -> DoseState.OPEN
    }
}

/** Minutes of [at] measured from midnight of [date] (may be negative or ≥1440 across midnight). */
fun minutesFrom(date: LocalDate, at: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
    ((at - date.atStartOfDay(zone).toInstant().toEpochMilli()) / 60000).toInt()

/** Whether a TAKEN record at [takenAt] for this dose is allowed. */
fun AppData.canCheckIn(medId: String, slotMin: Int, date: LocalDate, takenAt: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
    inWindow(slotMin, minutesFrom(date, takenAt, zone), windowFor(medId))

/**
 * Generic one-tap target: earliest pending item whose window contains now. Returns null if none.
 */
fun <T> List<T>.pickTarget(nowMin: Int, minutes: (T) -> Int, pending: (T) -> Boolean, window: (T) -> Window): T? =
    filter { pending(it) && inWindow(minutes(it), nowMin, window(it)) }.minByOrNull(minutes)

/** The single source of truth for the home button / widget: plan item that one-tap 打卡 records, or null. */
fun checkInTarget(d: AppData, date: LocalDate, nowMin: Int, today: LocalDate = date): PlanItem? =
    planFor(d, date, today).filter { p -> d.medicines.any { it.id == p.medId } }
        .pickTarget(nowMin, { it.minutes }, { d.recordFor(it.medId, it.slotId, date) == null }, { d.windowFor(it.medId) })

/** Earliest pending dose whose window has not opened yet: (item, windowStartMin). */
fun nextWindowOpen(d: AppData, date: LocalDate, nowMin: Int): Pair<PlanItem, Int>? =
    planFor(d, date, date).filter { p -> d.medicines.any { it.id == p.medId } && d.recordFor(p.medId, p.slotId, date) == null }
        .map { it to it.minutes - d.windowFor(it.medId).before }.filter { it.second > nowMin }.minByOrNull { it.second }
