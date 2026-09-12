package dev.pranav.reef.services.routines

import dev.pranav.reef.data.RoutineSchedule
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object RoutineTimeCalculator {

    data class SessionWindow(
        val startEpochMs: Long,
        val endEpochMs: Long
    )

    fun isActiveAt(schedule: RoutineSchedule, now: LocalDateTime): Boolean {
        return getSessionWindow(schedule, now) != null
    }

    fun getSessionWindow(schedule: RoutineSchedule, now: LocalDateTime): SessionWindow? {
        if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) return null

        val startTime = schedule.time ?: return null
        val endTime = schedule.endTime ?: return null

        val isOvernight = endTime.isBefore(startTime) || endTime == startTime

        val candidates = buildCandidates(now, startTime, endTime, isOvernight)

        for ((candidateStart, candidateEnd) in candidates) {
            if (!isWithinWindow(now, candidateStart, candidateEnd)) continue

            if (schedule.type == RoutineSchedule.ScheduleType.WEEKLY) {
                if (!schedule.daysOfWeek.contains(candidateStart.dayOfWeek)) continue
            }

            return SessionWindow(
                startEpochMs = candidateStart.toEpochMs(),
                endEpochMs = candidateEnd.toEpochMs()
            )
        }

        return null
    }

    fun getNextWindowStartMs(schedule: RoutineSchedule, now: LocalDateTime): Long? {
        if (schedule.type == RoutineSchedule.ScheduleType.MANUAL) return null
        val startTime = schedule.time ?: return null
        val today = now.toLocalDate()

        return when (schedule.type) {
            RoutineSchedule.ScheduleType.DAILY -> {
                val todayStart = LocalDateTime.of(today, startTime)
                if (now.isBefore(todayStart)) {
                    todayStart.toEpochMs()
                } else {
                    LocalDateTime.of(today.plusDays(1), startTime).toEpochMs()
                }
            }

            RoutineSchedule.ScheduleType.WEEKLY -> {
                if (schedule.daysOfWeek.isEmpty()) return null
                for (daysAhead in 0..7) {
                    val candidate = today.plusDays(daysAhead.toLong())
                    if (candidate.dayOfWeek !in schedule.daysOfWeek) continue
                    val candidateStart = LocalDateTime.of(candidate, startTime)
                    if (daysAhead == 0 && !now.isBefore(candidateStart)) continue
                    return candidateStart.toEpochMs()
                }
                null
            }

            RoutineSchedule.ScheduleType.MANUAL -> null
        }
    }

    fun getDurationMs(schedule: RoutineSchedule): Long {
        val startTime = schedule.time ?: return 24 * 60 * 60 * 1000L
        val endTime = schedule.endTime ?: return 24 * 60 * 60 * 1000L

        val startMinutes = startTime.hour * 60 + startTime.minute
        val endMinutes = endTime.hour * 60 + endTime.minute

        val durationMinutes = if (endMinutes > startMinutes) {
            endMinutes - startMinutes
        } else {
            (24 * 60 - startMinutes) + endMinutes
        }

        return durationMinutes * 60 * 1000L
    }

    private fun buildCandidates(
        now: LocalDateTime,
        startTime: LocalTime,
        endTime: LocalTime,
        isOvernight: Boolean
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)

        val candidates = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()

        val todayStart = LocalDateTime.of(today, startTime)
        val todayEnd = if (isOvernight) {
            LocalDateTime.of(today.plusDays(1), endTime)
        } else {
            LocalDateTime.of(today, endTime)
        }
        candidates.add(todayStart to todayEnd)

        if (isOvernight) {
            val yesterdayStart = LocalDateTime.of(yesterday, startTime)
            val yesterdayEnd = LocalDateTime.of(today, endTime)
            candidates.add(yesterdayStart to yesterdayEnd)
        }

        return candidates
    }

    private fun isWithinWindow(
        now: LocalDateTime,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime
    ): Boolean {
        return (now.isEqual(windowStart) || now.isAfter(windowStart)) && now.isBefore(windowEnd)
    }

    private fun LocalDateTime.toEpochMs(): Long {
        return atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

