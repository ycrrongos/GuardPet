package com.geekathon.guardpet

import android.content.Context

class PetSettings(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {
        applyTimeDecay()
    }

    var dashScopeApiKey: String
        get() = preferences.getString(KEY_DASHSCOPE_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DASHSCOPE_API_KEY
        set(value) {
            preferences.edit().putString(KEY_DASHSCOPE_API_KEY, value.trim()).apply()
        }

    var deepSeekApiKey: String
        get() = preferences.getString(KEY_DEEPSEEK_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEEPSEEK_API_KEY
        set(value) {
            preferences.edit().putString(KEY_DEEPSEEK_API_KEY, value.trim()).apply()
        }

    var openAiApiKey: String
        get() = preferences.getString(KEY_OPENAI_API_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENAI_API_KEY
        set(value) {
            preferences.edit().putString(KEY_OPENAI_API_KEY, value.trim()).apply()
        }

    var openAiBaseUrl: String
        get() = preferences.getString(KEY_OPENAI_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENAI_BASE_URL.ifBlank { OpenAiImageClient.DEFAULT_BASE_URL }
        set(value) {
            preferences.edit().putString(KEY_OPENAI_BASE_URL, value.trim()).apply()
        }

    var appearanceMode: PetAppearanceGenerator.GenerationMode
        get() = when (preferences.getString(KEY_APPEARANCE_MODE, MODE_FAST)) {
            MODE_QUALITY -> PetAppearanceGenerator.GenerationMode.QUALITY
            else -> PetAppearanceGenerator.GenerationMode.FAST
        }
        set(value) {
            val stored = when (value) {
                PetAppearanceGenerator.GenerationMode.QUALITY -> MODE_QUALITY
                PetAppearanceGenerator.GenerationMode.FAST -> MODE_FAST
            }
            preferences.edit().putString(KEY_APPEARANCE_MODE, stored).apply()
        }

    fun appearanceCredentials(): PetAppearanceAgent.Credentials =
        PetAppearanceAgent.Credentials(
            dashScopeApiKey = dashScopeApiKey,
            deepSeekApiKey = deepSeekApiKey,
            openAiApiKey = openAiApiKey,
            openAiBaseUrl = openAiBaseUrl
        )

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
        private const val KEY_DASHSCOPE_API_KEY = "dashscope_api_key"
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val MODE_FAST = "fast"
        private const val MODE_QUALITY = "quality"
        private const val DAY_MS = 86_400_000L
        private const val DAILY_DECAY = 40

        private fun gestureKey(gesture: PetGesture) = "gesture_${gesture.key}"
    }
}
