package dev.pranav.reef.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import dev.pranav.reef.App
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.services.routines.RoutineAlarmScheduler
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!(context.applicationContext as App).initializeAfterUnlock()) {
            Log.w("BootReceiver", "Ignoring ${intent.action} before user unlock")
            return
        }

        val safeContext =
            context.createDeviceProtectedStorageContext()

        if (!isPrefsInitialized) {
            prefs = safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }

        Log.d("BootReceiver", "Action received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                restoreFocusMode(safeContext)

                RoutineSessionManager.evaluateAndSync(safeContext)
                NotificationHelper.syncRoutineNotification(safeContext)
                RoutineAlarmScheduler.scheduleAll(
                    safeContext,
                    dev.pranav.reef.routine.Routines.getAll()
                )

                if (prefs.getBoolean("daily_summary", false)) {
                    DailySummaryScheduler.scheduleDailySummary(safeContext)
                }

                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    prefs.edit { putBoolean("show_dialog", true) }
                }
            }
        }
    }

    private fun restoreFocusMode(context: Context) {
        if (prefs.getBoolean("focus_mode", false)) {
            val serviceIntent = Intent(context, FocusModeService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
