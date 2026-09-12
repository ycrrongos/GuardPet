package com.geekathon.guardpet

import android.content.Context

class PetSettings(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {
        applyTimeDecay()
    }

    var edgeWalkEnabled: Boolean
        get() { applyTimeDecay(); return preferences.getBoolean(KEY_EDGE_WALK, true) }
        set(value) = preferences.edit().putBoolean(KEY_EDGE_WALK, value).apply()

    var mood: Int
        get() { applyTimeDecay(); return preferences.getInt(KEY_MOOD, 72) }
        set(value) = preferences.edit().putInt(KEY_MOOD, value.coerceIn(0, 100)).apply()

    var hunger: Int
        get() { applyTimeDecay(); return preferences.getInt(KEY_HUNGER, 60) }
        set(value) {
            val next = value.coerceIn(0, 100)
            preferences.edit()
                .putInt(KEY_HUNGER, next)
                .apply()
            if (next == 0) preferences.edit().putInt(KEY_MOOD, 0).apply()
        }

    var foodCount: Int
        get() { applyTimeDecay(); return preferences.getInt(KEY_FOOD, 0) }
        set(value) = preferences.edit().putInt(KEY_FOOD, value.coerceAtLeast(0)).apply()

    var petScale: Float
        get() { applyTimeDecay(); return preferences.getFloat(KEY_PET_SCALE, 1f) }
        set(value) = preferences.edit().putFloat(KEY_PET_SCALE, value.coerceIn(0.55f, 1.45f)).apply()

    var walkSpeed: Int
        get() { applyTimeDecay(); return preferences.getInt(KEY_WALK_SPEED, 50) }
        set(value) = preferences.edit().putInt(KEY_WALK_SPEED, value.coerceIn(10, 500)).apply()

    var semiHidden: Boolean
        get() { applyTimeDecay(); return preferences.getBoolean(KEY_SEMI_HIDDEN, false) }
        set(value) = preferences.edit().putBoolean(KEY_SEMI_HIDDEN, value).apply()

    fun actionFor(gesture: PetGesture): PetAction =
        PetAction.fromKey(preferences.getString(gestureKey(gesture), null), gesture.defaultAction)

    fun setAction(gesture: PetGesture, action: PetAction) {
        preferences.edit().putString(gestureKey(gesture), action.key).apply()
    }

    /** Applies about 40 points of mood and hunger decay per real day, including time offline. */
    private fun applyTimeDecay(now: Long = System.currentTimeMillis()) {
        val last = preferences.getLong(KEY_LAST_DECAY, 0L)
        if (last <= 0L) {
            preferences.edit().putLong(KEY_LAST_DECAY, now).apply()
            return
        }
        val elapsed = (now - last).coerceAtLeast(0L)
        val currentHunger = preferences.getInt(KEY_HUNGER, 60)
        if (currentHunger == 0 && preferences.getInt(KEY_MOOD, 72) != 0) {
            preferences.edit().putInt(KEY_MOOD, 0).apply()
        }
        val points = (elapsed.toDouble() * DAILY_DECAY / DAY_MS).toInt()
        if (points <= 0) return
        val oldMood = preferences.getInt(KEY_MOOD, 72)
        val oldHunger = preferences.getInt(KEY_HUNGER, 60)
        val newHunger = (oldHunger - points).coerceAtLeast(0)
        val newMood = if (newHunger == 0) 0 else (oldMood - points).coerceAtLeast(0)
        val consumedMs = (points.toDouble() * DAY_MS / DAILY_DECAY).toLong()
        preferences.edit()
            .putInt(KEY_MOOD, newMood)
            .putInt(KEY_HUNGER, newHunger)
            .putLong(KEY_LAST_DECAY, last + consumedMs)
            .apply()
    }
    companion object {
        private const val NAME = "pet_settings"
        private const val KEY_EDGE_WALK = "edge_walk_enabled"
        private const val KEY_MOOD = "mood"
        private const val KEY_HUNGER = "hunger"
        private const val KEY_FOOD = "food_count"
        private const val KEY_PET_SCALE = "pet_scale"
        private const val KEY_WALK_SPEED = "walk_speed"
        private const val KEY_SEMI_HIDDEN = "semi_hidden"
        private const val KEY_LAST_DECAY = "last_decay_time"
        private const val DAY_MS = 86_400_000L
        private const val DAILY_DECAY = 40

        private fun gestureKey(gesture: PetGesture) = "gesture_${gesture.key}"
    }
}
