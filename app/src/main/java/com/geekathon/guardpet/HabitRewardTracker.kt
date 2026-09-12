package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * Mood deltas and pet reactions for habit events. Cool-downs prevent farming.
 */
object HabitRewardTracker {
    private const val PREFS = "habit_rewards"
    private const val KEY_DAY = "day"
    private const val KEY_MARK_BONUS = "mark_bonus"
    private const val KEY_COMPLY_PREFIX = "comply_"
    private const val KEY_PENALTY_HOUR = "penalty_hour_"
    private const val KEY_FOCUS_BLOCKED = "focus_blocked"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private var lastBlockKey: String? = null
    private var lastBlockAtElapsed = 0L
    private var pendingComplyKey: String? = null
    private var pendingComplyAtElapsed = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() =
        requireNotNull(appContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resetDayIfNeeded() {
        val today = LocalDate.now().toString()
        val p = prefs()
        if (p.getString(KEY_DAY, null) == today) return
        p.edit().clear().putString(KEY_DAY, today).apply()
    }

    fun onHabitBlock(context: Context, key: String, sleepLock: Boolean, testMode: Boolean) {
        resetDayIfNeeded()
        if (testMode) {
            notifyPet(context, reaction = "bored", moodDelta = 0, stateHint = PetState.BORED.key)
            return
        }
        markFocusSessionBlocked()
        val now = SystemClock.elapsedRealtime()
        val recent = key == lastBlockKey && now - lastBlockAtElapsed < 10 * 60_000L
        lastBlockKey = key
        lastBlockAtElapsed = now
        pendingComplyKey = key
        pendingComplyAtElapsed = now

        val hourBucket = System.currentTimeMillis() / 3_600_000L
        val hourKey = KEY_PENALTY_HOUR + key.hashCode() + "_" + hourBucket
        val hourPenalty = prefs().getInt(hourKey, 0)
        if (hourPenalty >= 12) {
            notifyPet(
                context,
                reaction = "angry",
                moodDelta = 0,
                stateHint = PetState.ANGRY.key
            )
            return
        }
        val delta = when {
            sleepLock -> -8
            recent -> -2
            else -> -5
        }
        prefs().edit().putInt(hourKey, hourPenalty + (-delta)).apply()
        notifyPet(
            context,
            reaction = if (sleepLock) "sleep_angry" else "angry",
            moodDelta = delta,
            stateHint = if (sleepLock) PetState.SLEEP.key else PetState.ANGRY.key
        )
    }

    fun onAllowedForeground(context: Context, packageName: String) {
        resetDayIfNeeded()
        val key = pendingComplyKey ?: return
        if (!key.startsWith(packageName) && key != packageName && !key.contains(packageName)) {
            // still allow generic package comply when user left the blocked surface
        }
        val now = SystemClock.elapsedRealtime()
        if (now - pendingComplyAtElapsed < 2 * 60_000L) return
        if (now - pendingComplyAtElapsed > 15 * 60_000L) {
            pendingComplyKey = null
            return
        }
        // User stayed away ~2 minutes after a block → small reward
        val complyCountKey = KEY_COMPLY_PREFIX + key.hashCode()
        val count = prefs().getInt(complyCountKey, 0)
        if (count >= 3) {
            pendingComplyKey = null
            return
        }
        prefs().edit().putInt(complyCountKey, count + 1).apply()
        pendingComplyKey = null
        notifyPet(context, reaction = "happy", moodDelta = 1, stateHint = PetState.HAPPY.key)
    }

    fun noteComplianceOpportunity(packageName: String, surfaceId: String?) {
        // Keep pending key from onHabitBlock; surface id already encoded there.
        if (pendingComplyKey == null) {
            pendingComplyKey = packageName + ":" + (surfaceId ?: "pkg")
            pendingComplyAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    fun onMarkAssist(context: Context) {
        resetDayIfNeeded()
        val bonus = prefs().getInt(KEY_MARK_BONUS, 0)
        if (bonus >= 6) return
        prefs().edit().putInt(KEY_MARK_BONUS, bonus + 2).apply()
        notifyPet(context, reaction = "happy", moodDelta = 2, stateHint = PetState.HAPPY.key)
    }

    fun onFocusSessionStarted() {
        resetDayIfNeeded()
        prefs().edit().putBoolean(KEY_FOCUS_BLOCKED, false).apply()
    }

    fun markFocusSessionBlocked() {
        prefs().edit().putBoolean(KEY_FOCUS_BLOCKED, true).apply()
    }

    fun onFocusSessionCompleted(context: Context) {
        resetDayIfNeeded()
        if (prefs().getBoolean(KEY_FOCUS_BLOCKED, false)) return
        notifyPet(context, reaction = "happy", moodDelta = 6, stateHint = PetState.HAPPY.key)
    }

    fun onNightScheduleHonored(context: Context) {
        resetDayIfNeeded()
        notifyPet(context, reaction = "happy", moodDelta = 4, stateHint = PetState.HAPPY.key)
    }

    fun onBedtimeHonored(context: Context) {
        resetDayIfNeeded()
        notifyPet(context, reaction = "happy", moodDelta = 5, stateHint = PetState.SLEEP.key)
    }

    private fun notifyPet(
        context: Context,
        reaction: String,
        moodDelta: Int,
        stateHint: String
    ) {
        val app = appContext ?: context.applicationContext
        mainHandler.post {
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, PetService::class.java)
                        .setAction(PetService.ACTION_HABIT_REACTION)
                        .putExtra(PetService.EXTRA_REACTION, reaction)
                        .putExtra(PetService.EXTRA_MOOD_DELTA, moodDelta)
                        .putExtra(PetService.EXTRA_STATE, stateHint)
                )
            }
        }
    }
}
