package com.luge.medreminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** JSON (backup + legacy data.json) codec. Backwards compatible with v1 data.json. */
object Codec {
    fun toJson(d: AppData, prefs: Map<String, Any> = emptyMap()) = JSONObject().apply {
        put("format", "med-checkin-backup"); put("version", 2)
        put("late", d.lateThresholdMin)
        put("winBefore", d.winBefore); put("winAfter", d.winAfter)
        put("prefs", JSONObject(prefs))
        put("medicines", JSONArray(d.medicines.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("dosage", m.dosage).put("start", m.startDate)
                .put("end", m.endDate ?: JSONObject.NULL).put("freq", m.freq.name).put("everyN", m.everyN).put("weekdays", m.weekdays)
                .put("winBefore", m.winBefore ?: JSONObject.NULL).put("winAfter", m.winAfter ?: JSONObject.NULL)
                .put("slots", JSONArray(m.slots.map { s -> JSONObject().put("id", s.id).put("label", s.label).put("h", s.hour).put("m", s.minute) }))
        }))
        put("records", JSONArray(d.records.map { r ->
            JSONObject().put("id", r.id).put("med", r.medId).put("slot", r.slotId).put("date", r.date).put("sch", r.scheduledMin)
                .put("label", r.label).put("st", r.status.name).put("at", r.takenAt).put("note", r.note)
        }))
        put("snapshots", JSONObject().apply {
            d.snapshots.forEach { (date, l) -> put(date, JSONArray(l.map { JSONObject().put("med", it.medId).put("slot", it.slotId).put("label", it.label).put("min", it.minutes) })) }
        })
        put("inventory", JSONArray(d.inventory.values.map {
            JSONObject().put("med", it.medId).put("on", it.enabled).put("left", it.remaining).put("per", it.perDose).put("unit", it.unit).put("low", it.lowThreshold)
        }))
    }

    fun parse(o: JSONObject): AppData {
        val meds = o.optJSONArray("medicines") ?: JSONArray()
        val recs = o.optJSONArray("records") ?: JSONArray()
        val inv = o.optJSONArray("inventory") ?: JSONArray()
        val snaps = o.optJSONObject("snapshots")
        return AppData(
            medicines = (0 until meds.length()).map { i ->
                val m = meds.getJSONObject(i); val sl = m.getJSONArray("slots")
                Medicine(m.getString("id"), m.optString("name"), m.optString("dosage"),
                    (0 until sl.length()).map { j -> val s = sl.getJSONObject(j); DoseSlot(s.getString("id"), s.optString("label"), s.getInt("h"), s.getInt("m")) },
                    m.optString("start", LocalDate.now().toString()),
                    if (m.isNull("end")) null else m.optString("end").ifBlank { null },
                    runCatching { Freq.valueOf(m.optString("freq", "DAILY")) }.getOrDefault(Freq.DAILY),
                    m.optInt("everyN", 2), m.optInt("weekdays", 0x7F),
                    if (m.has("winBefore") && !m.isNull("winBefore")) m.getInt("winBefore") else null,
                    if (m.has("winAfter") && !m.isNull("winAfter")) m.getInt("winAfter") else null)
            },
            records = (0 until recs.length()).map { i ->
                val r = recs.getJSONObject(i)
                val med = r.getString("med"); val slot = r.getString("slot"); val date = r.getString("date")
                Record(med, slot, date, r.getInt("sch"), r.optString("label"), Status.valueOf(r.getString("st")), r.getLong("at"),
                    r.optString("note"), r.optString("id").ifBlank { "$med|$slot|$date" })
            },
            lateThresholdMin = o.optInt("late", 30),
            winBefore = o.optInt("winBefore", 30),
            winAfter = o.optInt("winAfter", 60),
            snapshots = snaps?.keys()?.asSequence()?.associate { k ->
                val a = snaps.getJSONArray(k)
                k to (0 until a.length()).map { val p = a.getJSONObject(it); PlanItem(p.getString("med"), p.getString("slot"), p.optString("label"), p.getInt("min")) }
            } ?: emptyMap(),
            inventory = (0 until inv.length()).associate { i ->
                val x = inv.getJSONObject(i)
                x.getString("med") to Inventory(x.getString("med"), x.optBoolean("on"), x.optDouble("left", 0.0), x.optDouble("per", 1.0), x.optString("unit", "片"), x.optDouble("low", 5.0))
            },
        )
    }
}
