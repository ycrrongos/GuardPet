package dev.pranav.reef.data

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

data class Routine(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val schedule: RoutineSchedule,
    val limits: List<AppLimit>,
    val groups: List<AppGroup> = emptyList()
) {
    data class AppLimit(
        val packageName: String,
        val limitMinutes: Int
    )

    data class AppGroup(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val type: GroupType,
        val packageNames: List<String>,
        val sharedLimitMinutes: Int = 0,
        val individualLimits: Map<String, Int> = emptyMap()
    ) {
        enum class GroupType {
            SHARED,
            INDIVIDUAL
        }
    }
}

data class RoutineSchedule(
    val type: ScheduleType,
    val timeHour: Int? = null,
    val timeMinute: Int? = null,
    val endTimeHour: Int? = null,
    val endTimeMinute: Int? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val isRecurring: Boolean = true
) {
    enum class ScheduleType {
        DAILY,
        WEEKLY,
        MANUAL
    }

    val time: LocalTime?
        get() = if (timeHour != null && timeMinute != null) {
            LocalTime.of(timeHour, timeMinute)
        } else null

    val endTime: LocalTime?
        get() = if (endTimeHour != null && endTimeMinute != null) {
            LocalTime.of(endTimeHour, endTimeMinute)
        } else null
}
