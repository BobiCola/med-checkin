package com.luge.medreminder

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LogicTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 10)
    private val morning = DoseSlot("s1", "早", 8, 0)
    private val evening = DoseSlot("s2", "晚", 20, 0)
    private val med = Medicine("m1", "维生素", "1片", listOf(morning, evening), startDate = "2026-11-01")
    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun taken(date: LocalDate, s: DoseSlot, h: Int, m: Int) = Record("m1", s.id, date.toString(), s.minutes, s.label, Status.TAKEN, at(date, h, m))

    @Test fun frequencyDaily() {
        assertFalse(med.isActiveOn(LocalDate.of(2026, 10, 31)))
        assertTrue(med.isActiveOn(LocalDate.of(2026, 11, 1)))
    }

    @Test fun frequencyEveryN() {
        val m = med.copy(freq = Freq.EVERY_N, everyN = 3)
        assertTrue(m.isActiveOn(LocalDate.of(2026, 11, 1)))
        assertFalse(m.isActiveOn(LocalDate.of(2026, 11, 2)))
        assertTrue(m.isActiveOn(LocalDate.of(2026, 11, 4)))
    }

    @Test fun frequencyWeekdaysAndEnd() {
        val monWed = (1 shl 0) or (1 shl 2)
        val m = med.copy(freq = Freq.WEEKDAYS, weekdays = monWed, endDate = "2026-11-11")
        assertTrue(m.isActiveOn(LocalDate.of(2026, 11, 2)))  // Monday
        assertFalse(m.isActiveOn(LocalDate.of(2026, 11, 3))) // Tuesday
        assertTrue(m.isActiveOn(LocalDate.of(2026, 11, 4)))  // Wednesday
        assertTrue(m.isActiveOn(LocalDate.of(2026, 11, 9)))  // Monday
        assertFalse(m.isActiveOn(LocalDate.of(2026, 11, 16))) // after end
        assertTrue(m.isEnded(LocalDate.of(2026, 11, 12)))
    }

    @Test fun nextFireRespectsCourse() {
        val m = med.copy(freq = Freq.WEEKDAYS, weekdays = 1 shl 2, endDate = "2026-11-11") // Wednesdays
        val n = nextFireTime(m, morning, LocalDateTime.of(2026, 11, 2, 9, 0))
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), n)
        assertNull(nextFireTime(m, morning, LocalDateTime.of(2026, 11, 11, 9, 0)))
    }

    @Test fun snapshotsFreezeHistory() {
        var d = AppData(medicines = listOf(med))
        d = ensureSnapshots(d, LocalDate.of(2026, 11, 8))
        d = ensureSnapshots(d, today) // lazily fills 9th and 10th
        assertEquals(setOf("2026-11-08", "2026-11-09", "2026-11-10"), d.snapshots.keys)
        // schedule changes today: only morning dose remains
        d = ensureSnapshots(d.copy(medicines = listOf(med.copy(slots = listOf(morning)))), today)
        assertEquals(2, planFor(d, LocalDate.of(2026, 11, 9), today).size) // past keeps 2 doses
        assertEquals(1, planFor(d, today, today).size)                     // today uses new plan
    }

    @Test fun inventoryDecrementAndRestore() {
        var d = AppData(medicines = listOf(med), inventory = mapOf("m1" to Inventory("m1", true, 10.0, 1.5)))
        d = d.withRecord(taken(today, morning, 8, 5))
        assertEquals(8.5, d.inventory["m1"]!!.remaining, 1e-9)
        d = d.withRecord(taken(today, morning, 8, 20)) // re-record same slot: no double decrement
        assertEquals(8.5, d.inventory["m1"]!!.remaining, 1e-9)
        d = d.withRecord(Record("m1", "s1", today.toString(), 480, "早", Status.SKIPPED, at(today, 8, 30)))
        assertEquals(10.0, d.inventory["m1"]!!.remaining, 1e-9) // taken → skipped restores
        d = d.withRecord(taken(today, evening, 20, 0)).withoutRecord("m1", "s2", today)
        assertEquals(10.0, d.inventory["m1"]!!.remaining, 1e-9)
        assertFalse(d.inventory["m1"]!!.isLow())
        assertTrue(Inventory("m1", true, 3.0, 1.0, lowThreshold = 5.0).isLow())
        assertEquals(5, Inventory("m1", true, 10.0, 1.0).daysLeft(2))
    }

    @Test fun statsCounts() {
        val start = LocalDate.of(2026, 11, 8)
        var d = AppData(medicines = listOf(med.copy(startDate = start.toString())), lateThresholdMin = 30)
        d = ensureSnapshots(d, start)
        listOf(start, start.plusDays(1)).forEach { day ->
            d = d.withRecord(taken(day, morning, 8, 10)).withRecord(taken(day, evening, 21, 0)) // evening late by 60
        }
        // today (10th): morning taken on time, evening not yet due at 12:00
        d = d.withRecord(taken(today, morning, 8, 0))
        val s = computeStats(d, d.medicines[0], 7, today, nowMin = 12 * 60)
        assertEquals(3, s.daysTracked)
        assertEquals(5, s.taken)
        assertEquals(0, s.missed)
        assertEquals(3f / 5f, s.onTimeRate, 1e-4f)
        assertEquals(2, s.streak) // 8th & 9th fully done, today partial
        assertEquals(1f, s.daily.last { it.first == start }.second, 1e-4f)
        assertEquals(28f, s.avgDelayMin, 1e-3f) // (10+60+10+60+0)/5
        // evening dose counts as missed only once its window (20:00 + 60) has closed
        assertEquals(0, computeStats(d, d.medicines[0], 7, today, nowMin = 21 * 60).missed)
        val s2 = computeStats(d, d.medicines[0], 7, today, nowMin = 21 * 60 + 1)
        assertEquals(1, s2.missed)
        assertEquals(1f / 3f, s2.slotForgetRate.toMap()["晚"]!!, 1e-4f) // 1 of 3
    }

    @Test fun delayAndOnTime() {
        val r = taken(today, morning, 8, 45)
        assertEquals(45, r.delayMin())
        assertFalse(r.onTime(30)); assertTrue(r.onTime(60))
    }

    @Test fun codecRoundTrip() {
        val d = ensureSnapshots(AppData(medicines = listOf(med.copy(freq = Freq.EVERY_N, everyN = 2, endDate = "2026-12-01")),
            records = listOf(taken(today, morning, 8, 0).copy(note = "饭后")),
            inventory = mapOf("m1" to Inventory("m1", true, 20.0, 1.0, "粒", 4.0))), today)
        val back = Codec.parse(Codec.toJson(d))
        assertEquals(d.medicines, back.medicines)
        assertEquals(d.records, back.records)
        assertEquals(d.inventory, back.inventory)
        assertEquals(d.snapshots, back.snapshots)
    }

    @Test fun legacyV1JsonParses() {
        val v1 = org.json.JSONObject("""{"late":20,"medicines":[{"id":"a","name":"药","dosage":"","start":"2026-11-01","slots":[{"id":"x","label":"早","h":8,"m":0}]}],
            "records":[{"med":"a","slot":"x","date":"2026-11-02","sch":480,"label":"早","st":"TAKEN","at":1}]}""")
        val d = Codec.parse(v1)
        assertEquals(20, d.lateThresholdMin)
        assertEquals(Freq.DAILY, d.medicines[0].freq)
        assertEquals("a|x|2026-11-02", d.records[0].id)
    }

    private val tri = listOf(DoseSlot("z", "早", 9, 0), DoseSlot("w", "中", 12, 0), DoseSlot("x", "晚", 20, 0))
    private fun pick(now: Int, taken: Set<String> = emptySet()) = tri.pickTarget(now, { it.minutes }, { it.id !in taken }, { Window(60, 60) })?.id

    @Test fun targetAt1000IsMorning() = assertEquals("z", pick(10 * 60))
    @Test fun targetAt0830IsUpcomingMorning() = assertEquals("z", pick(8 * 60 + 30))
    @Test fun targetAt1300AfterMorningIsNoon() = assertEquals("w", pick(13 * 60, setOf("z")))
    @Test fun targetAt2100OnlyEveningPending() = assertEquals("x", pick(21 * 60, setOf("z", "w")))
    @Test fun targetNoneWhenAllDone() = assertNull(pick(21 * 60, setOf("z", "w", "x")))
    @Test fun targetIgnoresInputOrder() = assertEquals("z", tri.reversed().pickTarget(10 * 60, { it.minutes }, { true }, { Window(60, 60) })?.id)

    @Test fun latenessAgainstChosenSlot() {
        val s = tri.pickTarget(10 * 60, { it.minutes }, { true }, { Window(60, 60) })!!
        val r = Record("m1", s.id, today.toString(), s.minutes, s.label, Status.TAKEN, at(today, 10, 0))
        assertEquals(60, r.delayMin())
    }

    // ---- home-button path end to end: state -> displayed target -> record -> stored slot ----
    private val triMed = Medicine("t", "降压药", "1片", tri, startDate = "2026-11-01")
    private fun tap(d: AppData, h: Int, m: Int): AppData {
        val t = checkInTarget(d, today, h * 60 + m) ?: return d
        val slot = triMed.slots.first { it.id == t.slotId }
        return d.withRecord(Record(t.medId, t.slotId, today.toString(), slot.minutes, slot.label, Status.TAKEN, at(today, h, m)))
    }

    @Test fun homeTapAt0910RecordsMorning() {
        val d0 = ensureSnapshots(AppData(medicines = listOf(triMed)), today)
        assertEquals("z", checkInTarget(d0, today, 9 * 60 + 10)!!.slotId)        // displayed
        val d1 = tap(d0, 9, 10)
        assertEquals(listOf("z"), d1.records.map { it.slotId })                     // stored
        assertEquals(10, d1.records[0].delayMin())
    }

    @Test fun homeDoubleTapAt0910DoesNotRecordNoon() {
        val d = tap(tap(ensureSnapshots(AppData(medicines = listOf(triMed)), today), 9, 10), 9, 10)
        assertEquals(listOf("z"), d.records.map { it.slotId })
        assertNull(checkInTarget(d, today, 9 * 60 + 10)) // 12:00 is 170 min away: not tappable
        assertEquals("w", checkInTarget(d, today, 11 * 60 + 30)!!.slotId)
    }

    @Test fun homeTargetAcrossMedicinesAndUnsortedSlots() {
        val other = Medicine("o", "钙片", "", listOf(DoseSlot("o1", "晚", 20, 0), DoseSlot("o0", "早", 8, 30)), startDate = "2026-11-01")
        val d = AppData(medicines = listOf(triMed.copy(slots = tri.reversed()), other))
        assertEquals("o0", checkInTarget(d, today, 9 * 60 + 10)!!.slotId)          // 08:30 overdue (still in window) first
        val d2 = d.withRecord(Record("o", "o0", today.toString(), 510, "早", Status.TAKEN, at(today, 9, 10)))
        assertEquals("z", checkInTarget(d2, today, 9 * 60 + 11)!!.slotId)
    }

    @Test fun earlyWindow() {
        assertEquals("z", pick(8 * 60))          // 60 min early ok
        assertNull(pick(7 * 60 + 59))            // 61 min early: no
    }

    // ---------------- check-in window ----------------
    private val w9 = Medicine("w", "药", "", listOf(DoseSlot("a", "早", 9, 0), DoseSlot("b", "中", 12, 0), DoseSlot("c", "晚", 20, 0)), startDate = "2026-11-01")
    private val base = AppData(medicines = listOf(w9), winBefore = 30, winAfter = 60)

    @Test fun windowEdgesInclusive() {
        val w = Window(30, 60)
        assertFalse(inWindow(540, 509, w)); assertTrue(inWindow(540, 510, w))
        assertTrue(inWindow(540, 600, w)); assertFalse(inWindow(540, 601, w))
        assertEquals("08:30–10:00", windowText(540, w))
    }

    @Test fun canCheckInByTimestamp() {
        assertFalse(base.canCheckIn("w", 540, today, at(today, 8, 29)))
        assertTrue(base.canCheckIn("w", 540, today, at(today, 8, 30)))
        assertTrue(base.canCheckIn("w", 540, today, at(today, 10, 0)))
        assertFalse(base.canCheckIn("w", 540, today, at(today, 10, 1)))
        assertFalse(base.canCheckIn("w", 540, today, at(today.plusDays(1), 9, 0))) // other day
    }

    @Test fun target0910Is0900() = assertEquals("a", checkInTarget(base, today, 9 * 60 + 10)!!.slotId)
    @Test fun target1120NoneWithBefore30() = assertNull(checkInTarget(base, today, 11 * 60 + 20))
    @Test fun target1130Is1200() = assertEquals("b", checkInTarget(base, today, 11 * 60 + 30)!!.slotId)

    @Test fun nextWindowCountdown() {
        val (p, start) = nextWindowOpen(base, today, 11 * 60 + 20)!!
        assertEquals("b", p.slotId); assertEquals(11 * 60 + 30, start)
    }

    @Test fun missedAfterWindow() {
        assertEquals(DoseState.OPEN, base.doseState("w", "a", 540, today, today, 600))
        assertEquals(DoseState.MISSED, base.doseState("w", "a", 540, today, today, 601))
        assertEquals(DoseState.BEFORE, base.doseState("w", "a", 540, today, today, 509))
        assertEquals(DoseState.MISSED, base.doseState("w", "a", 540, today.minusDays(1), today, 0))
        assertNull(checkInTarget(base, today, 10 * 60 + 1)) // 09:00 closed, 12:00 not open
        val done = base.withRecord(Record("w", "a", today.toString(), 540, "早", Status.TAKEN, at(today, 9, 5)))
        assertEquals(DoseState.DONE, done.doseState("w", "a", 540, today, today, 601))
    }

    @Test fun perMedicineOverride() {
        val d = base.copy(medicines = listOf(w9.copy(winBefore = 0, winAfter = 120)))
        assertEquals(Window(0, 120), d.windowFor("w"))
        assertNull(checkInTarget(d, today, 8 * 60 + 59))
        assertEquals("a", checkInTarget(d, today, 11 * 60)!!.slotId)
        assertEquals(Window(30, 60), base.windowFor("w"))
        assertEquals(30, d.copy(lateThresholdMin = 30).lateFor("w"))
        assertEquals(60, base.copy(lateThresholdMin = 90).lateFor("w")) // late clamped to after
    }

    @Test fun windowCodecRoundTrip() {
        val d = base.copy(winBefore = 15, winAfter = 45, medicines = listOf(w9.copy(winBefore = 10, winAfter = null)))
        val b = Codec.parse(Codec.toJson(d))
        assertEquals(15, b.winBefore); assertEquals(45, b.winAfter); assertEquals(d.medicines, b.medicines)
    }

    // ---------------- 24h time round-trip / slot ordering ----------------
    @Test fun hmRoundTrip() {
        listOf("00:00" to 0, "12:00" to 720, "23:59" to 1439, "02:00" to 120).forEach { (t, v) ->
            assertEquals(v, parseHm(t)); assertEquals(t, minText(v))
            val s = DoseSlot("x", "", 8, 0).withMinutes(v)
            assertEquals(t, s.timeText); assertEquals(v, s.minutes)
        }
        assertEquals(12, DoseSlot("x").withMinutes(720).hour)
        assertNull(parseHm("24:00")); assertNull(parseHm("12:60")); assertNull(parseHm("abc"))
        assertEquals("23:30", minText(-30))
    }

    @Test fun slotsSorted() {
        val m = Medicine("m", "x", "", listOf(DoseSlot("b", "中", 12, 0), DoseSlot("a", "早", 9, 0), DoseSlot("c", "晚", 20, 0)))
        assertEquals(listOf("早", "中", "晚"), m.sortedSlots().slots.map { it.label })
    }
}
