package com.geekathon.guardpet

import java.time.LocalTime

/** Local-device time rules. Sleep times come from [HabitPolicyStore] prefs when initialized. */
object TimeBehaviorConfig {
    val sleepStart: LocalTime
        get() = runCatching { HabitPolicyStore.sleepStart }.getOrDefault(LocalTime.of(23, 0))

    val wakeTime: LocalTime
        get() = runCatching { HabitPolicyStore.wakeTime }.getOrDefault(LocalTime.of(7, 0))

    val lunchTime: LocalTime = LocalTime.of(12, 0)
    const val CHECK_INTERVAL_MS = 60_000L

    fun isSleepTime(time: LocalTime): Boolean =
        !time.isBefore(sleepStart) || time.isBefore(wakeTime)

    /** True for the entire night window (fixes prior bug where midnight–wake was not "past bedtime"). */
    fun isPastBedtime(time: LocalTime): Boolean = isSleepTime(time)

    fun minutesIntoSleepWindow(now: LocalTime): Int {
        if (!isSleepTime(now)) return 0
        val start = sleepStart
        return if (!now.isBefore(start)) {
            (now.toSecondOfDay() - start.toSecondOfDay()) / 60
        } else {
            val afterMidnight = now.toSecondOfDay() / 60
            val beforeMidnight = (24 * 60) - (start.toSecondOfDay() / 60)
            afterMidnight + beforeMidnight
        }
    }
}
