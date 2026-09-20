package com.geekathon.guardpet

import android.app.usage.UsageStatsManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.geekathon.guardpet.ui.HabitGuardianScreen
import dev.pranav.reef.accessibility.BlockerService
import dev.pranav.reef.ui.ReefTheme
import java.time.LocalDate
import java.time.ZoneId

class HabitGuardianActivity : AppCompatActivity() {
    var onRefreshUi: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReefTheme {
                HabitGuardianScreen(this)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_ANALYZE, false) == true ||
            intent?.action == ACTION_HABIT_ANALYZE
        ) {
            window.decorView.post { onRefreshUi?.invoke() }
        }
    }

    override fun onResume() {
        super.onResume()
        onRefreshUi?.invoke()
    }

    fun resolveForegroundPackage(): String? {
        BlockerService.currentForegroundPackage()
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?.let { return it }
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val start = end - 120_000L
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?.asSequence()
            ?.filter { it.packageName != packageName }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun usagePackages(): List<String> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val end = System.currentTimeMillis()
        val start = LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
                ?.filter { it.totalTimeInForeground >= 5 * 60_000L }
                ?.filter { it.packageName != packageName }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.map { it.packageName }
                ?.distinct()
                ?.take(16)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    companion object {
        const val EXTRA_AUTO_ANALYZE = "auto_analyze"
        const val ACTION_HABIT_ANALYZE = "com.geekathon.guardpet.action.HABIT_ANALYZE"
    }
}
