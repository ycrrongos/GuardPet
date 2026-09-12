package dev.pranav.reef.data

import kotlinx.serialization.Serializable

@Serializable
enum class SessionType { SIMPLE, POMODORO }

@Serializable
enum class PhaseType { FOCUS, SHORT_BREAK, LONG_BREAK }

@Serializable
data class BlockEvent(
    val packageName: String,
    val timestamp: Long,
    val reason: String
)

@Serializable
data class PhaseEntry(
    val type: PhaseType,
    val startTimestamp: Long,
    val endTimestamp: Long = 0L,
    val plannedDuration: Long,
    val actualDuration: Long = 0L,
    val isCompleted: Boolean = false,
    val blockEvents: List<BlockEvent> = emptyList()
)

@Serializable
data class FocusSession(
    val id: String,
    val startTimestamp: Long,
    val endTimestamp: Long = 0L,
    val sessionType: SessionType,
    val isCompleted: Boolean = false,
    val phases: List<PhaseEntry> = emptyList()
) {
    val totalFocusTime: Long
        get() = phases.filter { it.type == PhaseType.FOCUS }.sumOf { it.actualDuration }

    val totalBlockEvents: Int
        get() = phases.sumOf { it.blockEvents.size }

    val completedCycles: Int
        get() = phases.count { it.type == PhaseType.FOCUS && it.isCompleted }
}
