package com.luge.medreminder

import java.time.LocalDate
import java.util.UUID

data class DoseSlot(val id: String = UUID.randomUUID().toString(), val label: String = "", val hour: Int = 8, val minute: Int = 0) {
    val minutes get() = hour * 60 + minute
    val timeText get() = "%02d:%02d".format(hour, minute)
}

enum class Freq { DAILY, EVERY_N, WEEKDAYS }

data class Medicine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val dosage: String = "",
    val slots: List<DoseSlot> = emptyList(),
    val startDate: String = LocalDate.now().toString(),
    val endDate: String? = null,
    val freq: Freq = Freq.DAILY,
    val everyN: Int = 2,
    /** bit (dayOfWeek-1): Monday=bit0 … Sunday=bit6 */
    val weekdays: Int = 0x7F,
    /** Per-medicine check-in window override (minutes before / after the scheduled time); null = use global. */
    val winBefore: Int? = null,
    val winAfter: Int? = null,
)

data class Inventory(
    val medId: String,
    val enabled: Boolean = false,
    val remaining: Double = 0.0,
    val perDose: Double = 1.0,
    val unit: String = "片",
    val lowThreshold: Double = 5.0,
)

enum class Status { TAKEN, SKIPPED }

data class Record(
    val medId: String, val slotId: String, val date: String,
    val scheduledMin: Int, val label: String,
    val status: Status, val takenAt: Long,
    val note: String = "",
    val id: String = "$medId|$slotId|$date",
)

/** One planned dose on a given day (snapshot-able). */
data class PlanItem(val medId: String, val slotId: String, val label: String, val minutes: Int)

data class AppData(
    val medicines: List<Medicine> = emptyList(),
    val records: List<Record> = emptyList(),
    val lateThresholdMin: Int = 30,
    /** date -> frozen plan for that day (all medicines). */
    val snapshots: Map<String, List<PlanItem>> = emptyMap(),
    val inventory: Map<String, Inventory> = emptyMap(),
    /** Global check-in window: allowed from (time - winBefore) to (time + winAfter), inclusive. */
    val winBefore: Int = 30,
    val winAfter: Int = 60,
)

/** Parses "H:mm"/"HH:mm" (24-hour) to minutes since midnight, or null. */
fun parseHm(s: String): Int? {
    val p = s.trim().split(":"); if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

fun DoseSlot.withMinutes(total: Int) = ((total % 1440) + 1440).let { copy(hour = it % 1440 / 60, minute = it % 60) }

/** Slots are always kept in time order (editor + storage). */
fun Medicine.sortedSlots() = copy(slots = slots.sortedBy { it.minutes })

fun minText(m: Int) = ((m % 1440) + 1440).let { "%02d:%02d".format(it % 1440 / 60, it % 60) }
