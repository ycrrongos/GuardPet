package dev.pranav.reef.util

import android.content.Context
import dev.pranav.reef.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

object FocusStats {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var file: File

    private val _sessions = MutableStateFlow<List<FocusSession>>(emptyList())
    val sessions: StateFlow<List<FocusSession>> = _sessions.asStateFlow()

    var activeSession: FocusSession? = null
        private set
    var activePhase: PhaseEntry? = null
        private set

    fun init(context: Context) {
        file = File(context.filesDir, "focus_sessions.json")
        if (file.exists()) {
            runCatching {
                _sessions.value = json.decodeFromString<List<FocusSession>>(file.readText())
                    .sortedByDescending { it.startTimestamp }
            }
        }
    }

    fun startSession(type: SessionType) {
        activeSession = FocusSession(
            id = UUID.randomUUID().toString(),
            startTimestamp = System.currentTimeMillis(),
            sessionType = type
        )
    }

    fun startPhase(type: PhaseType, plannedDuration: Long) {
        activePhase = PhaseEntry(
            type = type,
            startTimestamp = System.currentTimeMillis(),
            plannedDuration = plannedDuration
        )
    }

    fun recordBlockEvent(packageName: String, reason: String) {
        val phase = activePhase ?: return
        activePhase = phase.copy(
            blockEvents = phase.blockEvents + BlockEvent(
                packageName,
                System.currentTimeMillis(),
                reason
            )
        )
    }

    fun endPhase(isCompleted: Boolean) {
        val phase = activePhase ?: return
        val now = System.currentTimeMillis()
        val finished = phase.copy(
            endTimestamp = now,
            actualDuration = now - phase.startTimestamp,
            isCompleted = isCompleted
        )
        activeSession = activeSession?.copy(phases = activeSession!!.phases + finished)
        activePhase = null
    }

    fun endSession(isCompleted: Boolean) {
        endPhase(isCompleted)
        val session = activeSession ?: return
        val closed = session.copy(
            endTimestamp = System.currentTimeMillis(),
            isCompleted = isCompleted
        )
        _sessions.value =
            (listOf(closed) + _sessions.value).sortedByDescending { it.startTimestamp }
        activeSession = null
        save()
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(_sessions.value)) }
    }

    fun sessionsForDay(dayOffset: Int = 0): List<FocusSession> {
        val day = LocalDate.now().plusDays(dayOffset.toLong())
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end =
            day.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return _sessions.value.filter { it.startTimestamp in start..end }
    }

    fun sessionsToday(): List<FocusSession> = sessionsForDay(0)

    fun sessionsForWeek(weekOffset: Int = 0): List<FocusSession> {
        val week = LocalDate.now().plusWeeks(weekOffset.toLong())
        val start = week.with(DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant()
            .toEpochMilli()
        val end = week.with(DayOfWeek.SUNDAY).atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return _sessions.value.filter { it.startTimestamp in start..end }
    }

    fun sessionsForMonth(monthOffset: Int = 0): List<FocusSession> {
        val month = YearMonth.now().plusMonths(monthOffset.toLong())
        val start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end =
            month.atEndOfMonth().atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
                .toEpochMilli()
        return _sessions.value.filter { it.startTimestamp in start..end }
    }

    /** 7 (dayLabel, totalFocusMinutes) pairs for the Vico weekly chart. */
    fun weeklyChartData(weekOffset: Int = 0): List<Pair<String, Float>> {
        val monday = LocalDate.now().plusWeeks(weekOffset.toLong()).with(DayOfWeek.MONDAY)
        return (0..6).map { i ->
            val day = monday.plusDays(i.toLong())
            val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd =
                day.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val minutes = _sessions.value
                .filter { it.startTimestamp in dayStart..dayEnd }
                .sumOf { it.totalFocusTime } / 60_000f
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to minutes
        }
    }

    /** Per-week (label, totalFocusMinutes) pairs for the monthly chart. */
    fun monthlyChartData(monthOffset: Int = 0): List<Pair<String, Float>> {
        val month = YearMonth.now().plusMonths(monthOffset.toLong())
        val firstDay = month.atDay(1)
        val lastDay = month.atEndOfMonth()
        val result = mutableListOf<Pair<String, Float>>()
        var current = firstDay
        while (!current.isAfter(lastDay)) {
            val weekEnd = minOf(current.plusDays(6), lastDay)
            val start = current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val end = weekEnd.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
                .toEpochMilli()
            val minutes = _sessions.value
                .filter { it.startTimestamp in start..end }
                .sumOf { it.totalFocusTime } / 60_000f
            result.add(current.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) to minutes)
            current = current.plusDays(7)
        }
        return result
    }

    fun sessionById(id: String) = _sessions.value.find { it.id == id }

    fun generateSampleData() {
        val rng = Random.Default
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        val totalDays = 90

        repeat(totalDays) { i ->
            // 0–2 sessions per day across the last 90 days
            val dayOffset = i * day
            val sessionCount = rng.nextInt(0, 3)
            repeat(sessionCount) { s ->
                val sessionStart =
                    now - dayOffset - rng.nextLong(2 * 60 * 60 * 1000L) - s * 90 * 60 * 1000L
                val isPomodoro = rng.nextBoolean()

                val session = FocusSession(
                    id = java.util.UUID.randomUUID().toString(),
                    startTimestamp = sessionStart,
                    sessionType = if (isPomodoro) SessionType.POMODORO else SessionType.SIMPLE
                )

                val phases = buildList {
                    if (isPomodoro) {
                        val cycles = rng.nextInt(1, 5)
                        repeat(cycles) { c ->
                            val focusDuration = rng.nextLong(20 * 60_000L, 26 * 60_000L)
                            val focusActual = if (c == cycles - 1 && rng.nextBoolean())
                                rng.nextLong(focusDuration / 2, focusDuration)
                            else focusDuration
                            val focusStart = sessionStart + c * (25 + 5) * 60_000L
                            val focusBlocks = buildList {
                                repeat(rng.nextInt(0, 4)) {
                                    add(
                                        BlockEvent(
                                            packageName = samplePackages.random(rng),
                                            timestamp = focusStart + rng.nextLong(focusActual),
                                            reason = "focus_mode"
                                        )
                                    )
                                }
                            }
                            add(
                                PhaseEntry(
                                    type = PhaseType.FOCUS,
                                    startTimestamp = focusStart,
                                    endTimestamp = focusStart + focusActual,
                                    plannedDuration = focusDuration,
                                    actualDuration = focusActual,
                                    isCompleted = focusActual == focusDuration,
                                    blockEvents = focusBlocks
                                )
                            )
                            val breakDuration = if (c == cycles - 1) 15 * 60_000L else 5 * 60_000L
                            val breakStart = focusStart + focusActual
                            val breakActual = if (rng.nextBoolean()) breakDuration
                            else rng.nextLong(breakDuration / 2, breakDuration)
                            val breakBlocks = buildList {
                                repeat(rng.nextInt(0, 3)) {
                                    add(
                                        BlockEvent(
                                            packageName = samplePackages.random(rng),
                                            timestamp = breakStart + rng.nextLong(breakActual),
                                            reason = "focus_mode"
                                        )
                                    )
                                }
                            }
                            add(
                                PhaseEntry(
                                    type = if (c == cycles - 1) PhaseType.LONG_BREAK else PhaseType.SHORT_BREAK,
                                    startTimestamp = breakStart,
                                    endTimestamp = breakStart + breakActual,
                                    plannedDuration = breakDuration,
                                    actualDuration = breakActual,
                                    isCompleted = breakActual == breakDuration,
                                    blockEvents = breakBlocks
                                )
                            )
                        }
                    } else {
                        val planned = rng.nextLong(15 * 60_000L, 60 * 60_000L)
                        val actual = if (rng.nextBoolean()) planned
                        else rng.nextLong(planned / 2, planned)
                        val blocks = buildList {
                            repeat(rng.nextInt(0, 6)) {
                                add(
                                    BlockEvent(
                                        packageName = samplePackages.random(rng),
                                        timestamp = sessionStart + rng.nextLong(actual),
                                        reason = "focus_mode"
                                    )
                                )
                            }
                        }
                        add(
                            PhaseEntry(
                                type = PhaseType.FOCUS,
                                startTimestamp = sessionStart,
                                endTimestamp = sessionStart + actual,
                                plannedDuration = planned,
                                actualDuration = actual,
                                isCompleted = actual == planned,
                                blockEvents = blocks
                            )
                        )
                    }
                }

                val endTs = phases.lastOrNull()?.endTimestamp ?: sessionStart
                val completed = phases.all { it.isCompleted }
                val closed = session.copy(
                    endTimestamp = endTs,
                    isCompleted = completed,
                    phases = phases
                )
                _sessions.value =
                    (_sessions.value + closed).sortedByDescending { it.startTimestamp }
            }
        }
        save()
    }

    fun clearAllData() {
        _sessions.value = emptyList()
        activeSession = null
        activePhase = null
        save()
    }

    private val samplePackages = listOf(
        "com.instagram.android",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.google.android.youtube",
        "com.whatsapp",
        "com.facebook.katana",
        "com.netflix.mediaclient",
        "com.spotify.music"
    )
}
