package dev.pranav.reef.services.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.pranav.reef.data.Routine
import dev.pranav.reef.data.RoutineSchedule
import dev.pranav.reef.receivers.RoutineAlarmReceiver
import java.time.LocalDateTime

object RoutineAlarmScheduler {

    fun scheduleNextStart(context: Context, routine: Routine) {
        if (!routine.isEnabled) return
        if (routine.schedule.type == RoutineSchedule.ScheduleType.MANUAL) return
        val nextStartMs = RoutineTimeCalculator.getNextWindowStartMs(
            routine.schedule, LocalDateTime.now()
        ) ?: return
        scheduleAlarm(context, startRequestCode(routine.id), nextStartMs)
    }

    fun scheduleEnd(context: Context, routineId: String, endTimeMs: Long) {
        if (endTimeMs <= 0L) return
        scheduleAlarm(context, endRequestCode(routineId), endTimeMs)
    }

    fun cancel(context: Context, routineId: String) {
        cancelAlarm(context, startRequestCode(routineId))
        cancelAlarm(context, endRequestCode(routineId))
    }

    fun scheduleAll(context: Context, routines: List<Routine>) {
        routines.filter { it.isEnabled }.forEach { scheduleNextStart(context, it) }
    }

    private fun scheduleAlarm(context: Context, requestCode: Int, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            buildPendingIntent(context, requestCode)
        )
    }

    private fun cancelAlarm(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, requestCode))
    }

    private fun buildPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, RoutineAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startRequestCode(routineId: String) = "start:$routineId".hashCode()
    private fun endRequestCode(routineId: String) = "end:$routineId".hashCode()
}
