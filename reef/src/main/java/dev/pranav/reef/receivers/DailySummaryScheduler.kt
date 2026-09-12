package dev.pranav.reef.receivers

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object DailySummaryScheduler {
    private const val TAG = "DailySummaryScheduler"

    fun scheduleDailySummary(context: Context) {
        val currentTime = Calendar.getInstance()
        val targetTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= currentTime.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

        val dailySummaryRequest = PeriodicWorkRequestBuilder<DailySummaryWorker>(
            1, TimeUnit.DAYS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailySummaryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailySummaryRequest
        )

        Log.d(
            TAG,
            "Daily summary scheduled with WorkManager, initial delay: ${initialDelay / 1000 / 60} minutes"
        )
    }

    fun cancelDailySummary(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailySummaryWorker.WORK_NAME)
        Log.d(TAG, "Daily summary cancelled")
    }
}
