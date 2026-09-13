package com.geekathon.guardpet

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 日程结束闹钟：到点发通知并打开 [ScheduleCompleteActivity]。
 */
object ScheduleEndScheduler {
    private const val CHANNEL_ID = "schedule_end"
    private const val REQ_BASE = 71000

    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val today = LocalDate.now()
        val nowMins = LocalTime.now().hour * 60 + LocalTime.now().minute
        // 先取消当日全部闹钟（含已完成），再只给 pending 重挂
        DayScheduleStore.forDate(today).forEach { cancel(app, am, it.id) }
        DayScheduleStore.forDate(today)
            .filter { it.status == DayScheduleStatus.PENDING }
            .forEach { s ->
                if (s.endMinutes <= nowMins) {
                    notifyEnded(app, s.id)
                } else {
                    schedule(app, am, s)
                }
            }
    }

    fun clearNotified(context: Context, scheduleId: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("n_$scheduleId")
            .apply()
    }

    private fun schedule(context: Context, am: AlarmManager, s: DaySchedule) {
        val trigger = LocalDateTime.of(
            LocalDate.parse(s.date),
            LocalTime.of((s.endMinutes / 60).coerceIn(0, 23), (s.endMinutes % 60).coerceIn(0, 59))
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (trigger <= System.currentTimeMillis()) {
            notifyEnded(context, s.id)
            return
        }
        val pi = pendingIntent(context, s.id)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }.onFailure {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }

    private fun cancel(context: Context, am: AlarmManager, id: Long) {
        am.cancel(pendingIntent(context, id))
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ScheduleEndReceiver::class.java)
            .setAction("com.geekathon.guardpet.SCHEDULE_ENDED")
            .putExtra(ScheduleCompleteActivity.EXTRA_SCHEDULE_ID, id)
        return PendingIntent.getBroadcast(
            context,
            (REQ_BASE + (id % 100000)).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notifyEnded(context: Context, scheduleId: Long) {
        val app = context.applicationContext
        ensureChannel(app)
        val s = DayScheduleStore.byId(scheduleId) ?: return
        if (s.status != DayScheduleStatus.PENDING) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "n_$scheduleId"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val open = Intent(app, ScheduleCompleteActivity::class.java)
            .putExtra(ScheduleCompleteActivity.EXTRA_SCHEDULE_ID, scheduleId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(
            app,
            (REQ_BASE + 200000 + (scheduleId % 100000)).toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.miku_icon)
            .setContentTitle(app.getString(R.string.schedule_end_notif_title))
            .setContentText(app.getString(R.string.schedule_end_notif_body, s.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        runCatching {
            NotificationManagerCompat.from(app).notify(
                (REQ_BASE + 300000 + (scheduleId % 100000)).toInt(),
                notif
            )
        }
    }

    private const val PREFS = "schedule_end_notified"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.schedule_end_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        mgr.createNotificationChannel(ch)
    }
}

class ScheduleEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(ScheduleCompleteActivity.EXTRA_SCHEDULE_ID, -1L) ?: return
        if (id <= 0L) return
        DayScheduleStore.init(context.applicationContext)
        ScheduleEndScheduler.notifyEnded(context, id)
    }
}

class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        DayScheduleStore.init(context.applicationContext)
        ScheduleEndScheduler.rescheduleAll(context)
    }
}
