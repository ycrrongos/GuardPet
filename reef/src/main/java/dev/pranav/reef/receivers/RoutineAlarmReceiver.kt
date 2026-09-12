package dev.pranav.reef.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

class RoutineAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val safeContext = context.createDeviceProtectedStorageContext()
        if (!isPrefsInitialized) {
            prefs = safeContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }
        RoutineSessionManager.evaluateAndSync(safeContext)
        NotificationHelper.syncRoutineNotification(safeContext)
    }
}
