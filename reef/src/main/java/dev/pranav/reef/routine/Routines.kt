package dev.pranav.reef.routine

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.pranav.reef.data.Routine
import dev.pranav.reef.data.RoutineSchedule
import dev.pranav.reef.services.routines.RoutineAlarmScheduler
import dev.pranav.reef.services.routines.RoutineSessionManager
import dev.pranav.reef.util.prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.util.UUID

/**
 * Single, simple routine system. Supports MULTIPLE active routines simultaneously.
 */
object Routines {
    private const val TAG = "Routines"
    private const val ROUTINES_KEY = "routines"

    fun getAll(): List<Routine> {
        val json = prefs.getString(ROUTINES_KEY, "[]") ?: "[]"
        return try {
            JSONArray(json).let { arr ->
                (0 until arr.length()).mapNotNull { parseRoutine(arr.getJSONObject(it)) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Routine? = getAll().find { it.id == id }

    fun save(routine: Routine, context: Context) {
        val routines = getAll().toMutableList()
        val index = routines.indexOfFirst { it.id == routine.id }

        if (index >= 0) routines[index] = routine
        else routines.add(routine)

        saveAll(routines)

        RoutineSessionManager.updateSessionLimits(routine)
    }

    fun saveAll(routines: List<Routine>, context: Context) {
        saveAll(routines)
    }

    fun delete(id: String, context: Context) {
        RoutineSessionManager.stopSession(context, id)
        val routines = getAll().filterNot { it.id == id }
        saveAll(routines)
    }

    fun toggle(id: String, context: Context) {
        Log.d(TAG, "Toggle called for routine ID: $id")

        val routine = get(id) ?: run {
            Log.e(TAG, "Routine not found: $id")
            return
        }

        val updated = routine.copy(isEnabled = !routine.isEnabled)
        Log.d(TAG, "Routine: ${routine.name}, ${routine.isEnabled} -> ${updated.isEnabled}")

        val routines = getAll().toMutableList()
        val index = routines.indexOfFirst { it.id == id }
        if (index >= 0) routines[index] = updated
        saveAll(routines)

        if (!updated.isEnabled) {
            RoutineSessionManager.stopSession(context, id)
            RoutineAlarmScheduler.cancel(context, id)
        } else {
            when (updated.schedule.type) {
                RoutineSchedule.ScheduleType.MANUAL -> {
                    RoutineSessionManager.startSession(context, updated)
                }

                RoutineSchedule.ScheduleType.DAILY,
                RoutineSchedule.ScheduleType.WEEKLY -> {
                    RoutineSessionManager.activateIfInWindow(context, updated)
                }
            }
        }
    }

    private fun saveAll(routines: List<Routine>) {
        val json = JSONArray().apply {
            routines.forEach { put(routineToJson(it)) }
        }
        prefs.edit { putString(ROUTINES_KEY, json.toString()) }
    }

    fun createDefaults(): List<Routine> = listOf(
        Routine(
            id = UUID.randomUUID().toString(),
            name = "Weekend Digital Detox",
            isEnabled = false,
            schedule = RoutineSchedule(
                type = RoutineSchedule.ScheduleType.WEEKLY,
                timeHour = 9,
                timeMinute = 0,
                endTimeHour = 18,
                endTimeMinute = 0,
                daysOfWeek = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            ),
            limits = emptyList()
        ),
        Routine(
            id = UUID.randomUUID().toString(),
            name = "Workday Focus",
            isEnabled = false,
            schedule = RoutineSchedule(
                type = RoutineSchedule.ScheduleType.WEEKLY,
                timeHour = 9,
                timeMinute = 0,
                endTimeHour = 17,
                endTimeMinute = 0,
                daysOfWeek = setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY
                )
            ),
            limits = emptyList()
        )
    )

    private fun parseRoutine(json: JSONObject): Routine? = try {
        val scheduleJson = json.getJSONObject("schedule")
        val schedule = RoutineSchedule(
            type = RoutineSchedule.ScheduleType.valueOf(scheduleJson.getString("type")),
            timeHour = scheduleJson.optInt("timeHour").takeIf { scheduleJson.has("timeHour") },
            timeMinute = scheduleJson.optInt("timeMinute")
                .takeIf { scheduleJson.has("timeMinute") },
            endTimeHour = scheduleJson.optInt("endTimeHour")
                .takeIf { scheduleJson.has("endTimeHour") },
            endTimeMinute = scheduleJson.optInt("endTimeMinute")
                .takeIf { scheduleJson.has("endTimeMinute") },
            daysOfWeek = scheduleJson.optJSONArray("daysOfWeek")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    try {
                        DayOfWeek.valueOf(arr.getString(it))
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
            } ?: emptySet(),
            isRecurring = scheduleJson.optBoolean("isRecurring", true)
        )

        val limits = json.getJSONArray("limits").let { arr ->
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { limitJson ->
                    Routine.AppLimit(
                        packageName = limitJson.getString("packageName"),
                        limitMinutes = limitJson.getInt("limitMinutes")
                    )
                }
            }
        }

        val groups = json.optJSONArray("groups")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val g = arr.getJSONObject(i)
                    val pkgs = g.getJSONArray("packageNames").let { pkgArr ->
                        (0 until pkgArr.length()).map { j -> pkgArr.getString(j) }
                    }
                    val indivLimits = g.optJSONObject("individualLimits")?.let { limitsJson ->
                        val map = mutableMapOf<String, Int>()
                        limitsJson.keys().forEach { key -> map[key] = limitsJson.getInt(key) }
                        map.toMap()
                    } ?: emptyMap()
                    Routine.AppGroup(
                        id = g.optString("id", UUID.randomUUID().toString()),
                        name = g.getString("name"),
                        type = Routine.AppGroup.GroupType.valueOf(g.getString("type")),
                        packageNames = pkgs,
                        sharedLimitMinutes = g.optInt("sharedLimitMinutes", 0),
                        individualLimits = indivLimits
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } ?: emptyList()

        Routine(
            id = json.getString("id"),
            name = json.getString("name"),
            isEnabled = json.getBoolean("isEnabled"),
            schedule = schedule,
            limits = limits,
            groups = groups
        )
    } catch (_: Exception) {
        null
    }

    private fun routineToJson(routine: Routine) = JSONObject().apply {
        put("id", routine.id)
        put("name", routine.name)
        put("isEnabled", routine.isEnabled)

        put("schedule", JSONObject().apply {
            val s = routine.schedule
            put("type", s.type.name)
            s.timeHour?.let { put("timeHour", it) }
            s.timeMinute?.let { put("timeMinute", it) }
            s.endTimeHour?.let { put("endTimeHour", it) }
            s.endTimeMinute?.let { put("endTimeMinute", it) }
            put("daysOfWeek", JSONArray().apply { s.daysOfWeek.forEach { put(it.name) } })
            put("isRecurring", s.isRecurring)
        })

        put("limits", JSONArray().apply {
            routine.limits.forEach { limit ->
                put(JSONObject().apply {
                    put("packageName", limit.packageName)
                    put("limitMinutes", limit.limitMinutes)
                })
            }
        })

        put("groups", JSONArray().apply {
            routine.groups.forEach { group ->
                put(JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("type", group.type.name)
                    put(
                        "packageNames",
                        JSONArray().apply { group.packageNames.forEach { put(it) } })
                    put("sharedLimitMinutes", group.sharedLimitMinutes)
                    put("individualLimits", JSONObject().apply {
                        group.individualLimits.forEach { (pkg, minutes) -> put(pkg, minutes) }
                    })
                })
            }
        })
    }
}
