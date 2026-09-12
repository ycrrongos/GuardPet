package dev.pranav.reef.accessibility

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

object WebsiteUsageTracker {

    private const val PREF_USAGE = "website_usage"
    private lateinit var prefs: SharedPreferences

    private var currentTrackingDomain: String? = null
    private var trackingStartTime: Long = 0L

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_USAGE, Context.MODE_PRIVATE)
        checkAndResetDailyUsage()
    }

    private fun checkAndResetDailyUsage() {
        val lastResetDate = prefs.getLong("last_reset_date", 0L)
        val todayStart = startOfToday()
        if (lastResetDate < todayStart) {
            prefs.edit {
                clear()
                putLong("last_reset_date", todayStart)
            }
        }
    }

    private fun startOfToday(): Long =
        LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun startTracking(domain: String) {
        if (currentTrackingDomain == domain) return
        stopTracking()

        currentTrackingDomain = domain
        trackingStartTime = System.currentTimeMillis()
    }

    fun stopTracking() {
        val domain = currentTrackingDomain ?: return
        val now = System.currentTimeMillis()
        val duration = now - trackingStartTime

        checkAndResetDailyUsage()

        val currentUsage = prefs.getLong(domain, 0L)
        prefs.edit { putLong(domain, currentUsage + duration) }

        currentTrackingDomain = null
        trackingStartTime = 0L
    }

    fun getDailyUsage(domain: String): Long {
        checkAndResetDailyUsage()
        var usage = prefs.getLong(domain, 0L)
        if (currentTrackingDomain == domain) {
            usage += (System.currentTimeMillis() - trackingStartTime)
        }
        return usage
    }

    fun getCurrentTrackingDomain(): String? = currentTrackingDomain
}
