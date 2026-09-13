package com.geekathon.guardpet.friend

import android.app.usage.UsageStatsManager
import android.content.Context
import dev.pranav.reef.util.ScreenUsageHelper
import dev.pranav.reef.util.hasUsageStatsPermission
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object HabitXpSettler {
    data class Result(
        val ok: Boolean,
        val message: String,
        val gainedXp: Int = 0,
        val level: Int = 0,
        val xp: Int = 0
    )

    /**
     * 用「昨日同时段」对比「今日至今」总屏幕分钟：今日更少则按节省量给经验。
     * 每天最多成功结算一次（可强制 [force]）。
     */
    fun settle(context: Context, force: Boolean = false): Result {
        val app = context.applicationContext
        val store = HabitXpStore(app)
        val today = LocalDate.now().toString()
        if (!force && store.lastSettleDate == today) {
            return Result(
                ok = false,
                message = "今日已结算：${store.lastSettleNote.ifBlank { "无变化" }}",
                level = store.level,
                xp = store.xp
            )
        }
        if (!app.hasUsageStatsPermission()) {
            return Result(ok = false, message = "需要使用情况访问权限才能结算习惯进步", level = store.level, xp = store.xp)
        }
        val usm = app.getSystemService(UsageStatsManager::class.java)
            ?: return Result(ok = false, message = "无法读取用量", level = store.level, xp = store.xp)

        val zone = ZoneId.systemDefault()
        val now = LocalTime.now()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        val yDay = LocalDate.now().minusDays(1)
        val yStart = yDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val ySame = yDay.atTime(now).atZone(zone).toInstant().toEpochMilli()

        val todayMs = ScreenUsageHelper.fetchUsageInMs(usm, todayStart, nowMs).values.sum()
        val ySameMs = ScreenUsageHelper.fetchUsageInMs(usm, yStart, ySame).values.sum()
        val todayMin = TimeUnit.MILLISECONDS.toMinutes(todayMs).toInt()
        val yMin = TimeUnit.MILLISECONDS.toMinutes(ySameMs).toInt()
        val saved = (yMin - todayMin).coerceAtLeast(0)

        // 每少用 1 分钟 ≈ 2 XP；至少有一点点摸鱼惩罚为 0
        val gained = when {
            saved <= 0 -> 0
            else -> (saved * 2).coerceAtMost(500)
        }
        val note = if (gained > 0) {
            "同时段少用 ${saved} 分钟，+$gained XP（昨${yMin}′ / 今${todayMin}′）"
        } else {
            "同时段未少于昨日（昨${yMin}′ / 今${todayMin}′），未获得经验"
        }
        val (lv, xp) = if (gained > 0) store.addXp(gained) else store.level to store.xp
        store.lastSettleDate = today
        store.lastGainXp = gained
        store.lastSettleNote = note
        return Result(ok = true, message = note, gainedXp = gained, level = lv, xp = xp)
    }
}
