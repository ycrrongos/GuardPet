package com.geekathon.guardpet.friend

import android.content.Context

/**
 * 习惯等级 / 经验（Minecraft 分段升级曲线）。
 */
class HabitXpStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var level: Int
        get() = prefs.getInt(KEY_LEVEL, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_LEVEL, value.coerceAtLeast(0)).apply()

    var xp: Int
        get() = prefs.getInt(KEY_XP, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_XP, value.coerceAtLeast(0)).apply()

    var lastSettleDate: String
        get() = prefs.getString(KEY_SETTLE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SETTLE_DATE, value).apply()

    var lastGainXp: Int
        get() = prefs.getInt(KEY_LAST_GAIN, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_GAIN, value).apply()

    var lastSettleNote: String
        get() = prefs.getString(KEY_NOTE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NOTE, value).apply()

    fun xpIntoLevel(): Int = xp

    fun xpToNext(): Int = xpNeededForLevel(level)

    fun progressFraction(): Float {
        val need = xpToNext().coerceAtLeast(1)
        return (xp.toFloat() / need).coerceIn(0f, 1f)
    }

    fun addXp(amount: Int): Pair<Int, Int> {
        if (amount <= 0) return level to xp
        var lv = level
        var cur = xp + amount
        var need = xpNeededForLevel(lv)
        var guard = 0
        while (cur >= need && guard < 1000) {
            cur -= need
            lv += 1
            need = xpNeededForLevel(lv)
            guard++
        }
        level = lv
        xp = cur
        return lv to cur
    }

    companion object {
        private const val NAME = "habit_xp"
        private const val KEY_LEVEL = "level"
        private const val KEY_XP = "xp"
        private const val KEY_SETTLE_DATE = "last_settle_date"
        private const val KEY_LAST_GAIN = "last_gain"
        private const val KEY_NOTE = "last_note"

        /** Minecraft 风格：升到下一级所需经验。 */
        fun xpNeededForLevel(level: Int): Int {
            val n = level.coerceAtLeast(0)
            return when {
                n < 16 -> 2 * n + 7
                n < 31 -> 5 * n - 38
                else -> 9 * n - 158
            }.coerceAtLeast(7)
        }
    }
}
