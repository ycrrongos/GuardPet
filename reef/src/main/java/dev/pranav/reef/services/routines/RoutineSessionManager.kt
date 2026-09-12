package dev.pranav.reef.services.routines

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.pranav.reef.data.Routine
import dev.pranav.reef.data.RoutineSchedule
import dev.pranav.reef.util.NotificationHelper
import dev.pranav.reef.util.ScreenUsageHelper
import dev.pranav.reef.util.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

object RoutineSessionManager {
    private const val TAG = "RoutineSessionManager"
    private const val ACTIVE_SESSIONS_KEY = "active_routine_sessions"

    data class SharedGroupSession(
        val groupId: String,
        val packageNames: List<String>,
        val sharedLimitMs: Long
    )

    data class ActiveSession(
        val routineId: String,
        val startTime: Long,
        val endTime: Long,
        val isManual: Boolean,
        val limits: Map<String, Long>,
        val sharedGroups: List<SharedGroupSession> = emptyList()
    )

    @Volatile
    private var cachedSessions: List<ActiveSession>? = null

    fun evaluateAndSync(context: Context) {
        val routines = dev.pranav.reef.routine.Routines.getAll()
        val now = System.currentTimeMillis()
        var changed = false

        // 1. Expire sessions whose endTime has passed
        val currentSessions = getActiveSessions()
        val expired = currentSessions.filter { it.endTime > 0 && now >= it.endTime }
        for (session in expired) {
            Log.d(TAG, "Expiring session: ${session.routineId}")
            stopSessionInternal(context, session.routineId)
            routines.find { it.id == session.routineId }?.let { routine ->
                if (routine.isEnabled) RoutineAlarmScheduler.scheduleNextStart(context, routine)
            }
            changed = true
        }

        // 2. Remove sessions for routines that were deleted or disabled
        val enabledIds = routines.filter { it.isEnabled }.map { it.id }.toSet()
        val allIds = routines.map { it.id }.toSet()
        for (session in getActiveSessions()) {
            if (session.routineId !in allIds) {
                Log.d(TAG, "Removing orphaned session: ${session.routineId}")
                stopSessionInternal(context, session.routineId)
                changed = true
            } else if (session.routineId !in enabledIds) {
                Log.d(TAG, "Removing disabled routine session: ${session.routineId}")
                stopSessionInternal(context, session.routineId)
                changed = true
            }
        }

        // 3. Activate routines that should be active now but have no session
        val activeIds = getActiveSessions().map { it.routineId }.toSet()
        val toActivate =
            RoutineEvaluator.findRoutinesToActivate(routines, activeIds, LocalDateTime.now())
        for (info in toActivate) {
            Log.d(TAG, "Auto-activating routine: ${info.routine.name}")
            startSessionInternal(context, info.routine, info.sessionStartMs, info.sessionEndMs)
            changed = true
        }

        if (changed) {
            Log.d(TAG, "Sync complete. Active sessions: ${getActiveSessions().size}")
        }
    }

    fun startSession(context: Context, routine: Routine) {
        val now = System.currentTimeMillis()
        val endTime = if (routine.schedule.type == RoutineSchedule.ScheduleType.MANUAL) {
            0L
        } else {
            val window =
                RoutineTimeCalculator.getSessionWindow(routine.schedule, LocalDateTime.now())
            window?.endEpochMs ?: (now + RoutineTimeCalculator.getDurationMs(routine.schedule))
        }
        startSessionInternal(context, routine, now, endTime)
    }

    fun activateIfInWindow(context: Context, routine: Routine) {
        val window = RoutineTimeCalculator.getSessionWindow(routine.schedule, LocalDateTime.now())
        if (window != null) {
            Log.d(TAG, "Routine ${routine.name} is within schedule window, activating immediately")
            startSessionInternal(context, routine, window.startEpochMs, window.endEpochMs)
        } else {
            Log.d(
                TAG,
                "Routine ${routine.name} is outside schedule window, scheduling alarm for next start"
            )
            RoutineAlarmScheduler.scheduleNextStart(context, routine)
        }
    }

    fun stopSession(context: Context, routineId: String) {
        stopSessionInternal(context, routineId)
    }

    private fun startSessionInternal(
        context: Context,
        routine: Routine,
        startTime: Long,
        endTime: Long
    ) {
        val sessions = getActiveSessions().toMutableList()
        sessions.removeAll { it.routineId == routine.id }

        val limits = buildLimitsMap(routine)
        val sharedGroups = buildSharedGroups(routine)

        val session = ActiveSession(
            routineId = routine.id,
            startTime = startTime,
            endTime = endTime,
            isManual = routine.schedule.type == RoutineSchedule.ScheduleType.MANUAL,
            limits = limits,
            sharedGroups = sharedGroups
        )
        sessions.add(session)
        saveActiveSessions(sessions)

        Log.d(
            TAG,
            "Started session for ${routine.name}: ${limits.size} limits, ${sharedGroups.size} groups, endTime=${if (endTime == 0L) "never" else endTime}"
        )
        if (endTime > 0L) {
            RoutineAlarmScheduler.scheduleEnd(context, routine.id, endTime)
        }
        NotificationHelper.syncRoutineNotification(context)
    }

    private fun stopSessionInternal(context: Context, routineId: String) {
        val sessions = getActiveSessions().toMutableList()
        val removed = sessions.removeAll { it.routineId == routineId }

        if (removed) {
            saveActiveSessions(sessions)
            NotificationHelper.syncRoutineNotification(context)
            Log.d(TAG, "Stopped session: $routineId. Remaining: ${sessions.size}")
        }
    }

    private fun buildLimitsMap(routine: Routine): Map<String, Long> {
        val limits = mutableMapOf<String, Long>()
        routine.limits.forEach { limits[it.packageName] = it.limitMinutes * 60_000L }
        routine.groups
            .filter { it.type == Routine.AppGroup.GroupType.INDIVIDUAL }
            .forEach { group ->
                group.individualLimits.forEach { (pkg, minutes) ->
                    limits[pkg] = minutes * 60_000L
                }
            }
        return limits
    }

    private fun buildSharedGroups(routine: Routine): List<SharedGroupSession> {
        return routine.groups
            .filter { it.type == Routine.AppGroup.GroupType.SHARED }
            .map { group ->
                SharedGroupSession(
                    groupId = group.id,
                    packageNames = group.packageNames,
                    sharedLimitMs = group.sharedLimitMinutes * 60_000L
                )
            }
    }

    fun getActiveSessions(): List<ActiveSession> {
        cachedSessions?.let { return it }
        val loaded = loadSessions()
        cachedSessions = loaded
        return loaded
    }

    fun getLimitMs(packageName: String): Long? {
        val now = System.currentTimeMillis()
        val sessions = getActiveSessions().filter { it.endTime == 0L || now < it.endTime }
        if (sessions.isEmpty()) return null

        var strictestLimit: Long? = null

        for (session in sessions) {
            val individualLimit = session.limits[packageName]
            if (individualLimit != null) {
                strictestLimit =
                    strictestLimit?.let { minOf(it, individualLimit) } ?: individualLimit
            }

            for (group in session.sharedGroups) {
                if (packageName in group.packageNames) {
                    strictestLimit = strictestLimit?.let { minOf(it, group.sharedLimitMs) }
                        ?: group.sharedLimitMs
                }
            }
        }

        return strictestLimit
    }

    fun getUsageMs(context: Context, packageName: String): Long {
        val now = System.currentTimeMillis()
        val sessions = getActiveSessions().filter { it.endTime == 0L || now < it.endTime }
        if (sessions.isEmpty()) return 0L

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var maxUsage = 0L

        for (session in sessions) {
            val sharedGroup = session.sharedGroups.find { packageName in it.packageNames }

            if (sharedGroup != null) {
                val groupUsage = sharedGroup.packageNames.sumOf { pkg ->
                    ScreenUsageHelper.fetchUsageInMs(
                        usm,
                        session.startTime,
                        System.currentTimeMillis(),
                        pkg
                    )[pkg] ?: 0L
                }
                if (groupUsage > maxUsage) maxUsage = groupUsage
            } else if (session.limits.containsKey(packageName)) {
                val usage = ScreenUsageHelper.fetchUsageInMs(
                    usm, session.startTime, System.currentTimeMillis(), packageName
                )[packageName] ?: 0L
                if (usage > maxUsage) maxUsage = usage
            }
        }

        return maxUsage
    }

    fun updateSessionLimits(routine: Routine) {
        val sessions = getActiveSessions().toMutableList()
        val index = sessions.indexOfFirst { it.routineId == routine.id }
        if (index < 0) return

        val old = sessions[index]
        sessions[index] = old.copy(
            limits = buildLimitsMap(routine),
            sharedGroups = buildSharedGroups(routine)
        )
        saveActiveSessions(sessions)
    }

    fun hasActiveSession(routineId: String): Boolean {
        return getActiveSessions().any { it.routineId == routineId }
    }

    fun getActiveRoutineNames(): List<String> {
        val sessions = getActiveSessions()
        return sessions.mapNotNull { session ->
            dev.pranav.reef.routine.Routines.get(session.routineId)?.name
        }
    }

    private fun saveActiveSessions(sessions: List<ActiveSession>) {
        val json = JSONArray().apply {
            sessions.forEach { session ->
                put(JSONObject().apply {
                    put("routineId", session.routineId)
                    put("startTime", session.startTime)
                    put("endTime", session.endTime)
                    put("isManual", session.isManual)
                    put("limits", JSONObject().apply {
                        session.limits.forEach { (pkg, limit) -> put(pkg, limit) }
                    })
                    put("sharedGroups", JSONArray().apply {
                        session.sharedGroups.forEach { group ->
                            put(JSONObject().apply {
                                put("groupId", group.groupId)
                                put("packageNames", JSONArray().apply {
                                    group.packageNames.forEach { put(it) }
                                })
                                put("sharedLimitMs", group.sharedLimitMs)
                            })
                        }
                    })
                })
            }
        }
        prefs.edit { putString(ACTIVE_SESSIONS_KEY, json.toString()) }
        cachedSessions = sessions
    }

    private fun loadSessions(): List<ActiveSession> {
        val json = prefs.getString(ACTIVE_SESSIONS_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val limitsJson = obj.getJSONObject("limits")
                    val limits = mutableMapOf<String, Long>()
                    limitsJson.keys().forEach { key -> limits[key] = limitsJson.getLong(key) }

                    val sharedGroups = obj.optJSONArray("sharedGroups")?.let { groupArr ->
                        (0 until groupArr.length()).mapNotNull { gi ->
                            try {
                                val g = groupArr.getJSONObject(gi)
                                val pkgs = g.getJSONArray("packageNames").let { pkgArr ->
                                    (0 until pkgArr.length()).map { j -> pkgArr.getString(j) }
                                }
                                SharedGroupSession(
                                    groupId = g.getString("groupId"),
                                    packageNames = pkgs,
                                    sharedLimitMs = g.getLong("sharedLimitMs")
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                    } ?: emptyList()

                    ActiveSession(
                        routineId = obj.getString("routineId"),
                        startTime = obj.getLong("startTime"),
                        endTime = obj.optLong("endTime", 0L),
                        isManual = obj.optBoolean("isManual", false),
                        limits = limits,
                        sharedGroups = sharedGroups
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun invalidateCache() {
        cachedSessions = null
    }
}
