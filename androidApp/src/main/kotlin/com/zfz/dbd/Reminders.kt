package com.zfz.dbd

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

internal enum class ReminderSound(val label: String) {
    DEFAULT("系统默认"), CHIME("轻柔铃声"), BELL("清脆铃声"), SILENT("静音");

    fun uri(context: Context): Uri? = when (this) {
        DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        CHIME -> Uri.parse("android.resource://${context.packageName}/raw/chime")
        BELL -> Uri.parse("android.resource://${context.packageName}/raw/bell")
        SILENT -> null
    }
}

internal data class CheckIn(
    val id: String,
    val title: String,
    val hour: Int,
    val minute: Int,
    val interval: Int,
    val sound: ReminderSound,
    val completedDay: String = "",
    val nextReminder: Long = 0
) {
    val timeLabel: String get() = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    val intervalLabel: String get() =
        if (interval % 60 == 0) "${interval / 60} 小时" else "$interval 分钟"
}

internal class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("daybyday", Context.MODE_PRIVATE)

    fun read(): List<CheckIn> {
        val json = JSONArray(prefs.getString("items", "[]"))
        return List(json.length()) { index ->
            val row = json.getJSONObject(index)
            CheckIn(
                row.getString("id"), row.getString("title"), row.getInt("hour"),
                row.getInt("minute"), row.getInt("interval"),
                ReminderSound.valueOf(row.getString("sound")), row.optString("completedDay"),
                row.optLong("nextReminder")
            )
        }
    }

    fun readHistory(): Map<String, Map<String, String>> {
        val saved = prefs.getString("history", null)
        if (saved == null) {
            return read().filter { it.completedDay.isNotEmpty() }
                .groupBy { it.completedDay }
                .mapValues { (_, items) -> items.associate { it.id to it.title } }
        }
        val days = JSONObject(saved)
        return days.keys().asSequence().associateWith { day ->
            val records = days.getJSONObject(day)
            records.keys().asSequence().associateWith { records.getString(it) }
        }
    }

    fun save(item: CheckIn, recordCompletion: Boolean) {
        write(read().filterNot { it.id == item.id } + item, item.takeIf { recordCompletion })
    }

    fun remove(id: String) = write(read().filterNot { it.id == id }, pruneHistory = true)

    fun pruneDeletedHistory() {
        val saved = prefs.getString("history", null) ?: return
        val history = JSONObject(saved)
        if (removeDeletedRecords(history, read())) {
            prefs.edit().putString("history", history.toString()).commit()
        }
    }

    private fun removeDeletedRecords(history: JSONObject, items: List<CheckIn>): Boolean {
        val ids = items.mapTo(HashSet()) { it.id }
        var changed = false
        val days = history.keys()
        while (days.hasNext()) {
            val records = history.getJSONObject(days.next())
            val entries = records.keys()
            while (entries.hasNext()) {
                if (entries.next() !in ids) {
                    entries.remove()
                    changed = true
                }
            }
            if (records.length() == 0) {
                days.remove()
                changed = true
            }
        }
        return changed
    }

    private fun write(items: List<CheckIn>, completed: CheckIn? = null, pruneHistory: Boolean = false) {
        val editor = prefs.edit()
        val savedHistory = prefs.getString("history", null)
        if (savedHistory == null || completed != null || pruneHistory) {
            val history = if (savedHistory == null) {
                JSONObject().apply {
                    readHistory().forEach { (day, records) -> put(day, JSONObject(records)) }
                }
            } else {
                JSONObject(savedHistory)
            }
            if (completed != null) {
                val day = completed.completedDay
                val records = history.optJSONObject(day) ?: JSONObject().also { history.put(day, it) }
                if (!records.has(completed.id)) records.put(completed.id, completed.title)
            }
            if (pruneHistory) removeDeletedRecords(history, items)
            editor.putString("history", history.toString())
        }
        val json = JSONArray()
        for (item in items) {
            json.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("hour", item.hour)
                put("minute", item.minute)
                put("interval", item.interval)
                put("sound", item.sound.name)
                put("completedDay", item.completedDay)
                put("nextReminder", item.nextReminder)
            })
        }
        editor.putString("items", json.toString()).commit()
    }
}

internal fun dayKey(timestamp: Long = System.currentTimeMillis()): String {
    val date = Calendar.getInstance().apply { timeInMillis = timestamp }
    return "${date.get(Calendar.YEAR)}-${date.get(Calendar.DAY_OF_YEAR)}"
}

internal class Reminders(private val context: Context) {
    private val store = ReminderStore(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun canScheduleExactly(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun notificationsEnabled(): Boolean =
        notifications.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun save(item: CheckIn) {
        notifications.cancel(item.id, 1)
        val completedDay = store.read().firstOrNull { it.id == item.id }?.completedDay.orEmpty()
        schedule(item.copy(completedDay = completedDay, nextReminder = 0))
        CheckInWidget.refresh(context)
    }

    fun delete(id: String) {
        store.remove(id)
        alarms.cancel(action(ALARM, id))
        notifications.cancel(id, 1)
        CheckInWidget.refresh(context)
    }

    fun reconcile(resetTimes: Boolean = false) {
        store.pruneDeletedHistory()
        for (item in store.read()) {
            val current = if (resetTimes) item.copy(nextReminder = 0) else item
            schedule(current)
        }
        CheckInWidget.refresh(context)
    }

    fun complete(id: String, day: String) {
        val item = store.read().firstOrNull { it.id == id } ?: return
        if (day != dayKey()) {
            CheckInWidget.refresh(context)
            return
        }
        if (item.completedDay == day) return
        notifications.cancel(id, 1)
        schedule(item.copy(completedDay = day, nextReminder = 0), recordCompletion = true)
        CheckInWidget.refresh(context)
    }

    fun snooze(id: String, day: String, firedAt: Long) {
        val item = store.read().firstOrNull { it.id == id } ?: return
        if (day != dayKey() || item.completedDay == day || !isCurrentNotification(id, firedAt)) return
        notifications.cancel(id, 1)
        schedule(item.copy(nextReminder = followUp(item, System.currentTimeMillis())))
    }

    fun fire(id: String, expected: Long) {
        val item = store.read().firstOrNull { it.id == id } ?: return
        if (item.nextReminder != expected) return
        val now = System.currentTimeMillis()
        if (expected > now) {
            schedule(item)
            return
        }
        val today = dayKey(now)
        if (item.completedDay != today && dayKey(expected) == today) {
            show(item, today, expected)
            schedule(item.copy(nextReminder = followUp(item, now)))
        } else {
            schedule(item.copy(nextReminder = 0))
        }
        CheckInWidget.refresh(context)
    }

    private fun followUp(item: CheckIn, now: Long): Long {
        val next = now + item.interval * 60_000L
        return if (dayKey(next) == dayKey(now)) next else dailyTime(item, now, tomorrow = true)
    }

    private fun dailyTime(item: CheckIn, now: Long, tomorrow: Boolean = false): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            if (tomorrow) add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun schedule(item: CheckIn, recordCompletion: Boolean = false) {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val next = when {
            item.nextReminder > now -> item.nextReminder
            item.completedDay == today -> dailyTime(item, now, tomorrow = true)
            else -> maxOf(dailyTime(item, now), now + 1_000L)
        }
        store.save(item.copy(nextReminder = next), recordCompletion)
        val operation = action(ALARM, item.id, firedAt = next)
        if (canScheduleExactly()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
                return
            } catch (_: SecurityException) {
                // Access may have changed since the permission check.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
    }

    private fun show(item: CheckIn, today: String, firedAt: Long) {
        if (!notificationsEnabled()) return
        val sound = item.sound.uri(context)
        val channelId = "reminder.${item.sound.name.lowercase()}"
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(channelId, "打卡 · ${item.sound.label}", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(sound, attributes) }
            )
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setSound(sound, attributes)
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = builder
            .setSmallIcon(R.drawable.ic_check_in)
            .setContentTitle(item.title)
            .setContentText("该打卡了 · ${item.intervalLabel}后再次提醒")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(firedAt)
            .addAction(Notification.Action.Builder(null, "确认打卡", action(DONE, item.id, today)).build())
            .addAction(Notification.Action.Builder(null, "稍等一下", action(SNOOZE, item.id, today, firedAt)).build())
            .build()
        notifications.notify(item.id, 1, notification)
    }

    private fun isCurrentNotification(id: String, firedAt: Long): Boolean =
        notifications.activeNotifications.any { it.tag == id && it.notification.`when` == firedAt }

    private fun action(type: String, id: String, day: String = "", firedAt: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java)
                .setAction(type)
                .setData(Uri.parse("daybyday://reminder/$id").buildUpon().apply {
                    if (type != ALARM) appendQueryParameter("occurrence", "$day-$firedAt")
                }.build())
                .putExtra("day", day)
                .putExtra("firedAt", firedAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ALARM = "com.zfz.dbd.ALARM"
        const val DONE = "com.zfz.dbd.DONE"
        const val SNOOZE = "com.zfz.dbd.SNOOZE"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = Reminders(context)
        val id = intent.data?.lastPathSegment
        val day = intent.getStringExtra("day").orEmpty()
        val firedAt = intent.getLongExtra("firedAt", 0)
        when (intent.action) {
            Reminders.ALARM -> if (id != null) reminders.fire(id, firedAt)
            Reminders.DONE -> if (id != null) reminders.complete(id, day)
            Reminders.SNOOZE -> if (id != null) reminders.snooze(id, day, firedAt)
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> reminders.reconcile(resetTimes = true)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> reminders.reconcile()
        }
    }
}
