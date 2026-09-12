package dev.pranav.reef.receivers

import android.Manifest
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.pranav.reef.MainActivity
import dev.pranav.reef.R
import dev.pranav.reef.util.REMINDER_CHANNEL_ID
import dev.pranav.reef.util.ScreenUsageHelper
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class DailySummaryWorker(
    private val context: Context,
    workerParams: WorkerParameters
): Worker(context, workerParams) {

    override fun doWork(): Result {
        if (!isPrefsInitialized) {
            prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }

        if (!prefs.getBoolean("daily_summary", false)) {
            return Result.success()
        }

        showDailySummaryNotification()
        return Result.success()
    }

    private fun showDailySummaryNotification() {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val todayUsage = ScreenUsageHelper.fetchAppUsageTodayTillNow(usageStatsManager)
        val totalUsageSeconds = todayUsage.values.sum()
        val totalUsageMinutes = totalUsageSeconds / 60

        val hours = totalUsageMinutes / 60
        val minutes = totalUsageMinutes % 60

        val usageText = when {
            hours > 0 && minutes > 0 -> context.getString(
                R.string.hour_min_short_suffix,
                hours,
                minutes
            )

            hours > 0 -> context.getString(R.string.hours_short_format, hours)
            minutes > 0 -> context.getString(R.string.minutes_short_format, minutes)
            else -> context.getString(R.string.less_than_one_minute)
        }

        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to_usage", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.daily_summary_title))
            .setContentText(context.getString(R.string.daily_summary_message, usageText))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(DAILY_SUMMARY_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val DAILY_SUMMARY_NOTIFICATION_ID = 300
        const val WORK_NAME = "daily_summary_work"
    }
}

