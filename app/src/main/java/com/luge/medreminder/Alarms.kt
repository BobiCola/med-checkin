package com.luge.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Alarms {
    const val ACTION_ALARM = "com.luge.medreminder.ALARM"
    const val ACTION_TAKEN = "com.luge.medreminder.TAKEN"
    const val ACTION_SNOOZE = "com.luge.medreminder.SNOOZE"
    const val STOCK_CHANNEL = "stock"
    val SOUND_OPTIONS = listOf("sound_vibrate" to "响铃 + 振动", "sound" to "仅响铃", "vibrate" to "仅振动", "silent" to "静音（仅通知）")

    fun channelId(ctx: Context) = "dose_" + Prefs.sound(ctx)

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("dose") // v1 channel
        SOUND_OPTIONS.forEach { (key, name) ->
            val ch = NotificationChannel("dose_$key", "服药提醒 · $name", if (key == "silent") NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH)
            if (key == "vibrate" || key == "silent") ch.setSound(null, null)
            else ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            ch.enableVibration(key == "sound_vibrate" || key == "vibrate")
            if (key == "sound_vibrate" || key == "vibrate") ch.vibrationPattern = longArrayOf(0, 300, 200, 300)
            nm.createNotificationChannel(ch)
        }
        nm.createNotificationChannel(NotificationChannel(STOCK_CHANNEL, "库存提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun base(medId: String, slotId: String) = (medId + slotId).hashCode() and 0x0FFFFFF0

    private fun alarmIntent(ctx: Context, medId: String, slotId: String) =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra("med", medId).putExtra("slot", slotId)

    private fun dailyPi(ctx: Context, medId: String, slotId: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) =
        PendingIntent.getBroadcast(ctx, base(medId, slotId), alarmIntent(ctx, medId, slotId), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun oneOffPi(ctx: Context, medId: String, slotId: String, date: String, attempt: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) =
        PendingIntent.getBroadcast(ctx, base(medId, slotId) + 7,
            alarmIntent(ctx, medId, slotId).putExtra("oneoff", true).putExtra("date", date).putExtra("attempt", attempt),
            flags or PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(ctx: Context, ms: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p) }
    }

    fun rescheduleAll(ctx: Context) {
        Repo.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        prefs.getStringSet("keys", emptySet())!!.forEach { k ->
            val parts = k.split("|"); if (parts.size == 2) dailyPi(ctx, parts[0], parts[1], PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        }
        val keys = mutableSetOf<String>()
        Repo.data.value.medicines.forEach { m -> m.slots.forEach { s -> if (schedule(ctx, m, s)) keys += "${m.id}|${s.id}" } }
        prefs.edit().putStringSet("keys", keys).apply()
    }

    /** Schedules the next occurrence respecting course & frequency. Returns false if the course ended. */
    fun schedule(ctx: Context, m: Medicine, s: DoseSlot): Boolean {
        val t = nextFireTime(m, s, LocalDateTime.now()) ?: return false
        setAlarm(ctx, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), dailyPi(ctx, m.id, s.id))
        return true
    }

    fun scheduleOneOff(ctx: Context, medId: String, slotId: String, date: String, attempt: Int, afterMin: Int) =
        setAlarm(ctx, System.currentTimeMillis() + afterMin * 60_000L, oneOffPi(ctx, medId, slotId, date, attempt))

    fun notify(ctx: Context, m: Medicine, s: DoseSlot, date: String, attempt: Int) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val id = base(m.id, s.id)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(action: String, code: Int, extra: (Intent) -> Unit = {}) = PendingIntent.getBroadcast(ctx, id + code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra("med", m.id).putExtra("slot", s.id).putExtra("date", date)
                .putExtra("attempt", attempt).also(extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = (if (attempt > 0) "再次提醒：" else "该吃药啦：") + m.name.ifBlank { "药物" }
        val text = listOf(s.label, s.timeText, m.dosage).filter { it.isNotBlank() }.joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, channelId(ctx))
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFF1677FF.toInt())
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, "已吃", act(ACTION_TAKEN, 1))
            .addAction(0, "10分钟后", act(ACTION_SNOOZE, 2) { it.putExtra("min", 10) })
            .addAction(0, "30分钟后", act(ACTION_SNOOZE, 3) { it.putExtra("min", 30) })
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }

    /** Replaces the dose notification with an "outside window" notice (no actions). */
    fun notifyOutOfWindow(ctx: Context, m: Medicine, s: DoseSlot, w: Window) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, base(m.id, s.id), Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channelId(ctx)).setSmallIcon(R.drawable.ic_notification).setColor(0xFFFF3141.toInt())
            .setContentTitle("已超出打卡时间").setContentText("${m.name} ${s.label} 可打卡时段 ${windowText(s.minutes, w)}，本次未记录")
            .setContentIntent(open).setAutoCancel(true).setSilent(true).build()
        try { NotificationManagerCompat.from(ctx).notify(base(m.id, s.id), n) } catch (_: SecurityException) {}
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Called after a dose is handled: clears the notification and any pending repeat/snooze. */
    fun cancelNotification(ctx: Context, medId: String, slotId: String) {
        NotificationManagerCompat.from(ctx).cancel(base(medId, slotId))
        val am = ctx.getSystemService(AlarmManager::class.java)
        oneOffPi(ctx, medId, slotId, "", 0, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    fun checkLowStock(ctx: Context, medId: String) {
        val d = Repo.data.value
        val inv = d.inventory[medId] ?: return
        val m = d.medicines.find { it.id == medId } ?: return
        if (!inv.isLow() || !canNotify(ctx)) return
        ensureChannel(ctx)
        val left = if (inv.remaining % 1.0 == 0.0) inv.remaining.toInt().toString() else "%.1f".format(inv.remaining)
        val n = NotificationCompat.Builder(ctx, STOCK_CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(0xFFFF8F1F.toInt())
            .setContentTitle("${m.name} 库存不足").setContentText("仅剩 $left ${inv.unit}，记得及时补药")
            .setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(base(medId, "stock") + 5, n) } catch (_: SecurityException) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        val d = Repo.data.value
        val m = d.medicines.find { it.id == intent.getStringExtra("med") } ?: return
        val s = m.slots.find { it.id == intent.getStringExtra("slot") } ?: return
        val date = intent.getStringExtra("date")?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
        val attempt = intent.getIntExtra("attempt", 0)
        when (intent.action) {
            Alarms.ACTION_ALARM -> {
                val day = LocalDate.parse(date)
                val pending = d.recordFor(m.id, s.id, day) == null && m.isActiveOn(day)
                val nowRel = minutesFrom(day, System.currentTimeMillis())
                val winEnd = s.minutes + d.windowFor(m.id).after
                if (pending && nowRel <= winEnd) {
                    Alarms.notify(ctx, m, s, date, attempt)
                    val rep = Prefs.repeatMin(ctx)
                    // repeat reminders stop at the end of the check-in window
                    if (rep > 0 && attempt < Prefs.repeatCount(ctx) && nowRel + rep <= winEnd) Alarms.scheduleOneOff(ctx, m.id, s.id, date, attempt + 1, rep)
                } else if (pending) Alarms.cancelNotification(ctx, m.id, s.id)
                if (!intent.getBooleanExtra("oneoff", false)) Alarms.schedule(ctx, m, s)
            }
            Alarms.ACTION_TAKEN -> {
                if (Repo.record(ctx, m, s, LocalDate.parse(date), Status.TAKEN)) Alarms.cancelNotification(ctx, m.id, s.id)
                else Alarms.notifyOutOfWindow(ctx, m, s, d.windowFor(m.id))
            }
            Alarms.ACTION_SNOOZE -> {
                Alarms.cancelNotification(ctx, m.id, s.id)
                Alarms.scheduleOneOff(ctx, m.id, s.id, date, attempt, intent.getIntExtra("min", 10))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Alarms.rescheduleAll(ctx)
}
