package com.geekathon.guardpet.friend

import android.content.Context
import java.util.UUID

class FriendPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var serverHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var roomCode: String
        get() = (prefs.getString(KEY_ROOM, "GUARD") ?: "GUARD").uppercase()
        set(value) = prefs.edit().putString(KEY_ROOM, value.trim().uppercase().take(12)).apply()

    var displayName: String
        get() = prefs.getString(KEY_NAME, "守伴用户") ?: "守伴用户"
        set(value) = prefs.edit().putString(KEY_NAME, value.trim().take(24).ifBlank { "守伴用户" }).apply()

    val userId: String
        get() {
            val existing = prefs.getString(KEY_UID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = "u_" + UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(KEY_UID, id).apply()
            return id
        }

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    companion object {
        private const val NAME = "friend_prefs"
        private const val KEY_HOST = "server_host"
        private const val KEY_ROOM = "room_code"
        private const val KEY_NAME = "display_name"
        private const val KEY_UID = "user_id"
        private const val KEY_AUTO = "auto_connect"
    }
}
